package dev.doughbay.engine;

import dev.doughbay.api.ApiException;
import dev.doughbay.api.DonutApiClient;
import dev.doughbay.api.ResponseParser.ParseResult;
import dev.doughbay.api.ResponseParser.ParsedListing;
import dev.doughbay.api.ResponseParser.ParsedSale;
import dev.doughbay.core.analysis.AnalyzerConfig;
import dev.doughbay.core.analysis.CommodityRegistry;
import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.analysis.MarketAnalyzer;
import dev.doughbay.core.analysis.RiskConfig;
import dev.doughbay.core.analysis.OpportunityDetector;
import dev.doughbay.core.model.ItemFingerprint;
import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.NamespacedId;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Sale;
import dev.doughbay.core.model.StackBucket;
import dev.doughbay.storage.Database;
import dev.doughbay.storage.ItemRepository;
import dev.doughbay.storage.ListingRepository;
import dev.doughbay.storage.MarketStatsRepository;
import dev.doughbay.storage.OpportunityRepository;
import dev.doughbay.storage.TransactionRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates collection, analysis, and opportunity detection. Read-only with
 * respect to the game: it calls the market-data API and writes to the local
 * database, nothing else.
 */
public final class MarketService {

    /**
     * Consecutive per-commodity API failures that end a sweep. Past this the
     * explanation is the service, not the book, and continuing would retry
     * every remaining commodity against something already known to be down.
     */
    private static final int MAX_CONSECUTIVE_SCAN_FAILURES = 5;

    /**
     * Rows in a full active-listing page. The API documents this only for
     * transactions ("100 items per page") and leaves the listings page size
     * unstated, so it is asserted here rather than inferred: a page carrying
     * fewer rows is the last one.
     *
     * <p>Under-stating it is safe — a genuinely full page then never reads as
     * terminal, so the scan simply fails to prove exhaustion. Over-stating it
     * is not, because every full page would look short and a partial book
     * could be mistaken for a whole one. A page larger than this value proves
     * the assumption wrong, and the scan refuses to claim completeness.
     */
    private static final int LISTING_PAGE_SIZE = 100;

    /**
     * Markets given a row, ranked by completed-sale volume. A cap keeps the
     * cost of the display list independent of how large the archive grows.
     */
    private static final int MAX_DISPLAYED_MARKETS = 250;
    /**
     * Sales loaded per market for percentile estimation. Far more than robust
     * statistics need, and valuation is recency-weighted anyway, so the rows
     * beyond this carry effectively no weight.
     */
    private static final int MAX_SALES_PER_MARKET = 2_000;

    private final AnalyzerConfig analyzerConfig;
    private final FeeConfig fees;
    private final RiskConfig risk;
    private final CommodityRegistry commodities;
    private final DonutApiClient client;
    private final TransactionRepository transactions;
    private final ListingRepository listings;
    private final ItemRepository items;
    private final MarketStatsRepository statsRepo;
    private final OpportunityRepository opportunities;
    private final MarketAnalyzer analyzer;

    public MarketService(AnalyzerConfig analyzerConfig, FeeConfig fees, RiskConfig risk,
                         CommodityRegistry commodities, DonutApiClient client, Database db) {
        this.analyzerConfig = analyzerConfig;
        this.fees = fees;
        this.risk = risk;
        this.commodities = commodities;
        this.client = client;
        this.transactions = new TransactionRepository(db);
        this.listings = new ListingRepository(db);
        this.items = new ItemRepository(db);
        this.statsRepo = new MarketStatsRepository(db);
        this.opportunities = new OpportunityRepository(db);
        this.analyzer = new MarketAnalyzer(analyzerConfig);
    }

    public record CollectReport(int pagesFetched, int recordsSeen, int newRecords,
                                int duplicates, int parseFailures, List<Sale> newSales) {
    }

    /** Downloads transaction pages and stores anything new. */
    public CollectReport collectTransactions(int fromPage, int toPage)
            throws ApiException, InterruptedException, SQLException {
        int seen = 0, added = 0, dupes = 0, failures = 0;
        List<Sale> newSales = new ArrayList<>();
        for (int page = fromPage; page <= toPage; page++) {
            ParseResult<ParsedSale> result = client.fetchTransactions(page);
            failures += result.failures().size();
            for (ParsedSale parsed : result.records()) {
                seen++;
                boolean commodity = commodities.isEligible(parsed.fingerprint());
                items.upsertSeen(parsed.fingerprint(), commodity, parsed.sale().soldAt());
                if (transactions.insertIfAbsent(parsed.sale(), parsed.rawJson())) {
                    added++;
                    newSales.add(parsed.sale());
                } else {
                    dupes++;
                }
            }
            // An empty page means we've walked past the available history.
            if (result.records().isEmpty() && result.failures().isEmpty()) break;
        }
        return new CollectReport(toPage - fromPage + 1, seen, added, dupes, failures, newSales);
    }

    public record ScanReport(int itemsScanned, int completeItems, int incompleteItems,
                             int listingsSeen, int parseFailures,
                             Map<String, List<Listing>> commodityListings) {
        public boolean completeForValuation() {
            return itemsScanned > 0
                    && completeItems == itemsScanned
                    && incompleteItems == 0
                    && parseFailures == 0;
        }
    }

    /**
     * Exhaustive, exact-item active-listing evidence used by execution
     * reconciliation. {@code complete} means the scan reached a clean terminal
     * page before the page cap and encountered no malformed or unidentifiable
     * exact rows. A capped or partially parsed scan must never be interpreted
     * as proof that a listing is absent.
     *
     * @param exhaustionProven whether the whole active book was observed: a
     *        page shorter than the first, or an empty page. The API returns
     *        HTTP 500 for a page past the end rather than an empty page, so
     *        an empty page alone can never be relied on as the proof.
     * @param ambiguousIdentities rows that were read correctly but cannot be
     *        told apart. The listings endpoint publishes no listing id, so two
     *        identical stacks from one seller at one price collapse to the
     *        same derived key. Their price and supply are fully known, so this
     *        never blocks valuation; it blocks only {@link #provenForAbsence()}.
     */
    public record ExactListingScanReport(String itemId, List<Listing> listings,
                                         int pagesScanned, int parseFailures,
                                         int ambiguousIdentities,
                                         boolean exhaustionProven,
                                         boolean complete, String detail) {
        public ExactListingScanReport {
            itemId = NamespacedId.normalize(itemId);
            listings = List.copyOf(listings);
            detail = detail == null ? "" : detail;
            if (pagesScanned < 0 || parseFailures < 0 || ambiguousIdentities < 0) {
                throw new IllegalArgumentException("scan counts cannot be negative");
            }
            if (complete != (exhaustionProven && parseFailures == 0)) {
                throw new IllegalArgumentException(
                        "complete requires a clean terminal page");
            }
        }

        /**
         * Whether this book can prove a specific listing is gone.
         *
         * <p>Strictly stronger than {@link #complete()}: absence reconciliation
         * must be able to name one row, so indistinguishable duplicates make
         * the proof unavailable even though the book itself was fully read.
         * Execution uses this; valuation must not.
         */
        public boolean provenForAbsence() {
            return complete && ambiguousIdentities == 0;
        }
    }

    /**
     * Focused scan: search each tracked commodity and snapshot its current
     * listings. Only metadata-plain commodity listings enter the analysis set.
     */
    public ScanReport scanCommodityListings() throws ApiException, InterruptedException, SQLException {
        return scanCommodityListings(10);
    }

    /**
     * Exhaustively scans every configured commodity. Only markets that reach a
     * clean terminal page are admitted to valuation. A page-capped, malformed,
     * identity-ambiguous, or unreachable book is archived for diagnostics but
     * suppressed from opportunity detection so page-one depth can never be
     * mistaken for the whole active market.
     *
     * <p>One commodity's transport failure leaves that book unproven without
     * abandoning the rest of the sweep. A run of consecutive failures is a
     * different thing — the API itself is unwell — and still aborts, rather
     * than retrying every remaining commodity against a service that is down.
     */
    public ScanReport scanCommodityListings(int maxPages)
            throws ApiException, InterruptedException, SQLException {
        if (maxPages <= 0) throw new IllegalArgumentException("maxPages must be positive");
        int itemsScanned = 0, completeItems = 0, incompleteItems = 0;
        int listingsSeen = 0, failures = 0;
        Map<String, List<Listing>> byItemKey = new HashMap<>();
        int consecutiveApiFailures = 0;
        for (String itemId : commodities.trackedIds()) {
            itemsScanned++;
            ExactListingScanReport report;
            try {
                report = scanExactCommodityListings(itemId, maxPages);
                consecutiveApiFailures = 0;
            } catch (ApiException e) {
                // This book simply cannot be proven this sweep. Leaving it
                // incomplete already locks valuation, so there is nothing to
                // gain by discarding every other commodity as well.
                incompleteItems++;
                if (++consecutiveApiFailures >= MAX_CONSECUTIVE_SCAN_FAILURES) {
                    throw new ApiException("Active-listing scan aborted after "
                            + consecutiveApiFailures
                            + " consecutive API failures: " + e.getMessage());
                }
                continue;
            }
            failures += report.parseFailures();
            if (!report.complete()) {
                incompleteItems++;
                continue;
            }
            completeItems++;
            for (Listing listing : report.listings()) {
                listingsSeen++;
                byItemKey.computeIfAbsent(listing.itemKey(), k -> new ArrayList<>())
                        .add(listing);
            }
        }
        return new ScanReport(itemsScanned, completeItems, incompleteItems,
                listingsSeen, failures, Map.copyOf(byItemKey));
    }

    /**
     * Walks the active-listing result set for one tracked, metadata-plain
     * commodity until a clean empty page proves exhaustion. The caller chooses
     * a finite cap to protect the API budget; hitting that cap on a non-empty
     * page deliberately returns incomplete coverage.
     *
     * <p>The API search is fuzzy, so every parsed row is exact-filtered by
     * namespaced item id and the commodity metadata gate before being archived
     * or returned. A repeated listing key is never silently collapsed: API
     * fallback identities can collide for two genuinely distinct identical
     * auctions, so any duplicate makes absence/multiplicity proof incomplete.
     * A blank listing identity is retained as active supply but counted as a
     * validation failure, preventing unsafe absence proof.</p>
     */
    /** The commodities this service currently scans and can prove coverage for. */
    public Set<String> trackedIds() {
        return commodities.trackedIds();
    }

    /** Prefix of the listing key every market signal carries. */
    public static final String MARKET_SIGNAL_PREFIX = "market:";

    public static boolean isMarketSignal(Opportunity opportunity) {
        return opportunity != null && opportunity.listing() != null
                && opportunity.listing().listingKey() != null
                && opportunity.listing().listingKey().startsWith(MARKET_SIGNAL_PREFIX);
    }

    /**
     * One signal per market and stack size whose completed sales clear the
     * risk rules: the price is the most a live row may cost and still leave
     * the minimum profit and ROI at the median resale, the row to buy is
     * whichever live one the auction screen shows under it.
     *
     * <p>Exists because the API's active listings lag the auction house by
     * minutes and its discounts are ghosts; a session that waited for one
     * to appear never traded. The completed-sale statistics are timely, and
     * a trustworthy market is a signal in its own right.
     *
     * @param maxPurchasePrice a cap on the ceiling, or 0 for none. A market
     *        whose ceiling is set by the cap rather than by the profit rules,
     *        far below what it trades at, is skipped as unaffordable.
     */
    public List<Opportunity> marketSignals(List<MarketStats> markets, long asOf,
                                           long maxPurchasePrice) {
        List<Opportunity> out = new ArrayList<>();
        if (markets == null) return out;
        for (MarketStats stats : markets) {
            if (stats == null || stats.bucket() == StackBucket.OTHER || !stats.hasPrices()) continue;
            // A hashed key used to mean "something we cannot price" - a box of
            // unknown contents, a one-off. For a descriptor market it means the
            // opposite: this is the exact make of the thing, priced against its
            // own history rather than blended with every other make.
            int hash = stats.itemKey().indexOf('#');
            if (hash >= 0 && !commodities.pricedByDescriptor(stats.itemKey().substring(0, hash))) continue;
            if (stats.sampleCount() < risk.minimumSamples()) continue;
            if (!Double.isFinite(stats.confidence()) || stats.confidence() < risk.minimumConfidence()) continue;
            if (!Double.isFinite(stats.robustVolatility())
                    || stats.robustVolatility() > risk.maximumVolatility()) continue;
            if (!Double.isFinite(stats.trend()) || (risk.skipFallingMarkets() && stats.trend() < 0)) continue;
            if (stats.newestSaleAt() <= 0
                    || asOf - stats.newestSaleAt() > risk.maximumNewestSaleAgeMillis()) continue;
            double holdHours = stats.salesPerHour() > 0 ? 1.0 / stats.salesPerHour() : Double.MAX_VALUE;
            if (holdHours > risk.maximumExpectedHoldHours()) continue;

            long sell = (long) Math.floor(stats.weightedMedian());
            if (sell <= 0) continue;
            double net = fees.netSale(sell);
            long economic = Math.min((long) Math.floor(net - risk.minimumProfit()),
                    (long) Math.floor(net / (1.0 + risk.minimumRoiPercent() / 100.0)));
            long ceiling = economic;
            if (maxPurchasePrice > 0 && economic > maxPurchasePrice) {
                if (maxPurchasePrice < sell * 0.6) continue;
                ceiling = maxPurchasePrice;
            }
            if (ceiling <= 0 || ceiling >= sell) continue;

            int count = stats.bucket().exactCount();
            String key = MARKET_SIGNAL_PREFIX + stats.itemKey() + ":" + stats.bucket().label();
            Listing signal = new Listing(key, stats.calculatedAt(), "", "market",
                    stats.itemKey(), stats.itemKey(), count, ceiling, null);
            double profit = net - ceiling;
            double roi = profit / ceiling * 100.0;
            // Rank by what a fill is worth, discounted by how sure and how
            // liquid the market is; sales rate alone put a $600 market ahead
            // of a $10,000 one.
            double score = Math.max(0, profit) * stats.confidence()
                    * Math.log1p(Math.max(0, stats.salesPerHour()));
            out.add(new Opportunity(signal, stats, ceiling, sell, profit, roi, holdHours,
                    Math.min(1.0, stats.confidence()), stats.confidence(), score,
                    List.of("Market signal: any live " + stats.bucket().label()
                            + " row at or under " + ceiling + " resells at the median " + sell)));
        }
        out.sort(Comparator.comparingDouble(Opportunity::score).reversed());
        return out;
    }

    public ExactListingScanReport scanExactCommodityListings(String itemId, int maxPages)
            throws ApiException, InterruptedException, SQLException {
        String normalizedItemId = NamespacedId.normalize(itemId);
        if (!commodities.trackedIds().contains(normalizedItemId)) {
            throw new IllegalArgumentException(
                    "exact listing scan requires a tracked commodity: " + normalizedItemId);
        }
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive");
        }

        Map<String, Listing> byListingKey = new LinkedHashMap<>();
        List<Listing> unkeyed = new ArrayList<>();
        int pagesScanned = 0;
        int failures = 0;
        int ambiguousIdentities = 0;
        boolean exhaustionProven = false;
        boolean pageSizeViolated = false;
        String search = searchTermFor(normalizedItemId);

        for (int page = 1; page <= maxPages; page++) {
            ParseResult<ParsedListing> result = client.fetchListings(page, search);
            pagesScanned++;
            failures += result.failures().size();
            int rowsOnPage = result.records().size() + result.failures().size();

            // An empty page still proves exhaustion when the API sends one.
            if (rowsOnPage == 0) {
                exhaustionProven = true;
                break;
            }
            if (rowsOnPage > LISTING_PAGE_SIZE) {
                // The page is bigger than this scan believes pages can be, so
                // "shorter than a full page" no longer means anything here.
                // Stop rather than guess a new size: continuing could only walk
                // into the out-of-range 500 this whole rule exists to avoid,
                // and exhaustion is already unprovable.
                pageSizeViolated = true;
                break;
            }

            for (ParsedListing parsed : result.records()) {
                if (!parsed.fingerprint().itemId().equals(normalizedItemId)) continue;
                if (!commodities.isEligible(parsed.fingerprint())) continue;

                Listing listing = parsed.listing();
                // CommodityRegistry already proves this for fingerprints, but
                // retain an explicit record-level boundary in case a future
                // parser/model change lets the two identities diverge.
                if (!normalizedItemId.equals(listing.itemId())
                        || !normalizedItemId.equals(listing.itemKey())
                        || !listing.isValid()) {
                    failures++;
                    continue;
                }

                items.upsertSeen(parsed.fingerprint(), true, listing.observedAt());
                listings.insertSnapshot(listing, parsed.rawJson());

                String key = listing.listingKey() == null ? "" : listing.listingKey().strip();
                if (key.isBlank()) {
                    // An anonymous active row is important supply but cannot
                    // participate in identity-based absence reconciliation.
                    unkeyed.add(listing);
                    ambiguousIdentities++;
                } else {
                    Listing prior = byListingKey.get(key);
                    if (prior == null) {
                        byListingKey.put(key, listing);
                    } else {
                        // Even materially identical rows can be two distinct
                        // auctions collapsed by the parser's deterministic
                        // fallback identity. The listings endpoint exposes no
                        // listing id at all, so multiplicity is unknowable.
                        //
                        // This is an identity limit, not a malformed row: the
                        // price and supply are perfectly well understood. It
                        // blocks absence proof, and nothing else.
                        ambiguousIdentities++;
                    }
                }
            }

            // A page that is not full is the last page. This must be decided
            // before requesting the next page: the API answers a page past the
            // end with HTTP 500 ("the specified user/page/item does not
            // exist"), never with an empty page, so waiting for an empty page
            // would guarantee an error on every book shallower than the cap.
            // Stopping here means the out-of-range page is never requested.
            //
            // The comparison cannot use page one's own row count as the size:
            // a commodity with three listings would then look "full" at three
            // and the scan would walk straight into the 500 it is avoiding.
            if (rowsOnPage < LISTING_PAGE_SIZE) {
                exhaustionProven = !pageSizeViolated;
                break;
            }
        }

        List<Listing> exactListings = new ArrayList<>(byListingKey.values());
        exactListings.addAll(unkeyed);
        exactListings.sort(Comparator
                .comparing((Listing listing) -> listing.listingKey() == null
                        ? "" : listing.listingKey().strip())
                .thenComparing(Comparator.comparingLong(Listing::observedAt).reversed())
                .thenComparingLong(Listing::totalPrice)
                .thenComparingInt(Listing::itemCount));

        boolean complete = exhaustionProven && failures == 0;
        String detail;
        if (complete && ambiguousIdentities > 0) {
            detail = "Complete exact active-listing coverage through terminal page "
                    + pagesScanned + "; " + ambiguousIdentities
                    + " indistinguishable row(s) block absence proof only";
        } else if (complete) {
            detail = "Complete exact active-listing coverage through terminal page "
                    + pagesScanned;
        } else if (failures > 0 && !exhaustionProven) {
            detail = "Incomplete exact active-listing coverage: " + failures
                    + " parse/validation failure(s), no clean terminal page";
        } else if (failures > 0) {
            detail = "Incomplete exact active-listing coverage: " + failures
                    + " parse/validation failure(s)";
        } else if (pageSizeViolated) {
            detail = "Incomplete exact active-listing coverage: a page exceeded "
                    + LISTING_PAGE_SIZE + " rows, so page fullness cannot end the book";
        } else {
            detail = "Incomplete exact active-listing coverage: page cap "
                    + maxPages + " reached before a terminal page";
        }
        return new ExactListingScanReport(normalizedItemId, exactListings,
                pagesScanned, failures, ambiguousIdentities,
                exhaustionProven, complete, detail);
    }

    static String searchTermFor(String itemId) {
        String bare = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        return bare.replace('_', ' ');
    }

    /**
     * Recomputes stats for a market and persists them; outlier transactions
     * are flagged in storage, never deleted.
     */
    public MarketAnalyzer.Analysis analyzeMarket(String itemKey, StackBucket bucket, long asOf)
            throws SQLException {
        long since = asOf - analyzerConfig.windowMillis();
        List<Sale> sales = transactions.findByItemKeySince(itemKey, since);
        MarketAnalyzer.Analysis analysis = analyzer.analyze(itemKey, bucket, sales, asOf);
        statsRepo.upsert(analysis.stats());
        if (!analysis.outliers().isEmpty()) {
            Set<String> hashes = new HashSet<>();
            analysis.outliers().forEach(s -> hashes.add(s.transactionHash()));
            transactions.markOutliers(hashes);
        }
        return analysis;
    }

    /**
     * Evaluates every scanned listing against its market and returns accepted
     * opportunities ranked by score (best first).
     */
    /**
     * A listing that was evaluated and turned down, with why.
     *
     * @param blockers every rule it failed, in the order they were checked
     */
    public record NearMiss(String itemKey, StackBucket bucket, long buyPrice,
                           double medianPrice, double salesPerHour,
                           double confidence, List<String> blockers) {
        public NearMiss {
            blockers = List.copyOf(blockers);
        }

        /** How far under the market's median this listing is priced. */
        public double discountPercent() {
            if (!(medianPrice > 0)) return 0;
            return (1.0 - buyPrice / medianPrice) * 100.0;
        }
    }

    /** Accepted opportunities, plus the closest listings that were not. */
    public record DetectionReport(List<Opportunity> opportunities,
                                  List<NearMiss> nearMisses) {
        public DetectionReport {
            opportunities = List.copyOf(opportunities);
            nearMisses = List.copyOf(nearMisses);
        }
    }

    public List<Opportunity> detectOpportunities(Map<String, List<Listing>> commodityListings,
                                                 long asOf,
                                                 OpportunityDetector.Bankroll bankroll)
            throws SQLException {
        return detect(commodityListings, asOf, bankroll).opportunities();
    }

    /**
     * Detection that keeps its rejections.
     *
     * <p>The detector already explains every turn-down precisely — "ROI 4.2%
     * below minimum 12.0%", "Insufficient deployable balance". Discarding that
     * left an empty screen and no way to tell a market with no edge from a
     * threshold set slightly too high, or from capital that was simply too
     * small for the listing.
     *
     * <p>Only the closest listing per market is kept: hundreds of rejections
     * saying the same thing about the same market is noise, and the one that
     * came nearest is the one worth reading.
     */
    public DetectionReport detect(Map<String, List<Listing>> commodityListings,
                                  long asOf,
                                  OpportunityDetector.Bankroll bankroll)
            throws SQLException {
        OpportunityDetector detector = new OpportunityDetector(fees, risk);
        List<Opportunity> found = new ArrayList<>();
        Map<String, NearMiss> closestByMarket = new HashMap<>();
        long since = asOf - analyzerConfig.windowMillis();

        for (Map.Entry<String, List<Listing>> entry : commodityListings.entrySet()) {
            String itemKey = entry.getKey();
            List<Sale> allSales = transactions.findByItemKeySince(itemKey, since);

            Map<StackBucket, List<Listing>> byBucket = new HashMap<>();
            for (Listing l : entry.getValue()) {
                byBucket.computeIfAbsent(l.bucket(), b -> new ArrayList<>()).add(l);
            }

            for (Map.Entry<StackBucket, List<Listing>> bucketEntry : byBucket.entrySet()) {
                StackBucket bucket = bucketEntry.getKey();
                MarketAnalyzer.Analysis analysis = analyzer.analyze(itemKey, bucket, allSales, asOf);
                statsRepo.upsert(analysis.stats());
                MarketStats stats = analysis.stats();
                if (!stats.hasPrices()) continue;

                // Use the analyzer's exact completed-sale cohort. Rebuilding it
                // as "all rows minus outliers" would reintroduce malformed,
                // future, stale, or wrong-stack sales that price statistics
                // deliberately excluded.
                List<Sale> keptSales = analysis.keptSales();

                List<Listing> bucketListings = bucketEntry.getValue();
                bucketListings.sort(Comparator.comparingDouble(Listing::unitPrice));
                for (Listing candidate : bucketListings) {
                    List<Listing> competition = new ArrayList<>(bucketListings);
                    competition.remove(candidate);
                    OpportunityDetector.Evaluation evaluation = detector.evaluate(
                            candidate, stats, keptSales, competition, bankroll);
                    if (evaluation.accepted()) {
                        evaluation.opportunity().ifPresent(found::add);
                        continue;
                    }
                    if (evaluation.rejections().isEmpty()) continue;

                    NearMiss miss = new NearMiss(itemKey, bucket,
                            candidate.totalPrice(), stats.weightedMedian(),
                            stats.salesPerHour(), stats.confidence(),
                            evaluation.rejections());
                    closestByMarket.merge(itemKey + "|" + bucket, miss,
                            (a, b) -> a.blockers().size() <= b.blockers().size() ? a : b);
                }
            }
        }
        found.sort(Comparator.comparingDouble(Opportunity::score).reversed());
        for (Opportunity o : found) {
            opportunities.insert(o, asOf);
        }

        List<NearMiss> misses = new ArrayList<>(closestByMarket.values());
        // Fewest blockers first: those are the ones a small change would clear.
        misses.sort(Comparator.comparingInt((NearMiss m) -> m.blockers().size())
                .thenComparing(NearMiss::itemKey));
        return new DetectionReport(found, misses);
    }

    public DonutApiClient client() {
        return client;
    }

    public TransactionRepository transactions() {
        return transactions;
    }

    /**
     * Stats for every scanned market, including ones with no opportunity, so
     * the market browser can show quiet markets rather than only the hit list.
     */
    /**
     * Stats for every market with completed-sale history, not only the ones
     * whose active book was scanned.
     *
     * <p>Every figure a market row shows — price bands, volume, volatility,
     * trend — is derived from completed sales already in local storage.
     * Active listings only ever served as an index of which rows to display,
     * so restricting the view to scanned commodities hid hundreds of markets
     * for no data reason and at no saving: this costs zero API requests.
     *
     * <p>An active book is still required to *trade* a market, which is why
     * opportunity detection continues to work from the scanned set alone.
     *
     * @param minimumSamples drop markets too thin to say anything about
     */
    public List<MarketStats> marketStatsFromHistory(long asOf, int minimumSamples)
            throws SQLException {
        long since = asOf - analyzerConfig.windowMillis();

        // Rank markets from an index first. Loading every sale in the window
        // to discover which markets exist scaled with total server volume,
        // not with what is displayed: a week of a busy auction house is
        // millions of rows, and holding them all to compute percentiles is an
        // out-of-memory failure rather than a slow one.
        Map<String, Integer> counts = transactions.countByItemKeySince(since);
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));

        List<MarketStats> out = new ArrayList<>();
        int marketsExamined = 0;
        for (Map.Entry<String, Integer> entry : ranked) {
            if (entry.getValue() < minimumSamples) break;
            if (++marketsExamined > MAX_DISPLAYED_MARKETS) break;

            // One market at a time, and its rows are released before the next.
            // Peak memory is therefore one market's cap, not the whole window.
            List<Sale> sales = transactions.findRecentByItemKeySince(
                    entry.getKey(), since, MAX_SALES_PER_MARKET);
            Set<StackBucket> buckets = new HashSet<>();
            for (Sale sale : sales) {
                buckets.add(sale.bucket());
            }
            for (StackBucket bucket : buckets) {
                MarketStats stats =
                        analyzer.analyze(entry.getKey(), bucket, sales, asOf).stats();
                if (stats.hasPrices() && stats.sampleCount() >= minimumSamples) {
                    out.add(stats);
                }
            }
        }
        out.sort(Comparator.comparingDouble(MarketStats::salesPerHour).reversed());
        return out;
    }

    /**
     * The markets worth spending active-listing scans on, chosen from observed
     * history rather than a fixed list.
     *
     * <p>A hardcoded set cannot know which markets this server actually trades
     * cleanly, and the answer moves. Ranking by confidence and liquidity picks
     * the markets where a price is both trustworthy and reachable, which is
     * exactly what an opportunity needs.
     *
     * <p>Eligibility still applies: an item that cannot be fungible is never
     * selected however well it scores, so this widens coverage without
     * loosening what may be traded.
     *
     * @param limit how many markets the request budget can afford to scan
     */
    public List<String> selectTrackedCommodities(long asOf, int limit,
                                                 double minimumConfidence,
                                                 int minimumSamples)
            throws SQLException {
        return selectTrackedCommodities(asOf, limit, minimumConfidence, minimumSamples, java.util.Set.of());
    }

    /**
     * As above, but never picks a market in {@code excluded}. The caller uses
     * this to keep markets the ledger has shown to lose money out of the
     * auto-picked pool; the exclusion is applied before ranking, so a losing
     * market's slot goes to the next market that pays rather than being lost.
     */
    public List<String> selectTrackedCommodities(long asOf, int limit,
                                                 double minimumConfidence,
                                                 int minimumSamples,
                                                 java.util.Set<String> excluded)
            throws SQLException {
        return selectTrackedCommodities(asOf, limit, minimumConfidence, minimumSamples,
                excluded, java.util.Map.of());
    }

    /** Least sales in the window before an hourly shape earns a say in the score. */
    private static final int HOURLY_DEMAND_MIN_SALES = 48;
    /** Bounds on the hourly-demand multiplier: a nudge, never a lever. */
    private static final double HOURLY_DEMAND_MIN = 0.5;
    private static final double HOURLY_DEMAND_MAX = 2.0;

    /**
     * As above, and additionally weights each market's score by how busy the
     * current hour is against that market's own daily average, from
     * {@code hourlyDemand} (item key to a 24-slot UTC histogram). An empty map
     * leaves scoring exactly as it was, so this is inert unless the caller opts
     * in. The weight is clamped so a market that fills now is favoured without
     * letting one freak hour outrank a trustworthy market.
     */
    public List<String> selectTrackedCommodities(long asOf, int limit,
                                                 double minimumConfidence,
                                                 int minimumSamples,
                                                 java.util.Set<String> excluded,
                                                 Map<String, int[]> hourlyDemand)
            throws SQLException {
        if (limit <= 0) return List.of();
        java.util.Set<String> skip = excluded == null ? java.util.Set.of() : excluded;
        Map<String, int[]> demand = hourlyDemand == null ? java.util.Map.of() : hourlyDemand;
        List<MarketStats> candidates = marketStatsFromHistory(asOf, Math.max(1, minimumSamples));

        // Score per item id, not per stack bucket: scanning is per item, and a
        // market whose x1 and x64 books both look good deserves one scan.
        Map<String, Double> bestScore = new HashMap<>();
        for (MarketStats stats : candidates) {
            if (stats.confidence() < minimumConfidence) continue;
            String itemId = stats.itemKey();
            // A market the ledger has shown to lose money is kept out of the
            // pool entirely, no matter how good its snapshot looks.
            if (skip.contains(itemId)) continue;
            // A metadata variant is priced apart from the plain item and can
            // never be a commodity, so it must not win a scan slot.
            if (itemId.indexOf('#') >= 0) continue;
            if (!commodities.assess(ItemFingerprint.builder(itemId).build())
                    .rejectionReasons().stream()
                    .allMatch(r -> r == CommodityRegistry.RejectionReason.NOT_TRACKED)) {
                continue;
            }
            // Liquidity is what turns a good price into a filled trade, but it
            // should not let a volatile market outrank a trustworthy one, so
            // it enters with a diminishing return rather than as a multiplier.
            double score = stats.confidence() * Math.log1p(stats.salesPerHour());
            // Favour the markets that fill at this hour, when the caller asked.
            if (!demand.isEmpty()) {
                score *= dev.doughbay.core.analysis.HourlyDemand.factor(
                        demand.get(itemId), asOf,
                        HOURLY_DEMAND_MIN, HOURLY_DEMAND_MAX, HOURLY_DEMAND_MIN_SALES);
            }
            bestScore.merge(itemId, score, Math::max);
        }

        return bestScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    public List<MarketStats> marketStatsFor(Map<String, List<Listing>> commodityListings, long asOf)
            throws SQLException {
        List<MarketStats> out = new ArrayList<>();
        long since = asOf - analyzerConfig.windowMillis();
        for (Map.Entry<String, List<Listing>> entry : commodityListings.entrySet()) {
            List<Sale> sales = transactions.findByItemKeySince(entry.getKey(), since);
            Set<StackBucket> buckets = new HashSet<>();
            for (Listing l : entry.getValue()) {
                buckets.add(l.bucket());
            }
            for (StackBucket bucket : buckets) {
                MarketStats stats = analyzer.analyze(entry.getKey(), bucket, sales, asOf).stats();
                if (stats.hasPrices()) {
                    out.add(stats);
                }
            }
        }
        out.sort(Comparator.comparingDouble(MarketStats::salesPerHour).reversed());
        return out;
    }

}
