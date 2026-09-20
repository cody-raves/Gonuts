package dev.doughbay.fabric;

import dev.doughbay.core.execution.ExecutionResult;
import dev.doughbay.core.execution.ExecutionStatus;
import dev.doughbay.core.automation.ContinuousAutomationPolicy;
import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.engine.MarketService;
import dev.doughbay.fabric.automation.AutomationSessionController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

/**
 * DoughBay's in-game market dashboard.
 *
 * <p>The screen is deliberately only a view/controller. Market collection and
 * valuation stay in {@link MarketWatcher}; any authorized game interaction is
 * delegated to {@link AutomatedExecutionDriver}. Demo snapshots and the
 * default Observe configuration never expose an active execution control.
 */
public final class DoughBayScreen extends Screen {

    private enum Tab {
        OVERVIEW("Dashboard"),
        OPPORTUNITIES("Opportunities"),
        MARKETS("Markets"),
        SEARCH("Search"),
        ITEMS("Items"),
        STATS("Performance"),
        RIVALS("Rivals"),
        ORDERS("Orders"),
        AUTOMATION("Autopilot"),
        ACTIVITY("Activity"),
        DIAGNOSTICS("Diagnostics"),
        LOGS("Logs"),
        PAYROLL("Payroll"),
        SETTINGS("Settings"),
        ABOUT("About");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    /**
     * Presentation modes for the Automation tab. Selecting a mode never
     * changes a permission or arms an operation; it only reveals the controls
     * that belong to that already-configured execution boundary.
     */
    private enum AutomationMode {
        /** The one trading mode: buy market signals, relist, track the slots. */
        CONTINUOUS("CONTINUOUS"),
        /** Setup and proving tools: the API key card and the test buy/sell. */
        TOOLS("TOOLS"),
        /** The Discord command centre: token, channel, operator. */
        DISCORD("DISCORD");

        final String label;

        AutomationMode(String label) {
            this.label = label;
        }

        AutomationMode next() {
            return this == CONTINUOUS ? TOOLS : this == TOOLS ? DISCORD : CONTINUOUS;
        }
    }

    private static final int GOLD = UiTheme.GOLD;
    private static final int TEXT = UiTheme.TEXT;
    private static final int SECONDARY = UiTheme.SECONDARY;
    private static final int MUTED = UiTheme.MUTED;
    private static final int GOOD = UiTheme.GOOD;
    private static final int WARN = UiTheme.WARN;
    private static final int BAD = UiTheme.BAD;

    private static final int SHADOW = 0x90000000;
    private static final int PANEL = UiTheme.BACKGROUND;
    private static final int PANEL_EDGE = UiTheme.EDGE;
    private static final int PANEL_INNER_EDGE = UiTheme.INSET;
    private static final int CARD = UiTheme.SURFACE;
    private static final int CARD_DARK = UiTheme.INSET;
    private static final int TABLE_HEADER = 0xFF101A27;
    private static final int ROW_EVEN = 0xF2131D2A;
    private static final int ROW_ODD = 0xF2182432;
    private static final int ROW_HOVER = UiTheme.HOVER;
    private static final int TRACK = 0xFF292D39;
    private static final int RANGE = 0xFF315B93;

    /** Matches the limit DoughBayConfig will accept when persisting. */
    private static final int API_KEY_MAX_INPUT = 512;
    private static final int MAX_PANEL_WIDTH = 1_050;
    private static final int MAX_PANEL_HEIGHT = 600;
    private static final int DETAIL_FOOTER_HEIGHT = 35;
    private static final long EXECUTION_FRESHNESS_MILLIS = 120_000L;

    /** Shared with the client tick callback so execution survives screen changes. */
    private static final AutomatedExecutionDriver DRIVER = DoughBayClient.executionDriver();
    private static final AutomationSessionController AUTOMATION_SESSION =
            DoughBayClient.automationSessionController();
    /** Static so a test result survives closing and reopening the screen. */
    private static final ApiKeyTester API_KEY_TESTER = new ApiKeyTester();

    /** Rendered icon width plus the gap before the label. */
    private static final int ICON_SLOT_WIDTH = 20;
    /**
     * Resolved icons, keyed by item id. Registry lookups are cheap but happen
     * for every row of every frame, and an unresolvable id should be looked up
     * once rather than repeatedly. Touched only from the render thread.
     */
    private static final Map<String, ItemStack> ICON_CACHE = new HashMap<>();

    private final MarketWatcher watcher;
    private final PerformanceStatsProvider performanceStatsProvider;
    private final Charts.Pending pendingTooltip = new Charts.Pending();
    private Button actionButton;
    private Button statsModeButton;
    private Button automationModeButton;
    private Button automationChooseButton;
    private Button automationPreflightButton;
    private Button automationStopButton;
    private Button automationSessionStartButton;
    private Button automationSessionResumeButton;
    private Button automationSessionStopButton;
    private Button automationEmergencyButton;
    private Button automationRecoveryPrimaryButton;
    private Button automationRecoverySecondaryButton;
    private EditBox apiKeyField;
    private Button apiKeySaveButton;
    private Button apiKeyTestButton;
    private Button apiKeySourceButton;
    /** True = read through the DoughBay proxy (a DoughBay key); false = the Donut API direct. */
    private boolean apiKeySourceProxy;
    private boolean apiKeySourceInit;
    private EditBox testBuyItemField;
    private EditBox testBuyPriceField;
    private Button testBuyButton;
    private EditBox testSellPriceField;
    private Button testSellButton;
    private String testSellPriceDraft = "";
    private boolean testSellPriceFocused;
    private String testBuyItemDraft = "";
    private String testBuyPriceDraft = "";
    private boolean testBuyItemFocused;
    private boolean testBuyPriceFocused;
    private String testBuyMessage = "";
    private int testBuyMessageColor = SECONDARY;
    // The live-trading setup card: shown on the Autopilot tab while live
    // trading is off, so turning it on is one screen instead of a config file.
    private EditBox setupPerTradeField;
    private EditBox setupPerSessionField;
    private String setupPerTradeDraft;
    private String setupPerSessionDraft;
    private boolean setupPerTradeFocused;
    private boolean setupPerSessionFocused;
    /** Until when a first press of Enable is waiting for its confirming second press. */
    private long setupConfirmUntil;
    private String setupMessage = "";
    private int setupMessageColor = SECONDARY;
    /**
     * The key being typed, held outside the widget so a market-data refresh
     * (which rebuilds every widget) cannot discard it mid-entry. Deliberately
     * not static: closing the screen drops an unsaved key rather than keeping
     * a secret alive in memory for the rest of the session.
     */
    private String apiKeyDraft = "";
    private boolean apiKeyFieldFocused;
    private String apiKeyMessage = "";
    private int apiKeyMessageColor = SECONDARY;
    private String widgetStateKey = "";

    /** Sortable market columns, in the order they appear in the header. */
    private enum MarketSort {
        MARKET, QUICK, MEDIAN, PATIENT, SALES, VOLATILITY, CONFIDENCE
    }

    /** One clickable header label, resolved during render for hit testing. */
    private record HeaderHit(int left, int right, MarketSort sort) {
        boolean contains(double x) {
            return x >= left && x <= right;
        }
    }

    private final List<HeaderHit> marketHeaderHits = new ArrayList<>();
    private int marketHeaderTop;
    private int marketHeaderBottom;
    private MarketSort marketSort = MarketSort.SALES;
    private boolean marketSortDescending = true;

    /** Height reserved above the market table for the filter row. */
    private static final int MARKET_FILTER_HEIGHT = 24;
    /** Below this, a market's own numbers say not to trust it. */
    private static final double LOW_CONFIDENCE = 0.25;
    /** Below this, a fill would take longer than any estimate is worth. */
    private static final double ILLIQUID_SALES_PER_HOUR = 1.0;


    private EditBox marketSearchField;
    private String marketSearch = "";
    private boolean marketSearchFocused;
    private boolean hideLowConfidence;
    private boolean hideIlliquid;
    private boolean hideVariants;

    // --- Search tab: one text box that opens an item or a seller, like the site
    /** One row of the search results list: an item to open, or a seller to open. */
    private record SearchRow(boolean seller, String id, String label, String sub) {
    }

    private EditBox searchField;
    private String searchQuery = "";
    private boolean searchFocused;
    /** Highlighted row in the results list; moved by the arrow keys. */
    private int searchSelected;
    /** The item id whose detail is open, or null for the list. */
    private String searchDetailItem;
    /** The seller whose history is open, or null for the list. */
    private String searchSellerName;
    /** A transient note under the seller view, e.g. after Copy items. */
    private String searchMessage = "";
    /** A seller being fetched to track without opening them; applied when it loads. */
    private MarketQueryClient.SellerView pendingTrackSeller;
    /** The seller window: 24 h, 7 d or 30 d. */
    private long searchWindowMillis = 24L * 3_600_000L;
    private MarketQueryClient.SellerView searchSellerView;
    /** The rows drawn this frame, for click hit-testing. */
    private List<SearchRow> searchRows = List.of();
    /** {rowTop, rowHeight, count} for the results list. */
    private int[] searchRowsGeometry = new int[0];
    /** {rowTop, rowHeight, count} for a seller's sale rows (each opens its item). */
    private int[] searchSaleRowsGeometry = new int[0];

    // --- Items tab: a creative-style grid for editing the include/exclude lists
    /** One registered item: its id, its normalized bare name, and a stack to draw. */
    private record ItemEntry(String id, String bare, String name,
                             net.minecraft.world.item.ItemStack stack, ItemCat cat) {
    }

    /** Creative-menu-style buckets for the Items grid, so it is not one wall of icons. */
    private enum ItemCat {
        ALL("All"), BLOCKS("Blocks"), REDSTONE("Redstone"), COMBAT("Combat"), TOOLS("Tools"),
        ARMOR("Armor"), FOOD("Food"), OTHER("Other");
        final String label;
        ItemCat(String label) { this.label = label; }
    }

    /** Redstone components and the blocks the creative Redstone tab groups with them. */
    private static boolean isRedstone(String b) {
        if (b.contains("redstone")) return true;
        if (b.endsWith("_button") || b.endsWith("_pressure_plate") || b.endsWith("_door")
                || b.endsWith("_trapdoor") || b.endsWith("_fence_gate") || b.endsWith("_rail")
                || b.endsWith("_copper_bulb")) {
            return true;
        }
        return switch (b) {
            case "repeater", "comparator", "piston", "sticky_piston", "observer", "dropper",
                    "dispenser", "hopper", "crafter", "lever", "tripwire_hook", "daylight_detector",
                    "target", "note_block", "tnt", "lightning_rod", "rail", "sculk_sensor",
                    "calibrated_sculk_sensor", "sculk_shrieker", "honey_block", "slime_block",
                    "copper_bulb" -> true;
            default -> false;
        };
    }

    /** Sorts an item into one bucket, using its data components with id fallbacks. */
    private static ItemCat categoryOf(net.minecraft.world.item.Item item, String bare) {
        net.minecraft.core.component.DataComponentMap c = item.components();
        boolean block = item instanceof net.minecraft.world.item.BlockItem;
        // Digging tools first: an axe can also carry a weapon component, but a
        // pickaxe, shovel, hoe or axe belongs in Tools, not Combat.
        if (bare.endsWith("_pickaxe") || bare.endsWith("_axe") || bare.endsWith("_shovel")
                || bare.endsWith("_hoe") || bare.equals("shears") || bare.equals("flint_and_steel")
                || bare.equals("fishing_rod") || bare.equals("brush")) {
            return ItemCat.TOOLS;
        }
        if (bare.endsWith("_sword") || bare.equals("mace") || bare.equals("trident")
                || bare.endsWith("bow") || bare.equals("shield") || bare.endsWith("arrow")
                || item instanceof net.minecraft.world.item.BowItem
                || item instanceof net.minecraft.world.item.CrossbowItem
                || item instanceof net.minecraft.world.item.TridentItem
                || item instanceof net.minecraft.world.item.MaceItem
                || item instanceof net.minecraft.world.item.ShieldItem
                || c.has(net.minecraft.core.component.DataComponents.WEAPON)
                || c.has(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS)) {
            return ItemCat.COMBAT;
        }
        if (!block && c.has(net.minecraft.core.component.DataComponents.EQUIPPABLE)) return ItemCat.ARMOR;
        if (c.has(net.minecraft.core.component.DataComponents.TOOL)
                || bare.endsWith("_pickaxe") || bare.endsWith("_axe") || bare.endsWith("_shovel")
                || bare.endsWith("_hoe") || bare.equals("shears") || bare.equals("flint_and_steel")
                || bare.equals("fishing_rod") || bare.equals("brush")) {
            return ItemCat.TOOLS;
        }
        if (c.has(net.minecraft.core.component.DataComponents.FOOD)) return ItemCat.FOOD;
        if (isRedstone(bare)) return ItemCat.REDSTONE;
        if (block) return ItemCat.BLOCKS;
        return ItemCat.OTHER;
    }

    private enum ItemsFilter { ALL, INCLUDED, EXCLUDED, TRACKED }

    /** Every registered item, built once and reused; the registry does not change in a session. */
    private static List<ItemEntry> ALL_ITEMS;

    private EditBox itemsSearchField;
    private String itemsSearch = "";
    private ItemsFilter itemsFilter = ItemsFilter.ALL;
    private ItemCat itemsCat = ItemCat.ALL;
    private int itemsScrollRow;
    /** Items ticked in the grid; the action row acts on all of them at once. */
    private final java.util.LinkedHashSet<String> itemsSelected = new java.util.LinkedHashSet<>();
    // Grid geometry captured each frame, for click hit-testing.
    private int itemsGridLeft;
    private int itemsGridTop;
    private int itemsCell;
    private int itemsCols;
    private int itemsVisibleRows;
    private List<ItemEntry> itemsFrame = List.of();
    private String itemsHoveredId;
    /** Scroll offset for the Logs tab, in rows from the newest line. */
    private int logsScroll;

    /** Scrollbar track, in screen space; zero width when nothing overflows. */
    private int scrollBarLeft;
    private int scrollBarRight;
    private int scrollBarTop;
    private int scrollBarBottom;
    private boolean draggingScrollBar;

    private Tab tab = Tab.OVERVIEW;
    private Opportunity selected;
    /**
     * A deliberate, sticky Automation-tab candidate. It is only a display
     * reference: every command action rebinds it to the latest immutable
     * watcher snapshot before the driver receives it.
     */
    private Opportunity automationCandidate;
    private AutomationMode automationMode = initialAutomationMode();
    private PerformanceStatsSnapshot.Mode statsMode = PerformanceStatsSnapshot.Mode.PAPER;
    private boolean statsModeInitialized;
    private String driverMessage = "";
    private int scroll;
    /** Rivals tab: the rival whose trades are open, and the row geometry {top, rowHeight, count}. */
    private String selectedRival;
    private int[] rivalRowsGeometry = new int[0];
    private RivalIntel.Rival hoveredRival;
    /** Settings tab: the row being edited, its draft text, scroll, and row hit boxes {top, bottom, index}. */
    private String selectedSetting;
    private String settingDraft = "";
    private EditBox settingField;
    private EditBox settingsSearchField;
    private String settingsSearch = "";
    private boolean settingsSearchFocused;
    private int settingsScroll;
    private int settingsMaxScroll;
    private final List<int[]> settingRowBounds = new ArrayList<>();
    private final List<String> settingRowKeys = new ArrayList<>();
    private String settingsMessage = "";
    private String hoveredSetting;
    /** Payroll tab: the selected rule, the drafts in the bar, and row hit boxes {top, bottom, ruleId}. */
    private long selectedRule;
    private final List<long[]> payrollRowBounds = new ArrayList<>();
    private String payrollName = "";
    private String payrollPercent = "10";
    private String payrollTrigger = "HOURS";
    private String payrollValue = "6";
    private String payrollReserve = "0";
    private String payrollCap = "0";
    private boolean payrollOnline;
    private String payrollMessage = "";
    private int[] payrollFieldX = new int[0];
    private static final int RANK_BLUE = 0xFF4D9BFF;
    /** Settings tab: the key being rebound, waiting for the next key press. */
    private KeyMapping rebindingKey;
    private int maxScroll;

    // Row hit-testing geometry, refreshed with every list render.
    private int listTop;
    private int listBottom;
    private int listRowHeight = 1;
    private int firstVisibleIndex;
    private int visibleRows;
    private int pageScrollPixels;
    private int maxPageScrollPixels;

    public DoughBayScreen(MarketWatcher watcher) {
        this(watcher, PerformanceStatsProvider.previewOnly());
    }

    /**
     * Injection point for an asynchronous, immutable performance-history
     * adapter. The provider must never query SQLite from the render thread.
     */
    public DoughBayScreen(MarketWatcher watcher, PerformanceStatsProvider performanceStatsProvider) {
        super(Component.literal("GoNuts"));
        this.watcher = watcher;
        this.performanceStatsProvider = Objects.requireNonNull(performanceStatsProvider,
                "performanceStatsProvider");
    }

    private static AutomationMode initialAutomationMode() {
        return AutomationMode.CONTINUOUS;
    }

    private MarketWatcher.Snapshot snapshot() {
        return watcher == null ? MarketWatcher.Snapshot.idle("Not initialized") : watcher.snapshot();
    }

    @Override
    protected void init() {
        // The player is at the screen: the session holds off until it closes.
        AUTOMATION_SESSION.setPanelOpen(true);
        // Captured before the reference is dropped: a rebuild triggered by a
        // routine market refresh must not yank the caret out of the key field
        // while it is being typed into.
        apiKeyFieldFocused = apiKeyField != null && apiKeyField.isFocused();
        apiKeyField = null;
        testBuyItemFocused = testBuyItemField != null && testBuyItemField.isFocused();
        testBuyPriceFocused = testBuyPriceField != null && testBuyPriceField.isFocused();
        testSellPriceFocused = testSellPriceField != null && testSellPriceField.isFocused();
        testBuyItemField = null;
        testBuyPriceField = null;
        testBuyButton = null;
        testSellPriceField = null;
        setupPerTradeFocused = setupPerTradeField != null && setupPerTradeField.isFocused();
        setupPerSessionFocused = setupPerSessionField != null && setupPerSessionField.isFocused();
        setupPerTradeField = null;
        setupPerSessionField = null;
        testSellButton = null;
        marketSearchFocused = marketSearchField != null && marketSearchField.isFocused();
        marketSearchField = null;
        apiKeySaveButton = null;
        apiKeyTestButton = null;
        apiKeySourceButton = null;
        actionButton = null;
        statsModeButton = null;
        automationModeButton = null;
        automationChooseButton = null;
        automationPreflightButton = null;
        automationStopButton = null;
        automationSessionStartButton = null;
        automationSessionResumeButton = null;
        automationSessionStopButton = null;
        automationEmergencyButton = null;
        automationRecoveryPrimaryButton = null;
        automationRecoverySecondaryButton = null;
        widgetStateKey = "";
        UiLayout ui = layout();

        int columns = ui.navColumns();
        int gap = 4;
        int buttonWidth = ui.sidebar() > 0 ? ui.sidebar() - 20
                : (ui.innerWidth() - gap * (columns - 1)) / columns;
        int i = 0;
        for (Tab value : Tab.values()) {
            int groupSpace = i >= 10 ? 32 : i >= 5 ? 16 : 0;
            int x = ui.sidebar() > 0 ? ui.left() + 10
                    : ui.innerLeft() + (i % columns) * (buttonWidth + gap);
            int y = ui.sidebar() > 0 ? ui.top() + 64 + i * 21 + groupSpace
                    : ui.tabY() + (i / columns) * 22;
            Button button = CockpitButton.builder(Component.literal(value.label), ignored -> {
                tab = value;
                if (value == Tab.STATS && !statsModeInitialized) {
                    initializeStatsMode(snapshot());
                }
                if (value != Tab.OPPORTUNITIES) selected = null;
                if (value != Tab.RIVALS) selectedRival = null;
                if (value != Tab.SETTINGS) selectedSetting = null;
                if (value != Tab.PAYROLL) selectedRule = 0;
                if (value != Tab.SEARCH) {
                    searchDetailItem = null;
                    searchSellerName = null;
                }
                if (value != Tab.ITEMS) itemsSelected.clear();
                scroll = 0;
                pageScrollPixels = 0;
                driverMessage = "";
                rebuildWidgets();
            }).bounds(x, y, buttonWidth, 20).build();
            ((CockpitButton) button).navigation(value == tab);
            addRenderableWidget(button);
            i++;
        }

        initGlobalSessionButton(ui);
        if (tab == Tab.OVERVIEW) {
            if (ui.innerWidth() >= 420) addRenderableWidget(CockpitButton.builder(Component.literal("Preview HUD"), b -> {
                DoughBayClient.previewHud();
                onClose();
            }).bounds(ui.innerRight() - 184, ui.top() + 30, 98, 18).build());
            addRenderableWidget(CockpitButton.builder(Component.literal("Find opportunities"), b -> navigate(Tab.OPPORTUNITIES))
                    .bounds(ui.innerLeft() + 16, ui.contentTop() + 69, 128, 22).build());
            addRenderableWidget(CockpitButton.builder(Component.literal("Manage autopilot"), b -> navigate(Tab.AUTOMATION))
                    .bounds(ui.innerLeft() + 151, ui.contentTop() + 69, 128, 22).build());
        }

        if (tab == Tab.RIVALS && selectedRival != null) {
            Button back = CockpitButton.builder(Component.literal("Back"), ignored -> {
                selectedRival = null;
                rebuildWidgets();
            }).bounds(ui.innerRight() - 58, ui.contentTop() + 3, 58, 18).build();
            addRenderableWidget(back);
        }

        if (tab == Tab.OPPORTUNITIES && selected != null) {
            Button back = CockpitButton.builder(Component.literal("Back"), ignored -> {
                selected = null;
                scroll = 0;
                pageScrollPixels = 0;
                driverMessage = "";
                rebuildWidgets();
            }).bounds(ui.innerRight() - 58, ui.contentTop() + 3, 58, 18).build();
            addRenderableWidget(back);

            MarketWatcher.Snapshot current = snapshot();
            ExecutionStatus status = DRIVER.inspect();
            boolean armed = status.armed();
            AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
            String label = armed
                    ? "Emergency stop"
                    : session.state() != AutomationSessionController.State.STOPPED
                    || session.unresolvedExposure()
                    ? "View active Automation session"
                    : "Open Automation";

            int actionWidth = Math.min(ui.innerWidth(), Math.max(190, this.font.width(label) + 20));
            actionButton = CockpitButton.builder(Component.literal(label), ignored -> handleDetailAction())
                    .bounds(ui.innerLeft(), ui.bottom() - 27, actionWidth, 20)
                    .build();
            addRenderableWidget(actionButton);
            widgetStateKey = actionStateKey(current);
        } else if (tab == Tab.AUTOMATION) {
            initAutomationWidgets(ui, snapshot());
            widgetStateKey = automationStateKey(snapshot());
        } else if (tab == Tab.STATS) {
            initStatsWidgets(ui);
        } else if (tab == Tab.MARKETS) {
            initMarketFilterWidgets(ui);
        } else if (tab == Tab.SEARCH) {
            initSearchWidgets(ui);
        } else if (tab == Tab.ITEMS) {
            initItemsWidgets(ui);
        } else if (tab == Tab.SETTINGS) {
            initSettingsWidgets(ui);
        } else if (tab == Tab.PAYROLL) {
            initPayrollWidgets(ui);
        }
    }

    /** The session control on every tab: Start, Resume or Stop, on the header's status row, right-aligned. */
    private String globalSessionKey = "";

    private String sessionButtonKey() {
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        return session.state() + "|" + session.recoveredSession() + "|" + AUTOMATION_SESSION.recoverableInHand();
    }

    @Override
    public void removed() {
        // The bot may work again as soon as the player is out of the way.
        AUTOMATION_SESSION.setPanelOpen(false);
        super.removed();
    }

    private void initGlobalSessionButton(UiLayout ui) {
        if (tab == Tab.AUTOMATION) return;   // that tab has the full controls
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        globalSessionKey = sessionButtonKey();
        String label;
        Runnable action;
        boolean active = true;
        switch (session.state()) {
            case STOPPED -> {
                label = "▶ Start";
                action = () -> handleSessionStart(AutomationSessionController.RunMode.CONTINUOUS);
            }
            case PAUSED -> {
                label = "▶ Resume";
                if (session.recoveredSession() || AUTOMATION_SESSION.recoverableInHand()) {
                    action = () -> {
                        ExecutionResult result = AUTOMATION_SESSION.resumeRecoveredSession(DoughBayClient.continuousPolicy());
                        driverMessage = result.detail();
                        rebuildWidgets();
                    };
                    active = DoughBayClient.continuousPolicy() != null;
                } else if (canResumeSession(session)) {
                    action = () -> {
                        ExecutionResult result = AUTOMATION_SESSION.resume();
                        driverMessage = result.detail();
                        rebuildWidgets();
                    };
                } else {
                    action = () -> {
                        tab = Tab.AUTOMATION;
                        rebuildWidgets();
                    };
                    label = "Paused: see Automation";
                }
            }
            default -> {
                label = "■ Stop";
                action = () -> {
                    AUTOMATION_SESSION.stop();
                    driverMessage = "Session stopped";
                    rebuildWidgets();
                };
            }
        }
        int w = Math.max(72, this.font.width(label) + 16);
        Button button = CockpitButton.builder(Component.literal(label), ignored -> action.run())
                .bounds(ui.innerRight() - w, ui.top() + 30, w, 18).build();
        button.active = active;
        addRenderableWidget(button);
    }

    @Override
    public void tick() {
        super.tick();
        if (tab != Tab.AUTOMATION && !sessionButtonKey().equals(globalSessionKey)) {
            rebuildWidgets();
            return;
        }
        if ((selected != null && actionButton != null) || tab == Tab.AUTOMATION) {
            String latestKey = tab == Tab.AUTOMATION
                    ? automationStateKey(snapshot()) : actionStateKey(snapshot());
            if (!latestKey.equals(widgetStateKey)) {
                rebuildWidgets();
            }
        }
    }

    private void handleDetailAction() {
        if (selected == null) return;

        if (DRIVER.inspect().armed()) {
            if (AUTOMATION_SESSION.snapshot().state()
                    != AutomationSessionController.State.STOPPED) {
                AUTOMATION_SESSION.emergencyStop();
                driverMessage = AUTOMATION_SESSION.snapshot().detail();
            } else {
                DRIVER.emergencyStop();
                driverMessage = DRIVER.inspect().description();
            }
            rebuildWidgets();
            return;
        }

        MarketWatcher.Snapshot current = snapshot();
        Opportunity currentSelection = currentOpportunity(current, selected);
        automationCandidate = currentSelection == null ? selected : currentSelection;
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        automationMode = AutomationMode.CONTINUOUS;
        tab = Tab.AUTOMATION;
        selected = null;
        scroll = 0;
        pageScrollPixels = 0;
        driverMessage = currentSelection == null
                ? "That detail listing expired; the session chooses the best fresh signal itself"
                : "The continuous session chooses the best fresh signal at Start; this row is not pinned";
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        MarketWatcher.Snapshot current = snapshot();
        UiLayout ui = layout();
        pendingTooltip.clear();

        graphics.fill(0, 0, this.width, this.height, 0x66050910);
        UiTheme.panel(graphics, ui.left(), ui.top(), ui.width(), ui.height(), PANEL);
        if (ui.sidebar() > 0) renderSidebar(graphics, ui);
        renderHeader(graphics, current, ui);

        switch (tab) {
            case OVERVIEW -> renderOverview(graphics, current, ui, mouseX, mouseY);
            case ACTIVITY -> renderActivity(graphics, ui);
            case OPPORTUNITIES -> {
                if (selected == null) {
                    renderOpportunities(graphics, current, ui, mouseX, mouseY);
                } else {
                    renderDetail(graphics, current, selected, ui, mouseX, mouseY);
                }
            }
            case MARKETS -> renderMarkets(graphics, current, ui, mouseX, mouseY);
            case SEARCH -> renderSearch(graphics, ui, mouseX, mouseY);
            case ITEMS -> renderItems(graphics, ui, mouseX, mouseY);
            case STATS -> renderStats(graphics, current, ui, mouseX, mouseY);
            case RIVALS -> renderRivals(graphics, ui, mouseX, mouseY);
            case SETTINGS -> renderSettings(graphics, ui, mouseX, mouseY);
            case PAYROLL -> renderPayroll(graphics, ui, mouseX, mouseY);
            case ORDERS -> renderOrders(graphics, current, ui, mouseX, mouseY);
            case AUTOMATION -> renderAutomation(graphics, current, ui);
            case DIAGNOSTICS -> renderDiagnostics(graphics, current, ui);
            case LOGS -> renderLogs(graphics, ui);
            case ABOUT -> renderAbout(graphics, current, ui);
        }

        // Widgets are extracted after the cards they sit on. Gui render states
        // preserve submission order, so this keeps embedded Automation buttons
        // (and all other controls) visibly above opaque card fills.
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        Charts.drawTooltip(graphics, this.font, pendingTooltip,
                ui.left(), ui.top(), ui.right(), ui.bottom());
    }

    private void renderHeader(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot, UiLayout ui) {
        int x = ui.innerLeft();
        int top = ui.top();
        if (ui.sidebar() == 0) {
            Mascot.drawLogo(graphics, x, top + 7, 18);
            graphics.text(this.font, tab.label, x + 60, top + 12, TEXT);
        } else {
            scaledText(graphics, tab.label, x, top + 11, 1.35f, TEXT);
        }
        String age = snapshot.updatedAt() == 0 ? "Waiting for feed"
                : "Updated " + relativeAge(System.currentTimeMillis() - snapshot.updatedAt());
        if (ui.innerWidth() > 360) {
            graphics.text(this.font, age, ui.innerRight() - this.font.width(age), top + 12, MUTED);
        }
        int statusColor = snapshot.demo() ? WARN : snapshot.status().startsWith("STOPPED") ? BAD
                : snapshot.updatedAt() == 0 || !isFresh(snapshot) ? WARN : GOOD;
        String status = snapshot.demo() ? "DEMO / invented sample data"
                : snapshot.status().startsWith("STOPPED") ? "FEED STOPPED"
                : snapshot.updatedAt() == 0 ? "CONNECTING TO MARKET"
                : !isFresh(snapshot) ? "STALE / waiting for scan" : "LIVE MARKET FEED";
        int statusRoom = Math.max(30, ui.innerWidth() - 158);
        graphics.fill(x, top + 34, x + 4, top + 38, statusColor);
        graphics.text(this.font, truncate(status, statusRoom), x + 9, top + 32, statusColor);
        if (ui.sidebar() > 0) renderAccount(graphics, snapshot, ui, top + 53);
        graphics.fill(x, ui.contentTop() - 4, ui.innerRight(), ui.contentTop() - 3, PANEL_EDGE);
    }

    private void renderSidebar(GuiGraphicsExtractor graphics, UiLayout ui) {
        int x = ui.left();
        graphics.fill(x + 1, ui.top() + 1, x + ui.sidebar(), ui.bottom() - 1, UiTheme.INSET);
        graphics.fill(x + ui.sidebar(), ui.top() + 1, x + ui.sidebar() + 1, ui.bottom() - 1, PANEL_EDGE);
        Mascot.drawLogo(graphics, x + 13, ui.top() + 12, 24);
        graphics.text(this.font, "TRADE", x + 14, ui.top() + 53, MUTED);
        graphics.text(this.font, "OPERATIONS", x + 14, ui.top() + 173, MUTED);
        graphics.text(this.font, "WORKSPACE", x + 14, ui.top() + 294, MUTED);
        graphics.text(this.font, "Esc  /  Back to game", x + 12, ui.bottom() - 15, MUTED);
    }

    /**
     * The signed-in player: face, name, and balance, right-aligned under the
     * refresh age.
     *
     * <p>Balance is what sizes a trade, so it belongs where it is glanceable
     * rather than buried in a tab. An unknown balance says so — a player the
     * API has never seen answers HTTP 500, which is an ordinary outcome and
     * must not be shown as zero coins.
     */
    private void renderAccount(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot,
                               UiLayout ui, int y) {
        String name = snapshot.accountName();
        if (name.isEmpty()) return;

        long balance = snapshot.accountBalance();
        String money = balance < 0 ? "balance unknown" : money(balance);
        int moneyWidth = this.font.width(money);
        int nameWidth = this.font.width(name);
        // The rank beside the name: "+", "++" or "+++" in blue when the API
        // reports a plus rank, otherwise the rank text as the API gives it.
        // The API says "Unknown" for some ranks; the slot count is the rank
        // in practice, so it fills the badge in when the API does not.
        String rank = DoughBayClient.rank();
        String plus = rankBadge(rank);
        if (plus.isEmpty()) {
            int slots = SlotTracker.slots();
            plus = slots >= 90 ? "++" : slots >= 45 ? "+" : "";
        }
        String badge = plus;
        int badgeColor = RANK_BLUE;
        int badgeWidth = badge.isEmpty() ? 0 : this.font.width(badge) + 2;
        int gap = 6;
        int faceSize = 8;

        int right = ui.innerRight();
        int moneyX = right - moneyWidth;
        int nameX = moneyX - gap - nameWidth - badgeWidth;
        int faceX = nameX - gap - faceSize;
        if (faceX < ui.innerLeft() + 200) return;   // no room; the header wins

        drawPlayerFace(graphics, faceX, y, faceSize);
        graphics.text(this.font, name, nameX, y, SECONDARY);
        if (!badge.isEmpty()) graphics.text(this.font, badge, nameX + nameWidth + 2, y, badgeColor);
        graphics.text(this.font, money, moneyX, y, balance < 0 ? MUTED : GOOD);
    }

    /**
     * Draws the local player's head from their loaded skin.
     *
     * <p>Silently skipped when there is no player entity — the screen opens
     * fine at the title screen and before a world loads, and a missing face is
     * not worth a gap in the header, let alone an exception in the renderer.
     */
    private void drawPlayerFace(GuiGraphicsExtractor graphics, int x, int y, int size) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return;
            PlayerFaceExtractor.extractRenderState(graphics, player.getSkin(), x, y, size);
        } catch (Throwable ignored) {
            // Skin state can be absent or mid-load; the header must still draw.
        }
    }

    // ------------------------------------------------------------ opportunities

    private record SignalHit(int x, int y, int width, int height, Opportunity opportunity) { }
    private final List<SignalHit> overviewSignals = new ArrayList<>();

    private void navigate(Tab destination) {
        tab = destination;
        selected = null;
        scroll = 0;
        pageScrollPixels = 0;
        rebuildWidgets();
    }

    private void renderOverview(GuiGraphicsExtractor g, MarketWatcher.Snapshot snapshot,
                                UiLayout ui, int mouseX, int mouseY) {
        resetListGeometry();
        overviewSignals.clear();
        int x = ui.innerLeft(), w = ui.innerWidth(), top = ui.contentTop() + 6;
        var stats = performanceStats(snapshot, PerformanceStatsSnapshot.Mode.REAL);
        var session = AUTOMATION_SESSION.snapshot();
        boolean spacious = w >= 530;
        UiTheme.panel(g, x, top, w, 96, 0xFF172B35);
        g.text(this.font, snapshot.demo() ? "YOUR DEMO TRADING DESK" : "YOUR TRADING DESK", x + 16, top + 13, UiTheme.TEAL);
        scaledText(g, "A little less watching.", x + 16, top + 30, spacious ? 1.6f : 1.2f, TEXT);
        g.text(this.font, "A clearer view of every trade.", x + 16, top + 51, SECONDARY);
        if (w >= 420) {
            Mascot.draw(g, Mascot.faceFor(session, DoughBayClient.evasionGuard()), x + w - 118, top + 3, 96);
        }
        int viewportTop = top + 106;
        int columns = w >= 530 ? 4 : 2;
        int metricRows = 4 / columns;
        int metricWidth = (w - (columns - 1) * 8) / columns;
        int metricsHeight = metricRows * 61;
        int below = viewportTop + metricsHeight + 3;
        int contentHeight = metricsHeight + (spacious ? 237 : 478);
        maxPageScrollPixels = Math.max(0, viewportTop + contentHeight - (ui.bottom() - 12));
        pageScrollPixels = clamp(pageScrollPixels, 0, maxPageScrollPixels);
        g.enableScissor(x - 1, viewportTop, ui.innerRight() + 1, ui.bottom() - 10);
        int shift = pageScrollPixels;
        String[] labels = {"REALIZED PROFIT", "ACTIVE LISTINGS", "OPPORTUNITIES", "WIN RATE"};
        String[] values = {signedMoney(stats.realizedProfit()), String.valueOf(stats.openTrades()),
                String.valueOf(snapshot.opportunities().size()), stats.completedSales() == 0 ? "--" : percent(stats.winRatePercent())};
        String[] notes = {"Closed sales, after fees",
                money(stats.openCostBasis()) + " at cost", snapshot.markets().size() + " markets watched",
                stats.completedSales() + " completed sales"};
        for (int i = 0; i < 4; i++) {
            int mx = x + (i % columns) * (metricWidth + 8);
            int my = viewportTop + (i / columns) * 61 - shift;
            overviewMetric(g, mx, my, metricWidth, labels[i], values[i], notes[i], i == 0 ? GOOD : i == 2 ? GOLD : TEXT);
        }
        int leftWidth = spacious ? (w * 56) / 100 : w;
        int rightX = spacious ? x + leftWidth + 10 : x;
        int rightWidth = spacious ? w - leftWidth - 10 : w;
        int chartY = below - shift;
        UiTheme.panel(g, x, chartY, leftWidth, 137, CARD);
        g.text(this.font, "Profit over time", x + 13, chartY + 12, TEXT);
        g.text(this.font, "REALIZED / ALL TIME", x + 13, chartY + 28, MUTED);
        var points = stats.profitHistory().stream().map(p -> new Charts.Point(p.atMillis(), p.cumulativeProfit())).toList();
        if (points.size() >= 2) {
            for (int row = 0; row < 3; row++) g.fill(x + 13, chartY + 51 + row * 30, x + leftWidth - 13, chartY + 52 + row * 30, PANEL_EDGE);
            Charts.sparkline(g, x + 13, chartY + 45, leftWidth - 26, 64, points, Charts.SERIES_BLUE);
            g.text(this.font, "First recorded trade", x + 13, chartY + 118, MUTED);
            g.text(this.font, "Now", x + leftWidth - 32, chartY + 118, MUTED);
        } else drawWrapped(g, "Your realized profit history will appear after completed sales are recorded.",
                x + 13, chartY + 58, leftWidth - 26, chartY + 123, MUTED);
        int activityY = chartY + 147;
        UiTheme.panel(g, x, activityY, leftWidth, 83, CARD);
        g.text(this.font, "Latest activity", x + 13, activityY + 12, TEXT);
        var activity = DoughBayClient.recentActivity();
        if (activity.isEmpty()) {
            g.text(this.font, "All quiet for now", x + 13, activityY + 34, SECONDARY);
            g.text(this.font, "Sales, orders and alerts will show here.", x + 13, activityY + 51, MUTED);
        } else {
            var event = activity.getFirst();
            g.text(this.font, truncate(event.title(), leftWidth - 26), x + 13, activityY + 32, event.type().accent);
            drawWrapped(g, event.lineOne(), x + 13, activityY + 48, leftWidth - 26, activityY + 75, SECONDARY);
        }
        int signalsY = spacious ? chartY : activityY + 94;
        UiTheme.panel(g, rightX, signalsY, rightWidth, 230, CARD_DARK);
        g.text(this.font, "On the radar", rightX + 13, signalsY + 12, TEXT);
        g.text(this.font, "Top opportunities to review", rightX + 13, signalsY + 27, MUTED);
        var opportunities = snapshot.opportunities();
        if (opportunities.isEmpty()) drawWrapped(g, "No signals pass the filters yet. Your copilot is watching for the next opening.",
                rightX + 13, signalsY + 60, rightWidth - 26, signalsY + 170, SECONDARY);
        for (int i = 0; i < Math.min(3, opportunities.size()); i++) {
            var opportunity = opportunities.get(i);
            int sy = signalsY + 48 + i * 58;
            UiTheme.rounded(g, rightX + 8, sy, rightWidth - 16, 51, 5, CARD);
            drawItemIcon(g, opportunity.listing().itemId(), rightX + 15, sy + 7, 20);
            g.text(this.font, truncate(titleCase(shortName(opportunity.listing().itemId())), rightWidth - 57), rightX + 39, sy + 10, TEXT);
            g.text(this.font, signedMoney(Math.round(opportunity.expectedNetProfit())) + " est. profit", rightX + 15, sy + 31, GOOD);
            overviewSignals.add(new SignalHit(rightX + 8, sy, rightWidth - 16, 51, opportunity));
        }
        g.disableScissor();
        if (maxPageScrollPixels > 0) g.text(this.font, "Scroll to explore", ui.innerRight() - 90, ui.bottom() - 9, MUTED);
    }

    private void overviewMetric(GuiGraphicsExtractor g, int x, int y, int w,
                                String label, String value, String note, int color) {
        UiTheme.panel(g, x, y, w, 53, CARD);
        g.text(this.font, truncate(label, w - 20), x + 10, y + 8, MUTED);
        scaledText(g, truncate(value, (int) ((w - 20) / 1.35)), x + 10, y + 22, 1.35f, color);
        g.text(this.font, truncate(note, w - 20), x + 10, y + 40, SECONDARY);
    }

    private void renderActivity(GuiGraphicsExtractor g, UiLayout ui) {
        resetListGeometry();
        int x = ui.innerLeft(), w = ui.innerWidth(), top = ui.contentTop() + 9;
        g.text(this.font, "Every event, in one place", x, top, TEXT);
        g.text(this.font, "Recent alerts stay here after their popups disappear.", x, top + 16, MUTED);
        var events = DoughBayClient.recentActivity();
        int totalHeight = 0;
        for (var e : events) totalHeight += 35 + this.font.split(Component.literal(e.lineOne() + "  " + e.lineTwo() + "  " + e.lineThree()), w - 40).size() * 12;
        int listY = top + 38;
        maxPageScrollPixels = Math.max(0, listY + totalHeight - ui.bottom() + 16);
        pageScrollPixels = clamp(pageScrollPixels, 0, maxPageScrollPixels);
        if (events.isEmpty()) {
            renderEmptyState(g, x, listY, w, "No events yet", "Your next sale, order, warning or error will appear here.",
                    "This history covers the current game session.");
            return;
        }
        g.enableScissor(x, listY, ui.innerRight(), ui.bottom() - 10);
        int y = listY - pageScrollPixels;
        for (var e : events) {
            var lines = this.font.split(Component.literal(e.lineOne() + "  " + e.lineTwo() + "  " + e.lineThree()), w - 40);
            int h = 28 + lines.size() * 12;
            UiTheme.panel(g, x, y, w, h, CARD);
            g.fill(x + 10, y + 11, x + 14, y + 15, e.type().accent);
            g.text(this.font, e.title(), x + 22, y + 9, e.type().accent);
            String age = relativeAge(System.currentTimeMillis() - e.createdAt());
            g.text(this.font, age, x + w - 13 - this.font.width(age), y + 9, MUTED);
            int ly = y + 25;
            for (var line : lines) { g.text(this.font, line, x + 20, ly, SECONDARY, false); ly += 12; }
            y += h + 7;
        }
        g.disableScissor();
    }

    private void renderOpportunities(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot,
                                     UiLayout ui, int mouseX, int mouseY) {
        List<Opportunity> rows = snapshot.opportunities();
        int x = ui.innerLeft(), w = ui.innerWidth(), top = ui.contentTop() + 8;
        graphics.text(this.font, "A shortlist worth a closer look", x, top, TEXT);
        graphics.text(this.font, rows.size() + " signals / ranked by the engine", x, top + 15, MUTED);
        if (rows.isEmpty()) {
            renderEmptyState(graphics, x, top + 38, w, "Nothing clears your filters yet",
                    "Your copilot is waiting for a worthwhile opportunity.",
                    "Open Markets to review coverage and pricing confidence.");
            resetListGeometry();
            return;
        }
        listRowHeight = w >= 500 ? 76 : 88;
        listTop = top + 35;
        int bottom = ui.bottom() - 27;
        visibleRows = Math.max(1, (bottom - listTop) / listRowHeight);
        maxScroll = Math.max(0, rows.size() - visibleRows);
        scroll = Math.min(scroll, maxScroll);
        firstVisibleIndex = scroll;
        listBottom = listTop + Math.min(visibleRows, rows.size() - scroll) * listRowHeight;
        graphics.enableScissor(x, listTop, ui.innerRight(), bottom);
        for (int i = scroll; i < Math.min(rows.size(), scroll + visibleRows); i++) {
            int y = listTop + (i - scroll) * listRowHeight;
            opportunityCard(graphics, rows.get(i), snapshot, x, y, w, listRowHeight - 7,
                    mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + listRowHeight - 7);
        }
        graphics.disableScissor();
        drawScrollBar(graphics, ui.innerRight(), listTop, bottom, rows.size());
        graphics.text(this.font, "Select a signal for price history and the full breakdown", x, ui.bottom() - 17, MUTED);
    }

    private void opportunityCard(GuiGraphicsExtractor g, Opportunity o, MarketWatcher.Snapshot snapshot,
                                 int x, int y, int w, int h, boolean hover) {
        UiTheme.panel(g, x, y, w, h, hover ? ROW_HOVER : CARD);
        UiTheme.rounded(g, x + 10, y + 10, 29, 29, 5, CARD_DARK);
        drawItemIcon(g, o.listing().itemId(), x + 16, y + 10, 29);
        String name = titleCase(shortName(o.listing().itemId())) + " x" + o.listing().itemCount();
        g.text(this.font, truncate(name, Math.max(60, w - 158)), x + 48, y + 12, TEXT);
        g.text(this.font, percent(o.confidence() * 100) + " confidence  /  " + holdTime(o.estimatedHoldHours()) + " est. hold",
                x + 48, y + 27, MUTED);
        String gain = signedMoney(Math.round(o.expectedNetProfit()));
        g.text(this.font, gain, x + w - 13 - this.font.width(gain), y + 12, GOOD);
        String roi = percent(o.expectedRoiPercent()) + " ROI";
        g.text(this.font, roi, x + w - 13 - this.font.width(roi), y + 27, UiTheme.TEAL);
        int col = (w - 28) / 3;
        g.text(this.font, "BUY  " + money(o.buyPrice()), x + 14, y + h - 17, SECONDARY);
        g.text(this.font, "TARGET  " + money(o.recommendedSellPrice()), x + 14 + col, y + h - 17, SECONDARY);
        if (w >= 500) Charts.sparkline(g, x + 14 + col * 2, y + h - 23, col - 12, 17,
                snapshot.historyFor(o.listing().itemKey()), Charts.SERIES_BLUE);
        else g.text(this.font, "View details >", x + 14 + col * 2, y + h - 17, GOLD);
    }

    // ------------------------------------------------------------------- detail

    private void renderDetail(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot,
                              Opportunity opportunity, UiLayout ui, int mouseX, int mouseY) {
        Opportunity refreshed = currentOpportunity(snapshot, opportunity);
        if (refreshed != null) opportunity = refreshed;
        int titleY = ui.contentTop() + 7;
        int titleRoom = ui.innerWidth() - 74;
        String title = titleCase(shortName(opportunity.listing().itemId()))
                + "  x" + opportunity.listing().itemCount();
        graphics.text(this.font, truncate(title.toUpperCase(Locale.ROOT), titleRoom),
                ui.innerLeft(), titleY, GOLD);

        int footerTop = ui.bottom() - DETAIL_FOOTER_HEIGHT;
        int bodyTop = ui.contentTop() + 28;
        int bodyBottom = footerTop - 7;
        if (ui.innerWidth() < 680) {
            renderCompactDetail(graphics, snapshot, opportunity, ui,
                    bodyTop, bodyBottom, mouseX, mouseY);
            renderDetailFooter(graphics, snapshot, opportunity, ui, footerTop);
            return;
        }
        pageScrollPixels = 0;
        maxPageScrollPixels = 0;

        int totalHeight = Math.max(250, bodyBottom - bodyTop);
        int gap = 8;
        int bandHeight = 38;
        // Give the completed-sale charts enough vertical room to read their
        // axes at GUI scale 2.  The lower cards are mostly short facts, so a
        // little more of the available height is more useful in the history
        // panel than as empty space below those facts.
        int topHeight = Math.min(210, Math.max(158, (int) (totalHeight * 0.52)));
        int lowerHeight = totalHeight - topHeight - bandHeight - gap * 2;
        if (lowerHeight < 104) {
            topHeight -= 104 - lowerHeight;
            lowerHeight = 104;
        }

        int innerWidth = ui.innerWidth();
        int chartWidth = Math.max(330, (int) (innerWidth * 0.54));
        int summaryWidth = innerWidth - chartWidth - gap;
        int summaryGap = 7;
        int listingWidth = (summaryWidth - summaryGap) / 2;
        int marketWidth = summaryWidth - listingWidth - summaryGap;

        int listingX = ui.innerLeft();
        int marketX = listingX + listingWidth + summaryGap;
        int chartX = ui.innerRight() - chartWidth;

        renderListingCard(graphics, snapshot, opportunity,
                listingX, bodyTop, listingWidth, topHeight);
        renderMarketCard(graphics, opportunity.stats(),
                marketX, bodyTop, marketWidth, topHeight);
        renderHistoryCard(graphics, snapshot, opportunity,
                chartX, bodyTop, chartWidth, topHeight, mouseX, mouseY);

        int bandY = bodyTop + topHeight + gap;
        renderPriceBand(graphics, opportunity, ui.innerLeft(), bandY, innerWidth, bandHeight);

        int lowerY = bandY + bandHeight + gap;
        int lowerGap = 8;
        int outcomeWidth = (int) (innerWidth * 0.26);
        int executionWidth = (int) (innerWidth * 0.30);
        int reasonWidth = innerWidth - outcomeWidth - executionWidth - lowerGap * 2;
        int outcomeX = ui.innerLeft();
        int executionX = outcomeX + outcomeWidth + lowerGap;
        int reasonX = executionX + executionWidth + lowerGap;

        renderOutcomeCard(graphics, opportunity, outcomeX, lowerY, outcomeWidth, lowerHeight);
        renderExecutionCard(graphics, snapshot, opportunity,
                executionX, lowerY, executionWidth, lowerHeight);
        renderReasonsCard(graphics, opportunity, reasonX, lowerY, reasonWidth, lowerHeight);
        renderDetailFooter(graphics, snapshot, opportunity, ui, footerTop);
    }

    /**
     * Narrow screens use a clipped, vertically scrollable analysis column.
     * Nothing is silently dropped at a large GUI scale: the chart, outcome,
     * safety state, and reasons remain reachable with the mouse wheel.
     */
    private void renderCompactDetail(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot,
                                     Opportunity opportunity, UiLayout ui,
                                     int top, int bottom, int mouseX, int mouseY) {
        int gap = 7;
        int width = ui.innerWidth();
        int half = (width - gap) / 2;
        int factsHeight = 124;
        int chartHeight = 168;
        int bandHeight = 38;
        int decisionHeight = 102;
        int reasonsHeight = 116;
        int virtualHeight = factsHeight + chartHeight + bandHeight
                + decisionHeight + reasonsHeight + gap * 4;
        int viewportHeight = Math.max(1, bottom - top);
        maxPageScrollPixels = Math.max(0, virtualHeight - viewportHeight);
        pageScrollPixels = clamp(pageScrollPixels, 0, maxPageScrollPixels);

        int yy = top - pageScrollPixels;
        graphics.enableScissor(ui.innerLeft(), top, ui.innerRight(), bottom);
        try {
            renderListingCard(graphics, snapshot, opportunity,
                    ui.innerLeft(), yy, half, factsHeight);
            renderMarketCard(graphics, opportunity.stats(),
                    ui.innerLeft() + half + gap, yy, width - half - gap, factsHeight);
            yy += factsHeight + gap;

            int chartMouseX = mouseY >= top && mouseY < bottom ? mouseX : Integer.MIN_VALUE;
            int chartMouseY = mouseY >= top && mouseY < bottom ? mouseY : Integer.MIN_VALUE;
            renderHistoryCard(graphics, snapshot, opportunity,
                    ui.innerLeft(), yy, width, chartHeight, chartMouseX, chartMouseY);
            yy += chartHeight + gap;

            renderPriceBand(graphics, opportunity,
                    ui.innerLeft(), yy, width, bandHeight);
            yy += bandHeight + gap;

            renderOutcomeCard(graphics, opportunity,
                    ui.innerLeft(), yy, half, decisionHeight);
            renderExecutionCard(graphics, snapshot, opportunity,
                    ui.innerLeft() + half + gap, yy,
                    width - half - gap, decisionHeight);
            yy += decisionHeight + gap;

            renderReasonsCard(graphics, opportunity,
                    ui.innerLeft(), yy, width, reasonsHeight);
        } finally {
            graphics.disableScissor();
        }

        if (maxPageScrollPixels > 0) {
            int trackX = ui.innerRight() - 3;
            int thumbHeight = Math.max(13,
                    (int) ((double) viewportHeight / virtualHeight * viewportHeight));
            int thumbTravel = Math.max(1, viewportHeight - thumbHeight);
            int thumbY = top + (int) ((double) pageScrollPixels
                    / maxPageScrollPixels * thumbTravel);
            graphics.fill(trackX, top, trackX + 2, bottom, TRACK);
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, GOLD);
        }
    }

    private void renderListingCard(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot,
                                   Opportunity opportunity, int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        boolean signal = dev.doughbay.engine.MarketService.isMarketSignal(opportunity);
        sectionTitle(graphics, signal ? "MARKET SIGNAL" : "THIS LISTING", x, y, width);
        int yy = y + 23;
        int innerX = x + 8;
        int innerWidth = width - 16;
        if (signal) {
            yy = kvRight(graphics, innerX, yy, innerWidth,
                    "Buy up to", money(opportunity.buyPrice()), GOOD);
            yy = kvRight(graphics, innerX, yy, innerWidth,
                    "Per unit", money(opportunity.buyPrice()
                            / (double) Math.max(1, opportunity.listing().itemCount())), SECONDARY);
            yy = kvRight(graphics, innerX, yy, innerWidth,
                    "Resell at", money(opportunity.recommendedSellPrice()), TEXT);
            yy = kvRight(graphics, innerX, yy, innerWidth,
                    "Seller", "any live row on the screen", SECONDARY);
            yy = kvRight(graphics, innerX, yy, innerWidth,
                    "Stats age", relativeAge(Math.max(0,
                            System.currentTimeMillis() - opportunity.stats().calculatedAt())), SECONDARY);
            String note = AUTOMATION_SESSION.huntNote(opportunity.listing().itemId());
            drawWrapped(graphics, note.isBlank() ? "Not hunted yet this session" : "Last hunt: " + note,
                    innerX, yy + 2, innerWidth, y + height - 6,
                    note.startsWith("bought") ? GOOD : SECONDARY);
            return;
        }
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Current", money(opportunity.buyPrice()), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Per unit", money(opportunity.buyPrice()
                        / (double) Math.max(1, opportunity.listing().itemCount())), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Seller", opportunity.listing().sellerName(), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Observed", relativeAge(Math.max(0,
                        System.currentTimeMillis() - opportunity.listing().observedAt())), SECONDARY);
        kvRight(graphics, innerX, yy, innerWidth,
                "Time left", opportunity.listing().timeLeftMillis() == null
                        ? "unknown" : duration(opportunity.listing().timeLeftMillis()), SECONDARY);
    }

    private void renderMarketCard(GuiGraphicsExtractor graphics, MarketStats stats,
                                  int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "MARKET", x, y, width);
        int yy = y + 23;
        int innerX = x + 8;
        int innerWidth = width - 16;
        if (stats == null) {
            graphics.text(this.font, truncate("No completed-sale statistics", innerWidth),
                    innerX, yy, MUTED);
            return;
        }
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Median", money(stats.weightedMedian()), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Quick sale", money(stats.quickSalePrice()), GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Patient", money(stats.patientSalePrice()), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Samples", stats.sampleCount() + " / " + stats.uniqueSellers() + " sellers", SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Sales/hour", String.format(Locale.ROOT, "%.1f", stats.salesPerHour()), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Volatility", String.format(Locale.ROOT, "%.1f%%", stats.robustVolatility() * 100), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Trend", String.format(Locale.ROOT, "%+.1f%%", stats.trend() * 100),
                stats.trend() < -0.05 ? BAD : stats.trend() > 0.03 ? GOOD : SECONDARY);
        kvRight(graphics, innerX, yy, innerWidth,
                "Confidence", percent(stats.confidence() * 100), confidenceColor(stats.confidence()));
    }

    private void renderHistoryCard(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot,
                                   Opportunity opportunity, int x, int y, int width, int height,
                                   int mouseX, int mouseY) {
        card(graphics, x, y, width, height, CARD_DARK);
        graphics.text(this.font, "UNIT PRICE — LAST 12H", x + 8, y + 7, GOLD);

        List<Charts.Point> history = snapshot.historyFor(opportunity.listing().itemKey());
        MarketWatcher.VolumeWindow volume =
                snapshot.volumeFor(opportunity.listing().itemKey());
        // A rehearsal or test-buy candidate is built from a bare listing and
        // carries no completed-sale statistics; draw it priced at itself.
        MarketStats stats = opportunity.stats() != null
                ? opportunity.stats()
                : TestBuyPicker.placeholderStats(opportunity.listing(), System.currentTimeMillis());
        int count = Math.max(1, opportunity.listing().itemCount());
        int chartY = y + 20;
        // The price plot gets the majority of the card.  In the wide scale-2
        // layout this is roughly the height of the original visual mock-up;
        // compact layouts still retain a useful histogram below it.
        int lineHeight = clamp((int) Math.round(height * 0.52), 72, 110);
        long windowEnd = volume.windowEnd() > 0
                ? volume.windowEnd()
                : snapshot.updatedAt() > 0 ? snapshot.updatedAt() : System.currentTimeMillis();
        long windowSpan = volume.windowSpan();
        int nextY = Charts.line(graphics, this.font, x + 7, chartY, width - 14, lineHeight,
                history, Charts.SERIES_BLUE,
                new double[]{stats.quickSalePrice() / count, stats.weightedMedian() / count},
                new int[]{Charts.SERIES_AQUA, Charts.SERIES_ORANGE},
                new String[]{"quick", "median"},
                opportunity.buyPrice() / (double) count, GOOD,
                windowEnd, windowSpan, mouseX, mouseY, pendingTooltip);

        int cardBottom = y + height;
        if (nextY + 10 < cardBottom - 20) {
            drawLegend(graphics, x + 9, nextY);
            int barsY = nextY + 11;
            int barsHeight = cardBottom - barsY - 7;
            if (barsHeight >= 18) {
                Charts.bars(graphics, this.font, x + 7, barsY, width - 14, barsHeight,
                        volume.copyCounts(),
                        Charts.SERIES_BLUE, "sales / 30 min",
                        mouseX, mouseY, pendingTooltip, windowSpan);
            }
        }
    }

    private void drawLegend(GuiGraphicsExtractor graphics, int x, int y) {
        int cursor = x;
        cursor = legendItem(graphics, cursor, y, Charts.SERIES_BLUE, "sales");
        cursor = legendItem(graphics, cursor + 8, y, Charts.SERIES_AQUA, "quick");
        cursor = legendItem(graphics, cursor + 8, y, Charts.SERIES_ORANGE, "median");
        legendItem(graphics, cursor + 8, y, GOOD, "listing");
    }

    private int legendItem(GuiGraphicsExtractor graphics, int x, int y, int color, String label) {
        graphics.fill(x, y + 4, x + 7, y + 5, color);
        graphics.text(this.font, label, x + 10, y, MUTED);
        return x + 10 + this.font.width(label);
    }

    private void renderPriceBand(GuiGraphicsExtractor graphics, Opportunity opportunity,
                                 int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD_DARK);
        // A rehearsal or test-buy candidate is built from a bare listing and
        // carries no completed-sale statistics; draw it priced at itself.
        MarketStats stats = opportunity.stats() != null
                ? opportunity.stats()
                : TestBuyPicker.placeholderStats(opportunity.listing(), System.currentTimeMillis());
        double lo = Math.min(opportunity.buyPrice(), stats.lowerBound());
        double hi = Math.max(Math.max(stats.upperBound(), stats.patientSalePrice()),
                opportunity.recommendedSellPrice());
        if (!Double.isFinite(lo) || !Double.isFinite(hi) || hi <= lo) {
            graphics.text(this.font, "Price band unavailable", x + 8, y + 12, MUTED);
            return;
        }

        String buy = "buy " + money(opportunity.buyPrice());
        String quick = "quick " + money(stats.quickSalePrice());
        String median = "median " + money(stats.weightedMedian());
        String patient = "patient " + money(stats.patientSalePrice());
        graphics.text(this.font, buy, x + 8, y + 6, GOOD);

        int patientXLabel = x + width - 8 - this.font.width(patient);
        int medianXLabel = patientXLabel - 12 - this.font.width(median);
        int quickXLabel = medianXLabel - 12 - this.font.width(quick);
        if (quickXLabel > x + 8 + this.font.width(buy) + 8) {
            graphics.text(this.font, quick, quickXLabel, y + 6, Charts.SERIES_AQUA);
            graphics.text(this.font, median, medianXLabel, y + 6, Charts.SERIES_ORANGE);
            graphics.text(this.font, patient, patientXLabel, y + 6, Charts.SERIES_BLUE);
        } else {
            String rangeText = quick + "  •  " + median;
            int rangeRoom = Math.max(20, width - 24 - this.font.width(buy));
            String fitted = this.font.plainSubstrByWidth(rangeText, rangeRoom, true);
            graphics.text(this.font, fitted,
                    x + width - 8 - this.font.width(fitted), y + 6, SECONDARY);
        }

        int trackX = x + 9;
        int trackRight = x + width - 9;
        int trackY = y + height - 11;
        graphics.fill(trackX, trackY, trackRight, trackY + 5, TRACK);

        int quickX = priceX(stats.quickSalePrice(), lo, hi, trackX, trackRight);
        int patientX = priceX(stats.patientSalePrice(), lo, hi, trackX, trackRight);
        graphics.fill(Math.min(quickX, patientX), trackY,
                Math.max(quickX + 1, patientX), trackY + 5, RANGE);

        int buyX = priceX(opportunity.buyPrice(), lo, hi, trackX, trackRight);
        int medianX = priceX(stats.weightedMedian(), lo, hi, trackX, trackRight);
        graphics.fill(buyX, trackY - 3, Math.min(trackRight, buyX + 2), trackY + 8, GOOD);
        graphics.fill(medianX, trackY - 3, Math.min(trackRight, medianX + 2), trackY + 8,
                Charts.SERIES_ORANGE);
    }

    private void renderOutcomeCard(GuiGraphicsExtractor graphics, Opportunity opportunity,
                                   int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "EXPECTED OUTCOME", x, y, width);
        int yy = y + 23;
        int innerX = x + 8;
        int innerWidth = width - 16;
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Resale target", money(opportunity.recommendedSellPrice()), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Net profit", money(opportunity.expectedNetProfit()),
                opportunity.expectedNetProfit() > 0 ? GOOD : BAD);
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "ROI", percent(opportunity.expectedRoiPercent()), GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth,
                "Est. hold", holdTime(opportunity.estimatedHoldHours()), TEXT);
        kvRight(graphics, innerX, yy, innerWidth,
                "Sale chance", percent(opportunity.saleProbability() * 100), GOOD);
    }

    private void renderExecutionCard(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot,
                                     Opportunity opportunity, int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        String heading = snapshot.demo() ? "PREVIEW SAFETY"
                : DRIVER.inspect().armed() ? "LIVE VERIFICATION" : "SINGLE TRADE ENTRY";
        sectionTitle(graphics, heading, x, y, width);

        int yy = y + 23;
        int maxY = y + height - 7;
        int textX = x + 8;
        int textWidth = width - 16;

        if (snapshot.demo()) {
            yy = statusLine(graphics, textX, yy, textWidth, "!  Demo feed only", WARN);
            yy = statusLine(graphics, textX, yy, textWidth, "•  No API requests", SECONDARY);
            yy = statusLine(graphics, textX, yy, textWidth, "•  No server actions", SECONDARY);
            statusLine(graphics, textX, yy, textWidth, "×  No direct purchase path", WARN);
            return;
        }

        if (!DRIVER.authorizedExecutionEnabled()) {
            yy = statusLine(graphics, textX, yy, textWidth, "✓  Observe mode enabled", GOOD);
            yy = statusLine(graphics, textX, yy, textWidth, "×  Commands and clicks disabled", SECONDARY);
            drawWrapped(graphics,
                    "Open Automation to review the gates. The continuous session selects the best fresh signal itself; this detail screen never sends a purchase command.",
                    textX, yy + 2, textWidth, maxY, MUTED);
            return;
        }

        Opportunity armed = DRIVER.armedOpportunity();
        if (armed != null) {
            for (AutomatedExecutionDriver.Check check : DRIVER.verifyAgainstOpenScreen()) {
                if (yy > maxY - 9) break;
                int color = switch (check.status()) {
                    case MATCH -> GOOD;
                    case MISMATCH -> BAD;
                    case UNKNOWN -> MUTED;
                };
                String mark = switch (check.status()) {
                    case MATCH -> "✓ ";
                    case MISMATCH -> "× ";
                    case UNKNOWN -> "• ";
                };
                yy = statusLine(graphics, textX, yy, textWidth,
                        mark + check.label() + ": " + check.observed(), color);
            }
            return;
        }

        String blocker = executionBlocker(snapshot, opportunity);
        if (blocker != null) {
            drawWrapped(graphics, blocker, textX, yy, textWidth, maxY, WARN);
            return;
        }

        yy = statusLine(graphics, textX, yy, textWidth, "•  Exact item + stack", MUTED);
        yy = statusLine(graphics, textX, yy, textWidth, "•  Seller + live price", MUTED);
        yy = statusLine(graphics, textX, yy, textWidth, "•  Controller state + one-position gate", MUTED);
        yy = statusLine(graphics, textX, yy, textWidth,
                "Open Automation — the session selects the best fresh signal", GOOD);
        statusLine(graphics, textX, yy, textWidth, "No command is sent from this detail screen", SECONDARY);
    }

    private void renderReasonsCard(GuiGraphicsExtractor graphics, Opportunity opportunity,
                                   int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "WHY THIS WAS FLAGGED", x, y, width);
        int yy = y + 23;
        int maxY = y + height - 7;
        for (String reason : opportunity.reasons()) {
            if (yy > maxY - 9) break;
            String lower = reason.toLowerCase(Locale.ROOT);
            int color = reason.startsWith("DEMO") || lower.contains("stale")
                    || lower.contains("thin") || lower.contains("elevated")
                    ? WARN : SECONDARY;
            yy = drawWrapped(graphics, "•  " + reason,
                    x + 8, yy, width - 16, maxY, color);
        }
    }

    private void renderDetailFooter(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot,
                                    Opportunity opportunity, UiLayout ui, int footerTop) {
        graphics.fill(ui.innerLeft(), footerTop, ui.innerRight(), footerTop + 1, PANEL_EDGE);

        String statusText;
        int color;
        ExecutionStatus status = DRIVER.inspect();
        if (status.armed() || status.state() == ExecutionStatus.State.ABORTED) {
            statusText = status.description();
            color = status.state() == ExecutionStatus.State.ABORTED ? BAD : WARN;
        } else if (!driverMessage.isBlank()) {
            statusText = driverMessage;
            color = WARN;
        } else {
            String blocker = executionBlocker(snapshot, opportunity);
            statusText = blocker == null
                    ? "No direct purchase here • the continuous session selects the best fresh signal"
                    : blocker + " • Open Automation to review the locked gate";
            color = blocker == null ? SECONDARY : MUTED;
        }

        int actionWidth = 0;
        for (var child : children()) {
            if (child instanceof Button button && button.getY() >= ui.bottom() - 28) {
                actionWidth = button.getWidth() + 12;
                break;
            }
        }
        int textX = Math.min(ui.innerRight() - 10, ui.innerLeft() + actionWidth);
        int room = ui.innerRight() - textX;
        if (room >= this.font.width("status")) {
            graphics.text(this.font, truncate(statusText, room), textX, ui.bottom() - 21, color);
        }
    }

    // ------------------------------------------------------------------ markets

    private void renderMarkets(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot,
                               UiLayout ui, int mouseX, int mouseY) {
        List<MarketStats> rows = sortedMarkets(filteredMarkets(snapshot.markets()));
        int totalMarkets = snapshot.markets().size();
        int tableX = ui.innerLeft();
        int tableRight = ui.innerRight();
        int tableWidth = tableRight - tableX;
        // The filter row is a widget and draws itself; the table starts below.
        int headerY = ui.contentTop() + 8 + MARKET_FILTER_HEIGHT;
        int headerHeight = 22;
        int footerY = ui.bottom() - 26;

        if (rows.isEmpty()) {
            boolean filtered = totalMarkets > 0;
            renderEmptyState(graphics, tableX, headerY, tableWidth,
                    filtered ? "NO MARKETS MATCH THE FILTER" : "NO MARKETS ANALYZED",
                    filtered
                            ? totalMarkets + " markets are loaded but none match the search or toggles."
                            : "Markets appear after enough valid completed sales have been collected.",
                    filtered
                            ? "Clear the search box or switch a toggle off."
                            : "Use Diagnostics to check API and collector health.");
            resetListGeometry();
            return;
        }

        boolean wide = ui.innerWidth() >= 680;
        listRowHeight = wide ? 25 : 18;
        int rowsTop = headerY + headerHeight;
        int rowsViewportBottom = footerY - 7;
        visibleRows = Math.max(1, (rowsViewportBottom - rowsTop) / listRowHeight);
        maxScroll = Math.max(0, rows.size() - visibleRows);
        scroll = Math.min(scroll, maxScroll);
        firstVisibleIndex = scroll;

        graphics.fill(tableX, headerY, tableRight, headerY + headerHeight, TABLE_HEADER);
        int headerTextY = centeredTextY(headerY, headerHeight);
        marketHeaderHits.clear();
        marketHeaderTop = headerY;
        marketHeaderBottom = headerY + headerHeight;
        if (wide) {
            drawSortableHeader(graphics, tableX, headerTextY, wideColumns(tableWidth),
                    new String[]{"MARKET", "12H TREND", "QUICK", "MEDIAN",
                            "PATIENT", "SALES/H", "VOL", "CONF"},
                    new MarketSort[]{MarketSort.MARKET, null, MarketSort.QUICK,
                            MarketSort.MEDIAN, MarketSort.PATIENT, MarketSort.SALES,
                            MarketSort.VOLATILITY, MarketSort.CONFIDENCE});
        } else {
            drawSortableHeader(graphics, tableX, headerTextY, compactColumns(tableWidth),
                    new String[]{"MARKET", "QUICK", "MEDIAN", "SALES/H", "VOL", "CONF"},
                    new MarketSort[]{MarketSort.MARKET, MarketSort.QUICK,
                            MarketSort.MEDIAN, MarketSort.SALES,
                            MarketSort.VOLATILITY, MarketSort.CONFIDENCE});
        }

        graphics.enableScissor(tableX, rowsTop, tableRight, rowsViewportBottom);
        try {
            int end = Math.min(rows.size(), firstVisibleIndex + visibleRows);
            for (int index = firstVisibleIndex; index < end; index++) {
                MarketStats stats = rows.get(index);
                int rowY = rowsTop + (index - firstVisibleIndex) * listRowHeight;
                boolean hovered = mouseX >= tableX && mouseX < tableRight
                        && mouseY >= rowY && mouseY < rowY + listRowHeight;
                graphics.fill(tableX, rowY, tableRight, rowY + listRowHeight,
                        hovered ? ROW_HOVER : (index % 2 == 0 ? ROW_EVEN : ROW_ODD));
                int textY = centeredTextY(rowY, listRowHeight);

                if (wide) {
                    int[] cols = wideColumns(tableWidth);
                    int sparkWidth = Math.min(72, Math.max(48, (int) (tableWidth * 0.10)));
                    int sparkRight = tableX + cols[1];
                    String market = marketName(stats);
                    int icon = drawItemIcon(graphics, stats.itemKey(),
                            tableX + cols[0], rowY, listRowHeight);
                    int room = Math.max(24,
                            sparkRight - sparkWidth - 18 - (tableX + cols[0] + icon));
                    cell(graphics, this.font.plainSubstrByWidth(market, room),
                            tableX, cols[0] + icon, textY, TEXT, false);
                    Charts.sparkline(graphics, sparkRight - sparkWidth, rowY + 4,
                            sparkWidth, Math.max(9, listRowHeight - 8),
                            snapshot.historyFor(stats.itemKey()), Charts.SERIES_BLUE);
                    cell(graphics, money(stats.quickSalePrice()), tableX, cols[2], textY, TEXT, true);
                    cell(graphics, money(stats.weightedMedian()), tableX, cols[3], textY, TEXT, true);
                    cell(graphics, money(stats.patientSalePrice()), tableX, cols[4], textY, SECONDARY, true);
                    cell(graphics, String.format(Locale.ROOT, "%.1f", stats.salesPerHour()),
                            tableX, cols[5], textY, stats.salesPerHour() >= 6 ? GOOD : SECONDARY, true);
                    cell(graphics, String.format(Locale.ROOT, "%.1f%%", stats.robustVolatility() * 100),
                            tableX, cols[6], textY, stats.robustVolatility() > 0.15 ? WARN : SECONDARY, true);
                    cell(graphics, percent(stats.confidence() * 100), tableX, cols[7], textY,
                            confidenceColor(stats.confidence()), true);
                } else {
                    int[] cols = compactColumns(tableWidth);
                    String market = marketName(stats);
                    int icon = drawItemIcon(graphics, stats.itemKey(),
                            tableX + cols[0], rowY, listRowHeight);
                    cell(graphics, this.font.plainSubstrByWidth(market,
                                    Math.max(30, cols[1] - cols[0] - 12 - icon)),
                            tableX, cols[0] + icon, textY, TEXT, false);
                    cell(graphics, money(stats.quickSalePrice()), tableX, cols[1], textY, TEXT, true);
                    cell(graphics, money(stats.weightedMedian()), tableX, cols[2], textY, TEXT, true);
                    cell(graphics, String.format(Locale.ROOT, "%.1f", stats.salesPerHour()),
                            tableX, cols[3], textY, GOOD, true);
                    cell(graphics, String.format(Locale.ROOT, "%.1f%%", stats.robustVolatility() * 100),
                            tableX, cols[4], textY, SECONDARY, true);
                    cell(graphics, percent(stats.confidence() * 100), tableX, cols[5], textY,
                            confidenceColor(stats.confidence()), true);
                }
            }
        } finally {
            graphics.disableScissor();
        }

        int paintedRows = Math.min(visibleRows, rows.size() - firstVisibleIndex);
        int tableBottom = rowsTop + Math.max(0, paintedRows) * listRowHeight;
        graphics.outline(tableX, headerY, tableWidth,
                headerHeight + Math.max(0, paintedRows) * listRowHeight, PANEL_EDGE);
        listTop = rowsTop;
        listBottom = tableBottom;
        drawScrollBar(graphics, tableRight, rowsTop, rowsViewportBottom, rows.size());

        String footer = rows.size()
                + (rows.size() == totalMarkets ? " markets" : " of " + totalMarkets + " markets")
                + "  •  sorted by " + sortLabel(marketSort)
                + (marketSortDescending ? " (high to low)" : " (low to high)")
                + (maxScroll > 0 ? "  •  drag the bar or scroll" : "")
                + "  •  click a column to re-sort";
        graphics.text(this.font, truncate(footer, tableWidth), tableX + 2, footerY, MUTED);
    }

    // -------------------------------------------------------------------- stats

    private void initializeStatsMode(MarketWatcher.Snapshot marketSnapshot) {
        // One ledger, one page: the real one.
        statsMode = PerformanceStatsSnapshot.Mode.REAL;
        statsModeInitialized = true;
    }

    private void initStatsWidgets(UiLayout ui) {
        // The Stats page has no controls; everything is on one screen.
    }

    private PerformanceStatsSnapshot performanceStats(MarketWatcher.Snapshot marketSnapshot,
                                                       PerformanceStatsSnapshot.Mode mode) {
        try {
            PerformanceStatsSnapshot result = performanceStatsProvider.snapshot(
                    mode, marketSnapshot.demo(), System.currentTimeMillis());
            if (result == null) {
                return PerformanceStatsSnapshot.empty(mode,
                        "Performance snapshot is unavailable");
            }
            if (result.mode() != mode) {
                return PerformanceStatsSnapshot.empty(mode,
                        "Provider returned the wrong ledger; stats are locked");
            }
            return result;
        } catch (RuntimeException failure) {
            return PerformanceStatsSnapshot.empty(mode,
                    "Performance snapshot could not be loaded");
        }
    }

    private void renderStats(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot marketSnapshot,
                             UiLayout ui, int mouseX, int mouseY) {
        resetListGeometry();
        statsMode = PerformanceStatsSnapshot.Mode.REAL;
        PerformanceStatsSnapshot stats = performanceStats(marketSnapshot, statsMode);
        int x = ui.innerLeft();
        int width = ui.innerWidth();
        int right = ui.innerRight();
        int top = ui.contentTop() + 7;
        long now = System.currentTimeMillis();

        List<AutomationSessionController.OpenListingView> open = AUTOMATION_SESSION.openListingViews();
        int other = AUTOMATION_SESSION.otherListedSlots();
        long moneyOut = 0;
        long projected = 0;
        for (var listing : open) {
            moneyOut += listing.purchasePrice();
            projected += listing.targetPrice() - listing.purchasePrice();
        }

        // Rates over the last hour from the sale-by-sale history.
        long hourAgo = now - 3_600_000L;
        int salesLastHour = 0;
        long profitAtHourStart = 0;
        long profitNow = 0;
        boolean sawEarlier = false;
        for (var point : stats.profitHistory()) {
            if (point.atMillis() < hourAgo) {
                profitAtHourStart = point.cumulativeProfit();
                sawEarlier = true;
            } else {
                salesLastHour++;
            }
            profitNow = point.cumulativeProfit();
        }
        long profitLastHour = profitNow - (sawEarlier ? profitAtHourStart : 0);

        graphics.text(this.font, "STATS  ·  real ledger, this database", x, top, GOLD);
        String stamp = stats.hasHistory() ? stats.status() : stats.status();
        graphics.text(this.font, truncate(stamp, width / 2), right - this.font.width(truncate(stamp, width / 2)),
                top, MUTED);

        int cardsTop = top + 16;
        int gap = 6;
        int columns = width >= 700 ? 4 : 2;
        int cardWidth = (width - gap * (columns - 1)) / columns;
        int cardHeight = 44;
        String[] labels = {"BUYS", "SALES", "SPENT", "EARNED",
                "PROFIT", "MONEY OUT", "PROJECTED ON LISTED", "LAST HOUR"};
        String[] values = {grouped(stats.purchasedTrades()), grouped(stats.completedSales()),
                money(stats.totalInvested()), money(stats.settledCapital() + stats.realizedProfit()),
                signedMoney(stats.realizedProfit()),
                money(moneyOut), signedMoney(projected),
                signedMoney(profitLastHour)};
        String[] notes = {"purchases booked", "closed sales", "lifetime purchase cost",
                "sale proceeds", "closed sales after fees",
                open.size() + " listing(s) at cost", "if every listing sells at target",
                salesLastHour + " sale(s)  ·  " + String.format(Locale.ROOT, "%.1f", salesLastHour / 60.0)
                        + "/min  ·  " + money(profitLastHour / 60) + "/min"};
        int[] colors = {TEXT, TEXT, TEXT, TEXT,
                stats.realizedProfit() >= 0 ? GOOD : BAD, TEXT, projected >= 0 ? GOOD : BAD,
                profitLastHour >= 0 ? GOOD : BAD};
        int rows = (labels.length + columns - 1) / columns;
        for (int i = 0; i < labels.length; i++) {
            int col = i % columns;
            int row = i / columns;
            headlineCard(graphics, x + col * (cardWidth + gap), cardsTop + row * (cardHeight + gap),
                    cardWidth, cardHeight, labels[i], values[i], notes[i], colors[i]);
        }
        int mainTop = cardsTop + rows * (cardHeight + gap) + 4;
        int contentBottom = ui.bottom() - 10;
        boolean wide = width >= 700;

        // Left: the 45 auction slots, hover for the item. Right: top markets.
        int slotsWidth = wide ? (width * 46) / 100 : width;
        int slotsHeight = wide ? Math.max(120, contentBottom - mainTop) : 132;
        lastSlotBoard = new int[] {x, mainTop, slotsWidth};
        renderSlotBoard(graphics, x, mainTop, slotsWidth, slotsHeight, open, other, now, mouseX, mouseY);
        int marketsX = wide ? x + slotsWidth + 8 : x;
        int marketsTop = wide ? mainTop : mainTop + slotsHeight + 8;
        int marketsWidth = wide ? right - marketsX : width;
        int marketsHeight = Math.max(80, contentBottom - marketsTop);
        renderTopMarkets(graphics, stats, marketsX, marketsTop, marketsWidth, marketsHeight);
        renderSlotTooltip(graphics, open, other, now, mouseX, mouseY);
    }


    // ------------------------------------------------------------------
    // Rivals tab: who else is flipping, when they are on, what they trade.
    // ------------------------------------------------------------------

    private void renderRivals(GuiGraphicsExtractor graphics, UiLayout ui, int mouseX, int mouseY) {
        resetListGeometry();
        rivalRowsGeometry = new int[0];
        RivalIntel intel = DoughBayClient.rivalIntel();
        RivalIntel.Snapshot s = intel == null
                ? RivalIntel.Snapshot.empty("Rival intel is not running") : intel.snapshot();
        int x = ui.innerLeft();
        int width = ui.innerWidth();
        int right = ui.innerRight();
        int top = ui.contentTop() + 7;
        long now = System.currentTimeMillis();
        int contentBottom = ui.bottom() - 10;

        graphics.text(this.font, "RIVALS  ·  flippers seen in the sales feed, last 24 h", x, top, GOLD);
        String stamp = s.refreshedAt() > 0 ? "updated " + relativeAge(now - s.refreshedAt()) : s.status();
        if (selectedRival == null) {
            graphics.text(this.font, truncate(stamp, width / 2),
                    right - this.font.width(truncate(stamp, width / 2)), top, MUTED);
        }

        if (selectedRival != null) {
            RivalIntel.Rival chosen = null;
            for (RivalIntel.Rival r : s.rivals()) if (r.name().equals(selectedRival)) chosen = r;
            if (chosen != null) {
                renderRivalDetail(graphics, chosen, x, top + 16, width, contentBottom, now);
                return;
            }
            selectedRival = null;
        }

        int cardsTop = top + 16;
        int gap = 6;
        int columns = width >= 700 ? 5 : 2;
        int cardWidth = (width - gap * (columns - 1)) / columns;
        int cardHeight = 44;
        int repeat = 0;
        for (RivalIntel.Buyer b : s.buyers()) if (b.purchases() >= 2) repeat++;
        String[] labels = {"AUTO-TRADERS (EST.)", "RIVALS ACTIVE", "CROWDED MARKETS",
                "RIVAL-PROVEN MARKETS", "REPEAT BUYERS"};
        String[] values = {s.traders() > 0 ? String.format(Locale.ROOT, "%.0f%%", s.botSharePercent()) : "n/a",
                s.activeNow() + " of " + s.rivals().size(), grouped(s.crowded().size()),
                grouped(s.proven().size()), grouped(repeat)};
        String[] notes = {s.likelyBots() + " of " + s.traders() + " sellers with 20+ sales look automated",
                s.quietField() ? "field is quiet: bot looks faster, wider small pool"
                : "sold or listed in the last 10 min",
                "a rival is working these right now", "3+ reconstructed rival flips today",
                "bought from you more than once"};
        int[] colors = {TEXT, s.quietField() ? GOOD : TEXT, s.crowded().isEmpty() ? TEXT : WARN, TEXT, TEXT};
        for (int i = 0; i < labels.length; i++) {
            int col = i % columns;
            int row = i / columns;
            headlineCard(graphics, x + col * (cardWidth + gap), cardsTop + row * (cardHeight + gap),
                    cardWidth, cardHeight, labels[i], values[i], notes[i], colors[i]);
        }
        int rows = (labels.length + columns - 1) / columns;
        int mainTop = cardsTop + rows * (cardHeight + gap) + 4;
        boolean wide = width >= 700;
        int tableWidth = wide ? (width * 66) / 100 : width;
        int tableHeight = wide ? Math.max(120, contentBottom - mainTop)
                : Math.max(120, (contentBottom - mainTop) * 6 / 10);
        renderRivalTable(graphics, s, x, mainTop, tableWidth, tableHeight, now, mouseX, mouseY);
        int buyersX = wide ? x + tableWidth + 8 : x;
        int buyersTop = wide ? mainTop : mainTop + tableHeight + 8;
        int buyersWidth = wide ? right - buyersX : width;
        int buyersHeight = Math.max(60, contentBottom - buyersTop);
        int underdogHeight = Math.max(60, buyersHeight / 2 - 4);
        renderUnderdog(graphics, s, buyersX, buyersTop, buyersWidth, underdogHeight);
        renderBuyers(graphics, s, buyersX, buyersTop + underdogHeight + 8, buyersWidth,
                Math.max(40, buyersHeight - underdogHeight - 8), now);
        if (hoveredRival != null) renderRivalHoverCard(graphics, hoveredRival, mouseX, mouseY, ui);
    }

    /** The shadowed markets: what the rival pays, what they get, and where Underdog would list. */
    private void renderUnderdog(GuiGraphicsExtractor graphics, RivalIntel.Snapshot s, int x, int y,
                                int width, int height) {
        card(graphics, x, y, width, height, CARD);
        boolean on = Tuning.get("underdog.enabled") >= 0.5;
        sectionTitle(graphics, "UNDERDOG  ·  " + (on ? "on" : "off, enable in Settings")
                + "  ·  " + s.shadows().size() + " shadowed market(s)", x, y, width);
        int innerX = x + 9;
        int innerWidth = width - 18;
        int yy = y + 27;
        if (s.shadows().isEmpty()) {
            drawWrapped(graphics, "No rival market has enough reconstructed flips and margin yet.",
                    innerX, yy, innerWidth, y + height - 8, MUTED);
            return;
        }
        double undercut = Tuning.get("underdog.undercut_pct") / 100.0;
        for (RivalIntel.ShadowMarket m : s.shadows()) {
            if (yy + 22 > y + height - 8) break;
            long list = (long) Math.floor(m.medianSale() * (1.0 - undercut));
            yy = kvRight(graphics, innerX, yy, innerWidth, titleCase(shortName(m.itemId())) + " x" + m.count(),
                    money(m.medianBuy()) + " → " + money(list)
                            + String.format(Locale.ROOT, "  (%.0f%%)", m.marginPercent()), on ? GOOD : SECONDARY);
            yy = statusLine(graphics, innerX + 12, yy, innerWidth - 12,
                    m.rival() + "  ·  " + m.flips() + " flip(s)  ·  they sell at " + money(m.medianSale())
                            + (m.medianTurnaroundMillis() > 0
                            ? "  ·  " + relativeAge(m.medianTurnaroundMillis()).replace(" ago", "") + " turn" : ""),
                    MUTED);
        }
    }

    /** The at-a-glance card for a rival under the mouse: bot likelihood, volume, markets, profit. */
    private void renderRivalHoverCard(GuiGraphicsExtractor graphics, RivalIntel.Rival r, int mouseX, int mouseY,
                                      UiLayout ui) {
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        lines.add(r.name());
        colors.add(GOLD);
        lines.add("Bot likelihood " + r.botScore() + "%  ·  active " + r.activeHours() + " of 24 hours");
        colors.add(r.botScore() >= 60 ? WARN : SECONDARY);
        lines.add(grouped(r.sales()) + " sales/day  ·  avg ticket " + money(r.averageTicket())
                + "  ·  " + money(r.revenue()) + " revenue");
        colors.add(TEXT);
        lines.add("Est. profit/day " + money(r.estimatedProfit())
                + (r.profitFromFlips() ? String.format(Locale.ROOT, "  (%.0f%% margin from %d flips)",
                r.averageMarginPercent(), r.flips().size()) : "  (assumed 20% margin)"));
        colors.add(GOOD);
        StringBuilder markets = new StringBuilder();
        for (String item : r.topItems()) {
            if (markets.length() > 0) markets.append(", ");
            markets.append(titleCase(shortName(item)));
        }
        lines.add("Markets: " + (markets.length() == 0 ? "unknown" : markets) + "  ·  " + r.items() + " items");
        colors.add(SECONDARY);
        lines.add("Competes with you on " + r.overlap() + " of your markets  ·  click for their trades");
        colors.add(r.overlap() > 0 ? WARN : MUTED);

        int width = 0;
        for (String line : lines) width = Math.max(width, this.font.width(line));
        width = Math.min(width + 16, ui.innerWidth());
        int height = 8 + lines.size() * 11;
        int x = Math.min(mouseX + 12, ui.innerRight() - width);
        int y = mouseY + 12;
        if (y + height > ui.bottom() - 4) y = mouseY - height - 6;
        graphics.fill(x, y, x + width, y + height, 0xF80B0C10);
        graphics.outline(x, y, width, height, PANEL_EDGE);
        int yy = y + 5;
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(this.font, truncate(lines.get(i), width - 14), x + 7, yy, colors.get(i));
            yy += 11;
        }
    }

    private void renderRivalTable(GuiGraphicsExtractor graphics, RivalIntel.Snapshot s, int x, int y,
                                  int width, int height, long now, int mouseX, int mouseY) {
        card(graphics, x, y, width, height, CARD_DARK);
        sectionTitle(graphics, "WHO IS FLIPPING  ·  click a name for their trades", x, y, width);
        int innerX = x + 9;
        int innerWidth = width - 18;
        int yy = y + 27;
        if (s.rivals().isEmpty()) {
            drawWrapped(graphics, s.refreshedAt() > 0
                    ? "No seller in the last 24 hours has " + "60+ sales across 6+ items yet."
                    : s.status(), innerX, yy, innerWidth, y + height - 8, MUTED);
            return;
        }
        // Column lefts as shares of the width: name, sales, items, shared, revenue, seen, status, bot, strip.
        int[] col = {0, innerWidth * 22 / 100, innerWidth * 30 / 100, innerWidth * 37 / 100,
                innerWidth * 44 / 100, innerWidth * 55 / 100, innerWidth * 63 / 100, innerWidth * 71 / 100,
                innerWidth * 78 / 100};
        String[] heads = {"NAME", "SALES", "ITEMS", "SHARED", "REVENUE", "SEEN", "STATUS", "BOT", "24H"};
        for (int i = 0; i < heads.length; i++) {
            graphics.text(this.font, heads[i], innerX + col[i], yy, MUTED);
        }
        yy += 12;
        int rowHeight = 20;
        int rowsTop = yy;
        int shown = 0;
        hoveredRival = null;
        for (RivalIntel.Rival r : s.rivals()) {
            if (yy + rowHeight > y + height - 6) break;
            boolean hover = mouseX >= innerX && mouseX < innerX + innerWidth && mouseY >= yy && mouseY < yy + rowHeight;
            if (hover) {
                graphics.fill(innerX - 2, yy, innerX + innerWidth + 2, yy + rowHeight, ROW_HOVER);
                hoveredRival = r;
            }
            int textY = yy + 6;
            String status = r.status(now);
            int statusColor = status.equals("active") ? WARN : status.equals("quiet") ? SECONDARY : MUTED;
            graphics.text(this.font, truncate(r.name(), col[1] - 6), innerX + col[0], textY, TEXT);
            graphics.text(this.font, grouped(r.sales()), innerX + col[1], textY, TEXT);
            graphics.text(this.font, grouped(r.items()), innerX + col[2], textY, SECONDARY);
            graphics.text(this.font, grouped(r.overlap()), innerX + col[3], textY, r.overlap() > 0 ? GOOD : MUTED);
            graphics.text(this.font, money(r.revenue()), innerX + col[4], textY, SECONDARY);
            graphics.text(this.font, r.lastSeenAt() > 0 ? relativeAge(now - r.lastSeenAt()) : "never",
                    innerX + col[5], textY, SECONDARY);
            graphics.text(this.font, status, innerX + col[6], textY, statusColor);
            graphics.text(this.font, r.botScore() + "%", innerX + col[7], textY, r.botScore() >= 60 ? WARN : SECONDARY);
            activityStrip(graphics, r.hourly(), innerX + col[8], yy + 3, innerWidth - col[8], rowHeight - 6, now);
            yy += rowHeight;
            shown++;
        }
        rivalRowsGeometry = new int[] {rowsTop, rowHeight, shown};
    }

    /** 24 bars, one per hour of the day; the current hour is gold. */
    private void activityStrip(GuiGraphicsExtractor graphics, int[] hourly, int x, int y, int width, int height, long now) {
        if (hourly == null || hourly.length < 24 || width < 24) return;
        int max = 1;
        for (int v : hourly) max = Math.max(max, v);
        int barWidth = Math.max(1, width / 24);
        int currentHour = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).getHour();
        for (int h = 0; h < 24; h++) {
            int bh = hourly[h] == 0 ? 1 : Math.max(2, Math.round(hourly[h] * (float) height / max));
            int bx = x + h * barWidth;
            int color = hourly[h] == 0 ? 0xFF2A2D36 : h == currentHour ? GOLD : 0xFF5FCF78;
            graphics.fill(bx, y + height - bh, bx + Math.max(1, barWidth - 1), y + height, color);
        }
    }

    private void renderRivalDetail(GuiGraphicsExtractor graphics, RivalIntel.Rival r, int x, int y,
                                   int width, int bottom, long now) {
        card(graphics, x, y, width, bottom - y, CARD_DARK);
        sectionTitle(graphics, r.name() + "  ·  " + r.status(now) + "  ·  seen "
                + (r.lastSeenAt() > 0 ? relativeAge(now - r.lastSeenAt()) : "never"), x, y, width - 70);
        int innerX = x + 9;
        int innerWidth = width - 18;
        int yy = y + 27;
        int leftWidth = Math.min(320, Math.max(160, innerWidth / 2 - 8));
        yy = kvRight(graphics, innerX, yy, leftWidth, "Sales today",
                grouped(r.sales()) + " across " + r.items() + " items", TEXT);
        yy = kvRight(graphics, innerX, yy, leftWidth, "Revenue today", money(r.revenue()), TEXT);
        yy = kvRight(graphics, innerX, yy, leftWidth, "Est. profit today", money(r.estimatedProfit())
                + (r.profitFromFlips() ? "" : " (assumed 20%)"), GOOD);
        yy = kvRight(graphics, innerX, yy, leftWidth, "Bot likelihood", r.botScore() + "%  ·  "
                + r.activeHours() + "/24 h active", r.botScore() >= 60 ? WARN : SECONDARY);
        yy = kvRight(graphics, innerX, yy, leftWidth, "Markets shared with you", grouped(r.overlap()),
                r.overlap() > 0 ? GOOD : MUTED);
        StringBuilder tops = new StringBuilder();
        for (String item : r.topItems()) {
            if (tops.length() > 0) tops.append(", ");
            tops.append(titleCase(shortName(item)));
        }
        yy = kvRight(graphics, innerX, yy, leftWidth, "Top items", tops.length() == 0 ? "unknown" : tops.toString(), SECONDARY);
        yy = kvRight(graphics, innerX, yy, leftWidth, "Reconstructed flips", grouped(r.flips().size()), TEXT);
        yy = kvRight(graphics, innerX, yy, leftWidth, "Average margin", r.flips().isEmpty() ? "unknown"
                : String.format(Locale.ROOT, "%.1f%%", r.averageMarginPercent()),
                r.averageMarginPercent() >= 0 ? GOOD : BAD);
        yy = kvRight(graphics, innerX, yy, leftWidth, "Median turnaround",
                r.medianTurnaroundMillis() > 0 ? relativeAge(r.medianTurnaroundMillis()).replace(" ago", "") : "unknown", TEXT);

        int stripX = innerX + leftWidth + 14;
        int stripWidth = innerWidth - leftWidth - 14;
        if (stripWidth >= 48) {
            graphics.text(this.font, truncate("ACTIVITY BY HOUR  ·  sales per hour of day, gold = now", stripWidth),
                    stripX, y + 27, MUTED);
            activityStrip(graphics, r.hourly(), stripX, y + 40, stripWidth, 44, now);
            int barWidth = Math.max(1, stripWidth / 24);
            for (int h = 0; h < 24; h += 6) {
                graphics.text(this.font, String.valueOf(h), stripX + h * barWidth, y + 86, MUTED);
            }
        }

        // Lessons: their listing price against your target on the same market.
        int lessonsY = Math.max(yy, y + 98) + 4;
        hoveredRival = null;
        lessonsY = statusLine(graphics, innerX, lessonsY, innerWidth,
                "LESSONS  ·  their listing price vs your target on shared markets", GOLD);
        int lessons = 0;
        for (AutomationSessionController.OpenListingView mine : AUTOMATION_SESSION.openListingViews()) {
            if (lessonsY + 10 > bottom - 8 || lessons >= 4) break;
            String mineId = mine.itemKey().indexOf('#') >= 0
                    ? mine.itemKey().substring(0, mine.itemKey().indexOf('#')) : mine.itemKey();
            long sum = 0;
            int n = 0;
            long turn = 0;
            int turns = 0;
            for (RivalIntel.Flip f : r.flips()) {
                if (!f.itemKey().equals(mineId) || f.count() != mine.quantity()) continue;
                sum += f.listPrice();
                n++;
                if (f.soldAt() > 0 && f.boughtAt() > 0) {
                    turn += f.soldAt() - f.boughtAt();
                    turns++;
                }
            }
            if (n == 0) continue;
            long theirs = sum / n;
            String line = titleCase(shortName(mineId)) + " x" + mine.quantity() + ": they list at " + money(theirs)
                    + ", you at " + money(mine.targetPrice())
                    + (turns > 0 ? "; they turn in " + relativeAge(turn / turns).replace(" ago", "") : "");
            lessonsY = statusLine(graphics, innerX, lessonsY, innerWidth, line, theirs > mine.targetPrice() ? GOOD : SECONDARY);
            lessons++;
        }
        if (lessons == 0) {
            lessonsY = statusLine(graphics, innerX, lessonsY, innerWidth,
                    "No overlap between their reconstructed trades and your open listings right now.", MUTED);
        }

        // Their trades.
        int tableY = lessonsY + 6;
        if (tableY + 24 > bottom - 8) return;
        int[] col = {0, innerWidth * 30 / 100, innerWidth * 43 / 100, innerWidth * 56 / 100,
                innerWidth * 69 / 100, innerWidth * 82 / 100};
        String[] heads = {"ITEM", "BOUGHT", "LISTED", "SOLD", "MARGIN", "TURNAROUND"};
        for (int i = 0; i < heads.length; i++) graphics.text(this.font, heads[i], innerX + col[i], tableY, MUTED);
        tableY += 12;
        if (r.flips().isEmpty()) {
            drawWrapped(graphics, "No trades reconstructed yet. A flip is a sale of the same stack by someone else"
                    + " shortly before this seller listed it higher.", innerX, tableY, innerWidth, bottom - 8, MUTED);
            return;
        }
        for (RivalIntel.Flip f : r.flips()) {
            if (tableY + 11 > bottom - 8) break;
            graphics.text(this.font, truncate(titleCase(shortName(f.itemKey())) + " x" + f.count(), col[1] - 6),
                    innerX + col[0], tableY, TEXT);
            graphics.text(this.font, money(f.buyPrice()), innerX + col[1], tableY, SECONDARY);
            graphics.text(this.font, money(f.listPrice()), innerX + col[2], tableY, SECONDARY);
            graphics.text(this.font, f.soldPrice() > 0 ? money(f.soldPrice()) : "still up", innerX + col[3], tableY,
                    f.soldPrice() > 0 ? TEXT : MUTED);
            graphics.text(this.font, signedMoney(f.margin()) + String.format(Locale.ROOT, " (%.0f%%)", f.marginPercent()),
                    innerX + col[4], tableY, f.margin() >= 0 ? GOOD : BAD);
            graphics.text(this.font, f.soldAt() > 0 && f.boughtAt() > 0
                    ? relativeAge(f.soldAt() - f.boughtAt()).replace(" ago", "") : "-", innerX + col[5], tableY, SECONDARY);
            tableY += 11;
        }
    }

    private void renderBuyers(GuiGraphicsExtractor graphics, RivalIntel.Snapshot s, int x, int y,
                              int width, int height, long now) {
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "REPEAT BUYERS  ·  who buys from you", x, y, width);
        int innerX = x + 9;
        int innerWidth = width - 18;
        int yy = y + 27;
        if (s.buyers().isEmpty()) {
            drawWrapped(graphics, "No buyers recorded yet. Every sale line from now on keeps the buyer's name.",
                    innerX, yy, innerWidth, y + height - 8, MUTED);
            return;
        }
        for (RivalIntel.Buyer b : s.buyers()) {
            if (yy + 22 > y + height - 8) break;
            yy = kvRight(graphics, innerX, yy, innerWidth, b.name(),
                    b.purchases() + "x  ·  " + money(b.spent()), b.purchases() >= 2 ? GOOD : SECONDARY);
            yy = statusLine(graphics, innerX + 12, yy, innerWidth - 12,
                    "last " + relativeAge(now - b.lastAt()) + "  ·  " + titleCase(shortName(b.lastItem())), MUTED);
        }
    }


    // ------------------------------------------------------------------
    // Settings tab: every tunable number, editable live and saved.
    // ------------------------------------------------------------------

    private static boolean isListSetting(String key) {
        for (Tuning.ListSetting l : Tuning.LISTS) if (l.key().equals(key)) return true;
        return false;
    }

    private static String settingDraftFor(String key) {
        if (isListSetting(key)) return Tuning.text(key);
        double v = Tuning.get(key);
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    private void initSettingsWidgets(UiLayout ui) {
        settingField = null;
        // A search box on the title line: with this many settings, typing a few
        // letters is faster than reading down the cards.
        int searchW = Math.min(200, Math.max(120, ui.innerWidth() / 3));
        settingsSearchField = new EditBox(this.font, ui.innerLeft(), ui.contentTop() + 2, searchW, 16,
                Component.literal("Search settings"));
        settingsSearchField.setMaxLength(48);
        settingsSearchField.setHint(Component.literal("Search settings..."));
        settingsSearchField.setValue(settingsSearch);
        settingsSearchField.setResponder(value -> {
            settingsSearch = value;
            settingsScroll = 0;
        });
        addRenderableWidget(settingsSearchField);
        if (settingsSearchFocused) setFocused(settingsSearchField);
        // Presets on the title line, right-aligned: Default plus the named bundles.
        int px = ui.innerRight();
        int py = ui.contentTop() + 3;
        java.util.List<String> names = new ArrayList<>(Tuning.PRESETS.keySet());
        java.util.Collections.reverse(names);
        names.add("Default");
        for (String name : names) {
            int w = this.font.width(name) + 16;
            px -= w + 4;
            addRenderableWidget(CockpitButton.builder(Component.literal(name), ignored -> {
                int n = Tuning.applyPreset(name);
                settingsMessage = name + " preset: " + n + " setting(s) changed";
                selectedSetting = null;
                rebuildWidgets();
            }).bounds(px, py, w, 18).build());
        }
        if (selectedSetting == null) {
            int by = ui.bottom() - 27;
            int bx = ui.innerLeft();
            int w1 = 156;
            int w2 = 172;
            addRenderableWidget(CockpitButton.builder(Component.literal("Copy settings code"),
                    ignored -> exportSettingsCode()).bounds(bx, by, w1, 20).build());
            addRenderableWidget(CockpitButton.builder(Component.literal("Import from clipboard"),
                    ignored -> importSettingsCode()).bounds(bx + w1 + 6, by, w2, 20).build());
            return;
        }
        boolean list = isListSetting(selectedSetting);
        int y = ui.bottom() - 27;
        int x = ui.innerLeft();
        int buttons = 2 * 64 + 12;
        int fieldWidth = list ? Math.max(120, ui.innerWidth() - buttons - 6) : 120;
        settingField = new EditBox(this.font, x, y, fieldWidth, 20, Component.literal("Value"));
        settingField.setMaxLength(list ? 2000 : 16);
        settingField.setValue(settingDraft);
        settingField.setResponder(value -> settingDraft = value);
        addRenderableWidget(settingField);
        int bx = x + fieldWidth + 6;
        addRenderableWidget(CockpitButton.builder(Component.literal("Apply"), ignored -> applySetting())
                .bounds(bx, y, 64, 20).build());
        addRenderableWidget(CockpitButton.builder(Component.literal("Default"), ignored -> {
            if (isListSetting(selectedSetting)) {
                Tuning.setText(selectedSetting, "");
            } else {
                Tuning.reset(selectedSetting);
            }
            settingsMessage = "Reset to default";
            selectedSetting = null;
            rebuildWidgets();
        }).bounds(bx + 70, y, 64, 20).build());
    }

    private void exportSettingsCode() {
        String code = SettingsCode.export();
        if (code.isBlank()) {
            settingsMessage = "Could not build a settings code";
            rebuildWidgets();
            return;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        settingsMessage = "Settings code copied to clipboard (" + code.length()
                + " chars) — paste it to share (your webhook is not included)";
        rebuildWidgets();
    }

    private void importSettingsCode() {
        String code = Minecraft.getInstance().keyboardHandler.getClipboard();
        SettingsCode.Result r = SettingsCode.apply(code);
        settingsMessage = r.ok()
                ? "Imported " + r.applied() + " setting(s) from the clipboard"
                        + (r.skipped() > 0 ? " (" + r.skipped() + " unknown skipped)" : "")
                : "Import failed: " + r.error();
        selectedSetting = null;
        rebuildWidgets();
    }

    private void applySetting() {
        if (selectedSetting == null) return;
        if (isListSetting(selectedSetting)) {
            Tuning.setText(selectedSetting, settingDraft);
            int n = Tuning.itemSet(selectedSetting).size();
            settingsMessage = "Saved: " + n + " item(s)";
        } else {
            Tuning.Setting setting = Tuning.setting(selectedSetting);
            String raw = settingDraft.replaceAll("[^0-9.\\-]", "");
            double parsed;
            try {
                parsed = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                settingsMessage = "Not a number: " + settingDraft;
                return;
            }
            double stored = Tuning.set(selectedSetting, parsed);
            settingsMessage = "Saved " + setting.label() + " = " + setting.format(stored)
                    + (stored != parsed ? " (kept within " + setting.format(setting.min()) + " to "
                    + setting.format(setting.max()) + ")" : "");
        }
        selectedSetting = null;
        rebuildWidgets();
    }

    /** Whether the settings search hides a row; matches on key, label or description. */
    private boolean settingHidden(String key, String label, String desc) {
        if (settingsSearch.isBlank()) return false;
        String q = settingsSearch.strip().toLowerCase(Locale.ROOT);
        return !(key.toLowerCase(Locale.ROOT).contains(q)
                || label.toLowerCase(Locale.ROOT).contains(q)
                || (desc != null && desc.toLowerCase(Locale.ROOT).contains(q)));
    }

    private void renderSettings(GuiGraphicsExtractor graphics, UiLayout ui, int mouseX, int mouseY) {
        resetListGeometry();
        settingRowBounds.clear();
        settingRowKeys.clear();
        int x = ui.innerLeft();
        int width = ui.innerWidth();
        int right = ui.innerRight();
        int top = ui.contentTop() + 7;
        // The title sits right of the search box that init drew on this line.
        int searchW = Math.min(200, Math.max(120, width / 3));
        String hint = selectedSetting == null
                ? "click a row to edit  ·  presets on the right"
                : "type a value, then Apply";
        graphics.text(this.font, truncate("Settings  ·  " + hint, Math.max(20, width - searchW - 12)),
                x + searchW + 8, top, GOLD);

        int listTopY = top + 16;
        int listBottomY;
        if (selectedSetting == null) {
            // Leave the bottom strip for the Copy / Import settings-code row.
            listBottomY = ui.bottom() - 32;
            if (!settingsMessage.isBlank()) {
                listBottomY -= 12;
                graphics.text(this.font, truncate(settingsMessage, width), x, ui.bottom() - 40, GOOD);
            }
        } else {
            listBottomY = ui.bottom() - 58;
        }

        // One card per group, flowed into two columns, shortest column first.
        boolean wide = width >= 640;
        int gap = 8;
        int colWidth = wide ? (width - gap) / 2 : width;
        int[] colY = {listTopY - settingsScroll, listTopY - settingsScroll};
        int rowHeight = 13;
        int index = 0;
        hoveredSetting = null;
        List<String> groups = new ArrayList<>(Tuning.groups());
        groups.add("Keys");
        groups.add("Items");
        for (String group : groups) {
            boolean items = group.equals("Items");
            boolean keys = group.equals("Keys");
            int rows = 0;
            if (items) {
                for (Tuning.ListSetting l : Tuning.LISTS) {
                    if (!settingHidden(l.key(), l.label(), l.description())) rows++;
                }
            } else if (keys) {
                if (!settingHidden("key.open", "Open the GoNuts screen", "")) rows++;
                if (!settingHidden("key.stop", "Emergency stop", "")) rows++;
            } else {
                for (Tuning.Setting setting : Tuning.SETTINGS) {
                    if (setting.group().equals(group)
                            && !settingHidden(setting.key(), setting.label(), setting.description())) rows++;
                }
            }
            if (rows == 0) continue;   // the search hid this whole group
            int cardHeight = 27 + rows * rowHeight + 5;
            int col = (!wide || colY[0] <= colY[1]) ? 0 : 1;
            int cx = x + col * (colWidth + gap);
            int cy = colY[col];
            boolean visible = cy + cardHeight > listTopY && cy < listBottomY;
            if (visible) {
                int visibleTop = Math.max(cy, listTopY);
                int visibleBottom = Math.min(cy + cardHeight, listBottomY);
                graphics.fill(cx, visibleTop, cx + colWidth, visibleBottom, CARD);
                graphics.outline(cx, visibleTop, colWidth, visibleBottom - visibleTop, PANEL_EDGE);
                if (cy >= listTopY && cy + 21 <= listBottomY) {
                    sectionTitle(graphics, group.toUpperCase(Locale.ROOT), cx, cy, colWidth);
                }
            }
            int yy = cy + 27;
            int innerX = cx + 9;
            int innerWidth = colWidth - 18;
            if (keys) {
                KeyMapping open = DoughBayClient.keyOpenScreen();
                KeyMapping stop = DoughBayClient.keyEmergencyStop();
                if (!settingHidden("key.open", "Open the GoNuts screen", "")) {
                    yy = renderSettingRow(graphics, innerX, yy, innerWidth, rowHeight, listTopY, listBottomY,
                            "key.open", "Open the GoNuts screen", "Click, then press the new key",
                            rebindingKey == open ? "press a key…" : keyName(open), open == null || open.isDefault(),
                            index++, mouseX, mouseY);
                }
                if (!settingHidden("key.stop", "Emergency stop", "")) {
                    yy = renderSettingRow(graphics, innerX, yy, innerWidth, rowHeight, listTopY, listBottomY,
                            "key.stop", "Emergency stop", "Click, then press the new key",
                            rebindingKey == stop ? "press a key…" : keyName(stop), stop == null || stop.isDefault(),
                            index++, mouseX, mouseY);
                }
            } else if (items) {
                for (Tuning.ListSetting l : Tuning.LISTS) {
                    if (settingHidden(l.key(), l.label(), l.description())) continue;
                    String value = Tuning.text(l.key());
                    int n = Tuning.itemSet(l.key()).size();
                    yy = renderSettingRow(graphics, innerX, yy, innerWidth, rowHeight, listTopY, listBottomY,
                            l.key(), l.label(), l.description(),
                            n == 0 ? (l.key().endsWith("allow") ? "everything" : "nothing") : n + ": " + value,
                            n == 0, index++, mouseX, mouseY);
                }
            } else {
                for (Tuning.Setting setting : Tuning.SETTINGS) {
                    if (!setting.group().equals(group)) continue;
                    if (settingHidden(setting.key(), setting.label(), setting.description())) continue;
                    yy = renderSettingRow(graphics, innerX, yy, innerWidth, rowHeight, listTopY, listBottomY,
                            setting.key(), setting.label(), setting.description(),
                            setting.format(Tuning.get(setting.key())), Tuning.isDefault(setting.key()),
                            index++, mouseX, mouseY);
                }
            }
            colY[col] = cy + cardHeight + gap;
        }
        if (index == 0 && !settingsSearch.isBlank()) {
            graphics.text(this.font, "No settings match \"" + settingsSearch.strip() + "\"",
                    x, listTopY + 4, MUTED);
        }
        int contentBottom = Math.max(colY[0], colY[1]) - gap + settingsScroll;
        settingsMaxScroll = Math.max(0, contentBottom - listBottomY);
        if (hoveredSetting != null && selectedSetting == null) {
            renderSettingTooltip(graphics, hoveredSetting, mouseX, mouseY, ui);
        }

        if (selectedSetting != null) {
            int barY = ui.bottom() - 52;
            graphics.fill(x, barY - 4, right, ui.bottom() - 4, CARD);
            String label;
            String details;
            if (isListSetting(selectedSetting)) {
                Tuning.ListSetting l = null;
                for (Tuning.ListSetting c : Tuning.LISTS) if (c.key().equals(selectedSetting)) l = c;
                label = l == null ? selectedSetting : l.label();
                details = l == null ? "" : l.description();
            } else {
                Tuning.Setting setting = Tuning.setting(selectedSetting);
                label = setting.label();
                details = setting.description() + ".  Range " + setting.format(setting.min()) + " to "
                        + setting.format(setting.max()) + ", default " + setting.format(setting.defaultValue()) + ".";
            }
            graphics.text(this.font, truncate("Editing: " + label, width), x + 2, barY, GOLD);
            graphics.text(this.font, truncate(details, width), x + 2, barY + 11, SECONDARY);
            if (!settingsMessage.isBlank()) {
                graphics.text(this.font, truncate(settingsMessage, width / 2),
                        right - this.font.width(truncate(settingsMessage, width / 2)), barY, WARN);
            }
        }
    }

    private int renderSettingRow(GuiGraphicsExtractor graphics, int x, int y, int width, int rowHeight,
                                 int listTopY, int listBottomY, String key, String label,
                                 String description, String value, boolean isDefault,
                                 int index, int mouseX, int mouseY) {
        settingRowKeys.add(key);
        if (y < listTopY || y + rowHeight > listBottomY) return y + rowHeight;
        boolean hover = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + rowHeight;
        boolean editing = key.equals(selectedSetting);
        if (hover) hoveredSetting = key;
        if (hover || editing) graphics.fill(x - 3, y - 1, x + width + 3, y + rowHeight - 1, editing ? 0xFF2A3140 : ROW_HOVER);
        // Rows sit in two columns, so a hit box needs its x range too.
        settingRowBounds.add(new int[] {y, y + rowHeight, index, x - 3, x + width + 3});
        String shownValue = truncate(value, Math.max(40, width / 2));
        int valueX = x + width - this.font.width(shownValue);
        graphics.text(this.font, truncate(label, valueX - x - 8), x, y + 2, TEXT);
        graphics.text(this.font, shownValue, valueX, y + 2, isDefault ? SECONDARY : GOLD);
        return y + rowHeight;
    }

    /** What a setting does, its range and default, shown under the mouse. */
    private void renderSettingTooltip(GuiGraphicsExtractor graphics, String key, int mouseX, int mouseY, UiLayout ui) {
        if (key.startsWith("key.")) return;
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (isListSetting(key)) {
            for (Tuning.ListSetting l : Tuning.LISTS) {
                if (!l.key().equals(key)) continue;
                lines.add(l.label());
                colors.add(GOLD);
                for (FormattedCharSequence line : this.font.split(Component.literal(l.description()), 260)) {
                    lines.add(null);
                    colors.add(SECONDARY);
                    wrappedTooltipLines.add(line);
                }
            }
        } else {
            Tuning.Setting setting = Tuning.setting(key);
            lines.add(setting.label());
            colors.add(GOLD);
            for (FormattedCharSequence line : this.font.split(Component.literal(setting.description()), 260)) {
                lines.add(null);
                colors.add(SECONDARY);
                wrappedTooltipLines.add(line);
            }
            lines.add("Range " + setting.format(setting.min()) + " to " + setting.format(setting.max())
                    + "  ·  default " + setting.format(setting.defaultValue()));
            colors.add(MUTED);
            if (!Tuning.isDefault(key)) {
                lines.add("Changed from the default; click to edit or reset");
                colors.add(WARN);
            } else {
                lines.add("Click to edit");
                colors.add(MUTED);
            }
        }
        int width = 0;
        int wrappedIndex = 0;
        for (int i = 0; i < lines.size(); i++) {
            int w = lines.get(i) == null ? this.font.width(wrappedTooltipLines.get(wrappedIndex++)) : this.font.width(lines.get(i));
            width = Math.max(width, w);
        }
        width = Math.min(width + 16, ui.innerWidth());
        int height = 8 + lines.size() * 11;
        int x = Math.min(mouseX + 12, ui.innerRight() - width);
        int y = mouseY + 12;
        if (y + height > ui.bottom() - 4) y = mouseY - height - 6;
        graphics.fill(x, y, x + width, y + height, 0xF80B0C10);
        graphics.outline(x, y, width, height, PANEL_EDGE);
        int yy = y + 5;
        wrappedIndex = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i) == null) {
                graphics.text(this.font, wrappedTooltipLines.get(wrappedIndex++), x + 7, yy, colors.get(i));
            } else {
                graphics.text(this.font, truncate(lines.get(i), width - 14), x + 7, yy, colors.get(i));
            }
            yy += 11;
        }
        wrappedTooltipLines.clear();
    }

    private final List<FormattedCharSequence> wrappedTooltipLines = new ArrayList<>();

    private int[] slotBoardCell(int x, int y, int width, int index) {
        int gap = 3;
        int cell = Math.max(10, Math.min(18, (width - 18 - gap * (SlotTracker.columns() - 1)) / SlotTracker.columns()));
        int col = index % SlotTracker.columns();
        int row = index / SlotTracker.columns();
        return new int[] {x + 9 + col * (cell + gap), y + 27 + row * (cell + gap), cell};
    }

    private void renderSlotBoard(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                                 List<AutomationSessionController.OpenListingView> open, int other,
                                 long now, int mouseX, int mouseY) {
        card(graphics, x, y, width, height, CARD_DARK);
        int cap = AUTOMATION_SESSION.maxOpenListings();
        // The server's own count, which is the only one that cannot exceed the
        // cap. Adding the ledger's listings to the audit rows it failed to
        // match showed ninety-four of ninety, and called fifteen of them
        // "yours" on an account with no hand-made listings at all. They were
        // DoughBay's own, and reconciliation simply had not matched them.
        int fromServer = AUTOMATION_SESSION.serverListedSlots();
        int used = Math.min(SlotTracker.slots(),
                fromServer >= 0 ? fromServer : open.size() + other);
        int unmatched = Math.max(0, Math.min(other, used - open.size()));
        sectionTitle(graphics, "AUCTION SLOTS  " + used + " / " + SlotTracker.slots()
                + (unmatched > 0 ? "  (" + unmatched + " unmatched)" : ""), x, y, width);
        int hovered = hoveredSlot(x, y, width, mouseX, mouseY);
        for (int i = 0; i < SlotTracker.slots(); i++) {
            int[] c = slotBoardCell(x, y, width, i);
            int cx = c[0];
            int cy = c[1];
            int cell = c[2];
            boolean mod = i < open.size();
            boolean yours = !mod && i < used;
            int fill = mod ? 0xFF5FCF78 : yours ? 0xFF6FA8DC : i < cap ? 0xFF1C1E26 : 0xFF14151A;
            int edge = mod ? 0xFF2E7A40 : yours ? 0xFF2F5C8A : 0xFF3C4050;
            graphics.fill(cx, cy, cx + cell, cy + cell, i == hovered ? 0xFFFFFFFF : edge);
            graphics.fill(cx + 1, cy + 1, cx + cell - 1, cy + cell - 1, fill);
            if (mod) {
                var listing = open.get(i);
                net.minecraft.world.item.ItemStack stack = stackFor(listing.itemKey(), listing.quantity());
                if (stack != null && cell >= 16) {
                    graphics.item(stack, cx + (cell - 16) / 2, cy + (cell - 16) / 2);
                }
            }
        }
        int[] last = slotBoardCell(x, y, width, SlotTracker.slots() - 1);
        int legendY = last[1] + last[2] + 8;
        if (legendY + 10 <= y + height - 6) {
            graphics.text(this.font, truncate("green: GoNuts listing  ·  blue: up but unmatched  ·  hover a slot for the item",
                    width - 18), x + 9, legendY, MUTED);
        }
        int ageY = legendY + 12;
        if (!open.isEmpty() && ageY + 10 <= y + height - 6) {
            long oldest = Long.MAX_VALUE;
            for (var l : open) oldest = Math.min(oldest, l.listedAt());
            graphics.text(this.font, truncate("oldest listing " + relativeAge(Math.max(0, now - oldest))
                    + "  ·  reprice after 10 min unsold", width - 18), x + 9, ageY, SECONDARY);
        }
    }

    private int hoveredSlot(int x, int y, int width, int mouseX, int mouseY) {
        for (int i = 0; i < SlotTracker.slots(); i++) {
            int[] c = slotBoardCell(x, y, width, i);
            if (mouseX >= c[0] && mouseX < c[0] + c[2] && mouseY >= c[1] && mouseY < c[1] + c[2]) return i;
        }
        return -1;
    }

    private int[] lastSlotBoard = null;

    private void renderSlotTooltip(GuiGraphicsExtractor graphics,
                                   List<AutomationSessionController.OpenListingView> open, int other,
                                   long now, int mouseX, int mouseY) {
        if (lastSlotBoard == null) return;
        int hovered = hoveredSlot(lastSlotBoard[0], lastSlotBoard[1], lastSlotBoard[2], mouseX, mouseY);
        if (hovered < 0) return;
        List<String> lines = new ArrayList<>();
        net.minecraft.world.item.ItemStack stack = null;
        if (hovered < open.size()) {
            var l = open.get(hovered);
            stack = stackFor(l.itemKey(), l.quantity());
            lines.add(titleCase(shortName(l.itemKey())) + " x" + l.quantity());
            lines.add("Bought " + money(l.purchasePrice()) + "  →  listed " + money(l.targetPrice()));
            lines.add("Profit if sold " + signedMoney(l.targetPrice() - l.purchasePrice()));
            lines.add("Listed " + relativeAge(Math.max(0, now - l.listedAt())) + " ago");
        } else if (hovered < Math.min(SlotTracker.slots(),
                Math.max(open.size(), AUTOMATION_SESSION.serverListedSlots()))) {
            // Not the player's listing. It is one of ours whose record the
            // ledger has lost - the second line said so while the first still
            // blamed the player for it, on an account that has never listed
            // anything by hand. The blue cells are the size of the gap between
            // what the bot owns and what it knows it owns.
            lines.add("Unmatched listing");
            lines.add("Up on the auction house; the ledger has lost its record");
            lines.add("Not repriced or pulled back until the audit reclaims it");
        } else {
            lines.add("Empty slot");
        }
        int textWidth = 0;
        for (String line : lines) textWidth = Math.max(textWidth, this.font.width(line));
        int iconWidth = stack != null ? 22 : 0;
        int w = textWidth + iconWidth + 14;
        int h = lines.size() * 11 + 10;
        int tx = Math.min(mouseX + 12, this.width - w - 4);
        int ty = Math.min(mouseY - 4, this.height - h - 4);
        graphics.fill(tx - 1, ty - 1, tx + w + 1, ty + h + 1, 0xFF3C4050);
        graphics.fill(tx, ty, tx + w, ty + h, 0xF8101117);
        if (stack != null) graphics.item(stack, tx + 5, ty + (h - 16) / 2);
        int ly = ty + 5;
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(this.font, lines.get(i), tx + 7 + iconWidth, ly, i == 0 ? TEXT : SECONDARY);
            ly += 11;
        }
    }

    private static net.minecraft.world.item.ItemStack stackFor(String itemKey, int count) {
        try {
            String id = itemKey;
            int hash = id.indexOf('#');
            if (hash >= 0) id = id.substring(0, hash);
            net.minecraft.resources.Identifier ident = net.minecraft.resources.Identifier.tryParse(id);
            if (ident == null) return null;
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(ident);
            if (item == null) return null;
            return new net.minecraft.world.item.ItemStack(item, Math.max(1, count));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void renderTopMarkets(GuiGraphicsExtractor graphics, PerformanceStatsSnapshot stats,
                                  int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "TOP MARKETS  ·  by profit", x, y, width);
        int innerX = x + 9;
        int innerWidth = width - 18;
        int yy = y + 27;
        List<PerformanceStatsSnapshot.ItemPerformance> ranked = stats.mostProfitableItems();
        if (ranked.isEmpty()) {
            drawWrapped(graphics, "No closed sales yet.", innerX, yy, innerWidth, y + height - 8, MUTED);
            return;
        }
        int shown = 0;
        for (var item : ranked) {
            if (yy + 22 > y + height - 8) break;
            if (shown == 3) {
                yy = statusLine(graphics, innerX, yy + 2, innerWidth, "OTHERS", MUTED);
            }
            String label = (shown < 3 ? (shown + 1) + ".  " : "") + item.displayName();
            yy = kvRight(graphics, innerX, yy, innerWidth, label, signedMoney(item.realizedProfit()),
                    shown < 3 ? GOOD : SECONDARY);
            yy = statusLine(graphics, innerX + 12, yy, innerWidth - 12,
                    item.completedSales() + " sale(s)  ·  " + item.unitsSold() + " units  ·  "
                            + money(item.invested()) + " spent", MUTED);
            shown++;
            if (shown >= 8) break;
        }
        var mostSold = stats.mostSoldItem();
        if (mostSold != null && yy + 12 <= y + height - 8) {
            kvRight(graphics, innerX, yy + 4, innerWidth, "Most sold", mostSold.displayName()
                    + " (" + mostSold.completedSales() + ")", SECONDARY);
        }
    }

    private void headlineCard(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                              String label, String value, String note, int valueColor) {
        card(graphics, x, y, width, height, CARD);
        graphics.text(this.font, truncate(label, width - 14), x + 7, y + 6, MUTED);
        float valueScale = width >= 170 ? 1.35f : 1.15f;
        scaledText(graphics, truncate(value, Math.round((width - 14) / valueScale)),
                x + 7, y + 19, valueScale, valueColor);
        if (height >= 52) {
            graphics.text(this.font, truncate(note, width - 14), x + 7, y + height - 13, SECONDARY);
        }
    }

    private void renderPerformanceChart(GuiGraphicsExtractor graphics, PerformanceStatsSnapshot stats,
                                        int x, int y, int width, int height,
                                        int mouseX, int mouseY) {
        card(graphics, x, y, width, height, CARD_DARK);
        sectionTitle(graphics, "CUMULATIVE PERFORMANCE • LAST 30 DAYS", x, y, width);
        List<Charts.Point> profit = stats.profitHistory().stream()
                .map(point -> new Charts.Point(point.atMillis(), point.cumulativeProfit()))
                .toList();
        List<Charts.Point> deployed = stats.profitHistory().stream()
                .map(point -> new Charts.Point(point.atMillis(), point.cumulativeCapitalDeployed()))
                .toList();
        int innerX = x + 7;
        int innerWidth = width - 14;
        int chartTop = y + 25;
        int available = height - 31;
        int firstHeight = Math.max(56, available / 2);
        String pnl = "REALIZED P/L  " + signedMoney(stats.realizedProfit());
        graphics.text(this.font, pnl, innerX + 3, chartTop, stats.realizedProfit() >= 0 ? GOOD : BAD);
        int lineY = chartTop + 10;
        Charts.line(graphics, this.font, innerX, lineY, innerWidth,
                Math.max(45, firstHeight - 11), profit, Charts.SERIES_AQUA,
                new double[]{0}, new int[]{PANEL_EDGE}, new String[]{"break-even"},
                Double.NaN, GOOD, System.currentTimeMillis(), 30L * 24 * 60 * 60 * 1_000,
                mouseX, mouseY, pendingTooltip);

        int secondTitleY = lineY + Math.max(45, firstHeight - 11) + 3;
        if (secondTitleY + 43 < y + height) {
            graphics.text(this.font, "CAPITAL DEPLOYED  " + money(stats.totalInvested()),
                    innerX + 3, secondTitleY, Charts.SERIES_ORANGE);
            int secondChartY = secondTitleY + 10;
            Charts.line(graphics, this.font, innerX, secondChartY, innerWidth,
                    Math.max(38, y + height - secondChartY - 7), deployed, Charts.SERIES_ORANGE,
                    new double[0], new int[0], new String[0], Double.NaN, GOOD,
                    System.currentTimeMillis(), 30L * 24 * 60 * 60 * 1_000,
                    mouseX, mouseY, pendingTooltip);
        }
    }

    private void renderItemLeaders(GuiGraphicsExtractor graphics, PerformanceStatsSnapshot stats,
                                   int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "ITEM LEADERS", x, y, width);
        List<PerformanceStatsSnapshot.ItemPerformance> ranked = stats.mostProfitableItems();
        PerformanceStatsSnapshot.ItemPerformance mostSold = stats.mostSoldItem();
        int yy = y + 25;
        if (!ranked.isEmpty()) {
            var top = ranked.getFirst();
            graphics.text(this.font, "★ MOST PROFITABLE", x + 8, yy, GOLD);
            yy += 11;
            graphics.text(this.font, truncate(top.displayName(), width - 92), x + 8, yy, TEXT);
            String amount = signedMoney(top.realizedProfit());
            graphics.text(this.font, amount, x + width - 8 - this.font.width(amount), yy, GOOD);
            yy += 14;
        }
        if (mostSold != null) {
            graphics.text(this.font, "◆ MOST SOLD", x + 8, yy, Charts.SERIES_AQUA);
            yy += 11;
            String units = grouped(mostSold.unitsSold()) + " units";
            graphics.text(this.font, truncate(mostSold.displayName(), width - 85), x + 8, yy, TEXT);
            graphics.text(this.font, units, x + width - 8 - this.font.width(units), yy, GOOD);
            yy += 15;
        }

        graphics.fill(x + 7, yy, x + width - 7, yy + 1, PANEL_INNER_EDGE);
        yy += 5;
        graphics.text(this.font, "EXACT MARKET", x + 8, yy, MUTED);
        String header = "SALES     P/L";
        graphics.text(this.font, header, x + width - 8 - this.font.width(header), yy, MUTED);
        yy += 11;
        int limit = Math.min(4, ranked.size());
        for (int i = 0; i < limit && yy <= y + height - 21; i++) {
            var item = ranked.get(i);
            String rank = (i + 1) + "  " + item.displayName();
            graphics.text(this.font, truncate(rank, width - 112), x + 8, yy, SECONDARY);
            String values = item.completedSales() + "   " + signedMoney(item.realizedProfit());
            graphics.text(this.font, values, x + width - 8 - this.font.width(values), yy,
                    item.realizedProfit() >= 0 ? GOOD : BAD);
            yy += 11;
        }
        if (height >= 174) {
            String note = "Fingerprint + stack size stay separate";
            graphics.text(this.font, truncate(note, width - 16), x + 8, y + height - 13, MUTED);
        }
    }

    private void renderTradeQuality(GuiGraphicsExtractor graphics, PerformanceStatsSnapshot stats,
                                    int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "TRADE QUALITY", x, y, width);
        int innerX = x + 8;
        int innerWidth = width - 16;
        int yy = y + 26;
        yy = kvRight(graphics, innerX, yy, innerWidth, "Profitable sales",
                String.format(Locale.ROOT, "%.1f%%", stats.winRatePercent()), GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Closed W / L / even",
                stats.profitableSales() + " / " + stats.netNegativeSales()
                        + " / " + stats.breakEvenSales(),
                stats.netNegativeSales() == 0 ? GOOD : SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Purchased / open",
                stats.purchasedTrades() + " / " + stats.openTrades(), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Best sale ROI",
                String.format(Locale.ROOT, "%.1f%%", stats.bestRoiPercent()), GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Best closed sale",
                signedMoney(stats.bestTradeProfit()), GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Median reconciled hold",
                duration(stats.medianHoldMillis()), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Open cost basis",
                money(stats.openCostBasis()), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Unreconciled",
                stats.unreconciledTrades() + " / " + money(stats.unreconciledCost()),
                stats.unreconciledTrades() == 0 ? GOOD : WARN);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Realized P/L per hour",
                stats.profitPerHour() == null ? "not measured" : signedMoney(stats.profitPerHour()),
                stats.profitPerHour() != null && stats.profitPerHour() < 0 ? BAD : SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Session active",
                duration(stats.currentSessionActiveMillis()), Charts.SERIES_AQUA);
        if (yy <= y + height - 12) {
            kvRight(graphics, innerX, yy, innerWidth, "Lifetime active",
                    duration(stats.lifetimeActiveMillis()), GOLD);
        }
    }

    private void renderMilestones(GuiGraphicsExtractor graphics, PerformanceStatsSnapshot stats,
                                  int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD_DARK);
        String title = stats.mode() == PerformanceStatsSnapshot.Mode.PAPER
                ? "SIMULATED MILESTONES" : "REAL MILESTONES";
        sectionTitle(graphics, title, x, y, width);
        int columns = width >= 410 ? 2 : 1;
        int gap = 6;
        int badgeWidth = (width - 16 - gap * (columns - 1)) / columns;
        int top = y + 25;
        int availableRows = Math.max(1, (height - 30) / 40);
        int limit = Math.min(stats.milestones().size(), availableRows * columns);
        for (int i = 0; i < limit; i++) {
            var milestone = stats.milestones().get(i);
            int col = i % columns;
            int row = i / columns;
            int bx = x + 8 + col * (badgeWidth + gap);
            int by = top + row * 40;
            renderMilestoneBadge(graphics, milestone, bx, by, badgeWidth, 35);
        }
    }

    private void renderMilestoneBadge(GuiGraphicsExtractor graphics,
                                      PerformanceStatsSnapshot.Milestone milestone,
                                      int x, int y, int width, int height) {
        int edge = milestone.unlocked() ? 0xFF5B6C3E : PANEL_EDGE;
        graphics.fill(x, y, x + width, y + height, milestone.unlocked() ? 0xE8161D16 : CARD);
        graphics.outline(x, y, width, height, edge);
        String marker = milestone.unlocked() ? "✓ " : "◇ ";
        graphics.text(this.font, truncate(marker + milestone.title(), width - 10),
                x + 5, y + 4, milestone.unlocked() ? GOOD : SECONDARY);
        String progress = milestoneProgress(milestone);
        graphics.text(this.font, truncate(progress, width - 10), x + 5, y + 15,
                milestone.unlocked() ? GOOD : MUTED);
        int barX = x + 5;
        int barRight = x + width - 5;
        int barY = y + height - 7;
        graphics.fill(barX, barY, barRight, barY + 3, TRACK);
        int fill = (int) Math.round((barRight - barX) * milestone.completion());
        graphics.fill(barX, barY, barX + fill, barY + 3,
                milestone.unlocked() ? GOOD : Charts.SERIES_BLUE);
    }

    private String milestoneProgress(PerformanceStatsSnapshot.Milestone milestone) {
        if (milestone.id().contains("profit") || milestone.id().contains("deployed")) {
            return money(milestone.progress()) + " / " + money(milestone.target());
        }
        if (milestone.id().contains("active")) {
            return milestone.progress() / 60 + "h / " + milestone.target() / 60 + "h active";
        }
        return grouped(milestone.progress()) + " / " + grouped(milestone.target()) + " sales";
    }

    private void renderCompactStats(GuiGraphicsExtractor graphics, PerformanceStatsSnapshot stats,
                                    int x, int y, int width, int bottom,
                                    int mouseX, int mouseY) {
        int remaining = Math.max(100, bottom - y);
        int chartHeight = Math.min(185, Math.max(135, remaining * 56 / 100));
        renderPerformanceChart(graphics, stats, x, y, width, chartHeight, mouseX, mouseY);
        int lowerTop = y + chartHeight + 7;
        int lowerHeight = Math.max(70, bottom - lowerTop);
        int half = (width - 7) / 2;
        renderItemLeaders(graphics, stats, x, lowerTop, half, lowerHeight);
        renderTradeQuality(graphics, stats, x + half + 7, lowerTop,
                width - half - 7, lowerHeight);
    }

    // --------------------------------------------------------------- automation

    private void initAutomationWidgets(UiLayout ui, MarketWatcher.Snapshot snapshot) {
        AutomationModeGeometry geometry = automationModeGeometry(ui);
        Opportunity current = currentOpportunity(snapshot, automationCandidate);
        AutomatedExecutionDriver.OperationIntent intent = DRIVER.operationIntent();
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        boolean active = intent != AutomatedExecutionDriver.OperationIntent.NONE
                || session.state() != AutomationSessionController.State.STOPPED
                || session.unresolvedExposure();
        automationModeButton = CockpitButton.builder(
                        Component.literal(automationMode == AutomationMode.DISCORD
                                ? "◀ Back to Continuous"
                                : automationMode == AutomationMode.TOOLS
                                ? "Discord command centre ▶"
                                : "Tools: API key / test ▶"), ignored -> {
                            automationMode = automationMode.next();
                            driverMessage = "";
                            rebuildWidgets();
                        })
                .bounds(geometry.modeX(), geometry.modeY(), geometry.modeWidth() - 118, 20)
                .build();
        addRenderableWidget(automationModeButton);
        // Evasion toggle beside it: stop when another player comes within
        // 24 blocks, resume after a clear minute.
        EvasionGuard evasion = DoughBayClient.evasionGuard();
        if (evasion != null) {
            addRenderableWidget(CockpitButton.builder(
                            Component.literal(evasion.enabled()
                                    ? "Evasion: ON (" + EvasionGuard.radiusBlocks() + "b)"
                                    : "Evasion: OFF"), ignored -> {
                                evasion.setEnabled(!evasion.enabled());
                                driverMessage = "Evasion " + evasion.status();
                                rebuildWidgets();
                            })
                    .bounds(geometry.modeX() + geometry.modeWidth() - 114, geometry.modeY(), 114, 20)
                    .build());
        }

        switch (automationMode) {
            case TOOLS -> {
                // Collection setup and click proving; neither arms a session.
                initApiKeyWidgets(geometry);
                initTestBuyWidgets(geometry, active);
            }
            case DISCORD -> initDiscordWidgets(geometry);
            case CONTINUOUS -> {
                if (showLiveTradingSetup()) {
                    initLiveTradingSetupWidgets(geometry);
                } else {
                    initSessionModeWidgets(snapshot, geometry,
                            AutomationSessionController.RunMode.CONTINUOUS);
                }
            }
        }
    }

    private void initPreflightModeWidgets(MarketWatcher.Snapshot snapshot, Opportunity current,
                                          AutomationModeGeometry geometry, boolean active) {
        addAutomationCandidateButton(current, geometry);
        ModeActionGeometry actions = modeActionGeometry(geometry);
        String blocker = preflightBlocker(snapshot, automationCandidate);
        automationPreflightButton = CockpitButton.builder(
                        Component.literal("Run one-shot preflight — ZERO CLICKS"),
                        ignored -> handleAutomationPreflight())
                .bounds(actions.primaryX(), actions.primaryY(), actions.primaryWidth(), 20)
                .build();
        automationPreflightButton.active = !active && blocker == null;
        addRenderableWidget(automationPreflightButton);
        addAutomationStatusButton(actions.secondaryX(), actions.secondaryY(),
                actions.secondaryWidth(), "Clear preflight report");
        initTestBuyWidgets(geometry, active);
    }

    /** The lower-left card of the preflight layout, shared by init and render. */
    private int[] testBuyCardBounds(AutomationModeGeometry geometry) {
        ApiKeyGeometry key = apiKeyGeometry(geometry);
        int y = geometry.bodyY();
        return new int[] {geometry.left(), y, geometry.width(), key.y() - geometry.gap() - y};
    }

    private void initTestBuyWidgets(AutomationModeGeometry geometry, boolean active) {
        int[] b = testBuyCardBounds(geometry);
        int innerX = b[0] + 9;
        int innerWidth = b[2] - 18;
        int fieldY = b[1] + 30;
        int itemWidth = Math.max(80, innerWidth - 70 - 6);

        testBuyItemField = new EditBox(this.font, innerX, fieldY, itemWidth, 20,
                Component.literal("Item id"));
        testBuyItemField.setMaxLength(64);
        testBuyItemField.setHint(Component.literal("item, e.g. dirt x64"));
        testBuyItemField.setValue(testBuyItemDraft);
        testBuyItemField.setResponder(value -> {
            testBuyItemDraft = value;
            testBuyMessage = "";
        });
        addRenderableWidget(testBuyItemField);
        if (testBuyItemFocused) setFocused(testBuyItemField);

        testBuyPriceField = new EditBox(this.font, innerX + itemWidth + 6, fieldY, 70, 20,
                Component.literal("Max price"));
        testBuyPriceField.setMaxLength(9);
        testBuyPriceField.setHint(Component.literal("max $"));
        testBuyPriceField.setValue(testBuyPriceDraft);
        testBuyPriceField.setResponder(value -> {
            testBuyPriceDraft = value.replaceAll("[^0-9]", "");
            testBuyMessage = "";
        });
        addRenderableWidget(testBuyPriceField);
        if (testBuyPriceFocused) setFocused(testBuyPriceField);

        testBuyButton = CockpitButton.builder(Component.literal("TEST BUY — REAL CLICKS"),
                        ignored -> handleTestBuy())
                .bounds(innerX, fieldY + 22, innerWidth, 20).build();
        boolean executable = !active && DRIVER.authorizedExecutionEnabled()
                && DRIVER.currentEnvironmentBlocker() == null;
        testBuyButton.active = executable
                && !testBuyItemDraft.isBlank() && !testBuyPriceDraft.isBlank();
        addRenderableWidget(testBuyButton);

        int sellY = fieldY + 46;
        testSellPriceField = new EditBox(this.font, innerX, sellY, 70, 20,
                Component.literal("Sell price"));
        testSellPriceField.setMaxLength(9);
        testSellPriceField.setHint(Component.literal("price $"));
        testSellPriceField.setValue(testSellPriceDraft);
        testSellPriceField.setResponder(value -> {
            testSellPriceDraft = value.replaceAll("[^0-9]", "");
            testBuyMessage = "";
        });
        addRenderableWidget(testSellPriceField);
        if (testSellPriceFocused) setFocused(testSellPriceField);

        testSellButton = CockpitButton.builder(Component.literal("TEST SELL — held stack"),
                        ignored -> handleTestSell())
                .bounds(innerX + 76, sellY, innerWidth - 76, 20).build();
        testSellButton.active = executable && !testSellPriceDraft.isBlank();
        addRenderableWidget(testSellButton);
    }

    /** Lists the stack in the selected hotbar slot at the typed price. */
    private void handleTestSell() {
        long price;
        try {
            price = Long.parseLong(testSellPriceDraft.strip());
        } catch (NumberFormatException e) {
            price = 0;
        }
        if (price <= 0) {
            testBuyMessage = "Type a whole-number sell price";
            testBuyMessageColor = WARN;
            rebuildWidgets();
            return;
        }
        ExecutionResult result = DRIVER.sellHeldStack(price);
        testBuyMessage = (result.ok() ? "Started: " : "Refused: ") + result.detail();
        testBuyMessageColor = result.ok() ? GOOD : WARN;
        driverMessage = result.detail();
        rebuildWidgets();
    }

    /**
     * Buys the cheapest scanned listing of the typed item at or under the typed
     * price. It exists to prove the click path on something cheap without
     * waiting for an opportunity; it makes no profit claim and opens no
     * position.
     */
    private void handleTestBuy() {
        String itemId = TestBuyPicker.normalizeItemId(testBuyItemDraft);
        long maxPrice;
        try {
            maxPrice = Long.parseLong(testBuyPriceDraft.strip());
        } catch (NumberFormatException e) {
            maxPrice = 0;
        }
        if (itemId.isBlank() || maxPrice <= 0) {
            testBuyMessage = "Type an item id and a whole-number max price";
            testBuyMessageColor = WARN;
            rebuildWidgets();
            return;
        }
        // Chosen on the auction screen itself, not from the API: the API's
        // listing feed lags by minutes and every API-sourced target was gone.
        // "dirt x64" pins the stack size. The page is re-read for up to
        // about a minute, because a qualifying row is usually one that
        // appears after the first look and is gone seconds later.
        ExecutionResult result = DRIVER.buyCheapestVisible(
                itemId, maxPrice, TestBuyPicker.requiredCount(testBuyItemDraft), 15);
        testBuyMessage = (result.ok() ? "Started: " : "Refused: ") + result.detail();
        testBuyMessageColor = result.ok() ? GOOD : WARN;
        driverMessage = result.detail();
        rebuildWidgets();
    }

    private void renderTestBuyCard(GuiGraphicsExtractor graphics, AutomationModeGeometry geometry) {
        int[] b = testBuyCardBounds(geometry);
        int x = b[0];
        int y = b[1];
        int width = b[2];
        int height = b[3];
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "TEST BUY / TEST SELL — REAL CLICKS", x, y, width);
        int innerX = x + 9;
        int innerWidth = width - 18;
        graphics.text(this.font,
                truncate("Buy: hunts the page ~1 min for a row under the price (add x64 for stacks). Sell: the stack in your hand.", innerWidth),
                innerX, y + 16, SECONDARY);
        int yy = y + 30 + 70;
        String message = testBuyMessage.isBlank()
                ? DRIVER.inspect().description() : testBuyMessage;
        int color = testBuyMessage.isBlank() ? SECONDARY : testBuyMessageColor;
        drawWrapped(graphics, message, innerX, yy, innerWidth, y + height - 8, color);
    }

    private void initSessionModeWidgets(MarketWatcher.Snapshot marketSnapshot,
                                        AutomationModeGeometry geometry,
                                        AutomationSessionController.RunMode runMode) {
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        int gap = 8;
        int innerX = geometry.left() + 9;
        int usable = geometry.width() - 18;
        int y = geometry.bottom() - 28;
        String startLabel = runMode == AutomationSessionController.RunMode.SINGLE
                ? geometry.compact() ? "Start 1" : "Start ONE complete trade"
                : geometry.compact() ? "Start" : "Start CONTINUOUS session";

        if (session.state() == AutomationSessionController.State.STOPPED
                && !session.unresolvedExposure()) {
            // Live trading switched on: Start, plus the way back to off beside it.
            boolean offButton = runMode == AutomationSessionController.RunMode.CONTINUOUS
                    && DoughBayClient.donutAutomationEnabled();
            int offWidth = offButton ? (geometry.compact() ? 70 : 132) : 0;
            automationSessionStartButton = CockpitButton.builder(
                            Component.literal(startLabel),
                            ignored -> handleSessionStart(runMode))
                    .bounds(innerX, y, usable - (offButton ? offWidth + gap : 0), 20).build();
            automationSessionStartButton.active = sessionStartBlocker(marketSnapshot) == null;
            addRenderableWidget(automationSessionStartButton);
            if (offButton) {
                addRenderableWidget(CockpitButton.builder(
                                Component.literal(geometry.compact() ? "Turn off" : "Turn off live trading"),
                                ignored -> handleLiveTradingOff())
                        .bounds(innerX + usable - offWidth, y, offWidth, 20).build());
            }
            return;
        }

        if (session.state() == AutomationSessionController.State.PAUSED) {
            if (session.recoveredSession()) {
                initRecoveredSessionWidgets(marketSnapshot, geometry, session, innerX, y, usable);
                return;
            }
            if (canResumeSession(session)) {
                automationSessionResumeButton = CockpitButton.builder(
                                Component.literal(geometry.compact()
                                        ? "Resume monitored position"
                                        : "Resume safely monitored position"),
                                ignored -> {
                                    ExecutionResult result = AUTOMATION_SESSION.resume();
                                    driverMessage = result.detail();
                                    rebuildWidgets();
                                })
                        .bounds(innerX, y, usable, 20).build();
                addRenderableWidget(automationSessionResumeButton);
            }
            // A non-resumable pause intentionally exposes no action that can
            // dismiss or hide unresolved financial exposure. The card tells
            // the player to inspect and resolve it manually.
            return;
        }

        int buttonWidth = (usable - gap) / 2;
        automationSessionStopButton = CockpitButton.builder(
                        Component.literal(geometry.compact()
                                ? "STOP SESSION" : "STOP SESSION — keep exposure visible"),
                        ignored -> {
                            AUTOMATION_SESSION.stop();
                            driverMessage = AUTOMATION_SESSION.snapshot().detail();
                            rebuildWidgets();
                        })
                .bounds(innerX, y, buttonWidth, 20).build();
        addRenderableWidget(automationSessionStopButton);

        automationEmergencyButton = CockpitButton.builder(
                        Component.literal(geometry.compact()
                                ? "E-STOP — END" : "EMERGENCY STOP — END"), ignored -> {
                            AUTOMATION_SESSION.emergencyStop();
                            driverMessage = AUTOMATION_SESSION.snapshot().detail();
                            rebuildWidgets();
                        })
                .bounds(innerX + buttonWidth + gap, y,
                        usable - buttonWidth - gap, 20).build();
        addRenderableWidget(automationEmergencyButton);
    }

    private void initRecoveredSessionWidgets(
            MarketWatcher.Snapshot marketSnapshot,
            AutomationModeGeometry geometry,
            AutomationSessionController.SessionSnapshot session,
            int x, int y, int width) {
        ContinuousAutomationPolicy policy = DoughBayClient.continuousPolicy();
        if (!session.unresolvedExposure()) {
            if ("SESSION_CLOSE_PENDING".equals(session.manualResolutionStage())) {
                automationRecoveryPrimaryButton = CockpitButton.builder(
                                Component.literal(geometry.compact()
                                        ? "Closing session…"
                                        : "Closing recovered session — waiting for durable write…"),
                                ignored -> {
                                })
                        .bounds(x, y, width, 20).build();
                automationRecoveryPrimaryButton.active = false;
                addRenderableWidget(automationRecoveryPrimaryButton);
                return;
            }
            int gap = 8;
            int leftWidth = (width - gap) / 2;
            automationRecoveryPrimaryButton = CockpitButton.builder(
                            Component.literal(geometry.compact()
                                    ? "Resume caps" : "Resume — KEEP restored caps"), ignored -> {
                                ExecutionResult result =
                                        AUTOMATION_SESSION.resumeRecoveredSession(policy);
                                driverMessage = result.detail();
                                rebuildWidgets();
                            })
                    .bounds(x, y, leftWidth, 20).build();
            automationRecoveryPrimaryButton.active = policy != null;
            addRenderableWidget(automationRecoveryPrimaryButton);
            automationRecoverySecondaryButton = CockpitButton.builder(
                            Component.literal(geometry.compact()
                                    ? "End session" : "End recovered session deliberately"), ignored -> {
                                ExecutionResult result = AUTOMATION_SESSION.endRecoveredSession();
                                driverMessage = result.detail();
                                rebuildWidgets();
                            })
                    .bounds(x + leftWidth + gap, y, width - leftWidth - gap, 20).build();
            addRenderableWidget(automationRecoverySecondaryButton);
            return;
        }

        if (AUTOMATION_SESSION.recoverableInHand()) {
            automationRecoveryPrimaryButton = CockpitButton.builder(Component.literal(geometry.compact()
                            ? "Resume: list the item" : "Resume — list the bought item in your inventory"), ignored -> {
                        ExecutionResult result = AUTOMATION_SESSION.resumeRecoveredSession(policy);
                        DoughBayClient.LOGGER.info("DoughBay resume with item in hand: {}", result.detail());
                        driverMessage = result.detail();
                        rebuildWidgets();
                    })
                    .bounds(x, y, width, 20).build();
            automationRecoveryPrimaryButton.active = policy != null;
            addRenderableWidget(automationRecoveryPrimaryButton);
            return;
        }
        String stage = session.manualResolutionStage();
        String label = switch (stage) {
            case "FIRST_CONFIRMATION_REQUIRED" -> geometry.compact()
                    ? "Step 1: inspected" : "STEP 1 — I inspected inventory + auction externally";
            case "EVIDENCE_SCANNING" -> "Waiting for two complete exact-book scans…";
            case "SECOND_CONFIRMATION_REQUIRED" -> geometry.compact()
                    ? "Step 2: confirm" : "STEP 2 — Confirm no item and no own listing";
            case "AUDIT_WRITE_PENDING" -> "Writing immutable recovery audit…";
            default -> "Recovery controls locked";
        };
        automationRecoveryPrimaryButton = CockpitButton.builder(Component.literal(label), ignored -> {
                    ExecutionResult result;
                    if ("FIRST_CONFIRMATION_REQUIRED".equals(
                            AUTOMATION_SESSION.snapshot().manualResolutionStage())) {
                        result = AUTOMATION_SESSION.beginManualResolution(
                                policy, this.minecraft, snapshot());
                    } else {
                        result = AUTOMATION_SESSION.confirmManualResolution(
                                this.minecraft, snapshot());
                    }
                    DoughBayClient.LOGGER.info("DoughBay recovery button: {}", result.detail());
                    driverMessage = result.detail();
                    rebuildWidgets();
                })
                .bounds(x, y, width, 20).build();
        automationRecoveryPrimaryButton.active = policy != null
                && ("FIRST_CONFIRMATION_REQUIRED".equals(stage)
                || "SECOND_CONFIRMATION_REQUIRED".equals(stage));
        addRenderableWidget(automationRecoveryPrimaryButton);
    }

    private void addAutomationCandidateButton(Opportunity current,
                                              AutomationModeGeometry geometry) {
        int width = geometry.compact()
                ? geometry.width() : Math.min(205, Math.max(145, geometry.width() / 4));
        int x = geometry.compact() ? geometry.left() : geometry.right() - 9 - width;
        int y = geometry.compact() ? geometry.bodyY() + 20 : geometry.bodyY() + 48;
        boolean rehearsing = current != null && current.stats() == null;
        String label = current == null
                ? "Choose a listing to preflight"
                : "View " + titleCase(shortName(current.listing().itemId()))
                + " x" + current.listing().itemCount();
        automationChooseButton = CockpitButton.builder(Component.literal(label), ignored -> {
                    MarketWatcher.Snapshot latest = snapshot();
                    Opportunity rebound = currentOpportunity(latest, automationCandidate);
                    if (rebound == null) {
                        // Nothing passing: rehearse against the cheapest live
                        // listing so the GUI check is available regardless.
                        rebound = rehearsalTarget(latest);
                        if (rebound != null) {
                            automationCandidate = rebound;
                            driverMessage = "Rehearsal target selected — reads the auction"
                                    + " screen only, this is not a valued trade";
                            rebuildWidgets();
                            return;
                        }
                    }
                    tab = Tab.OPPORTUNITIES;
                    selected = rebound;
                    scroll = 0;
                    pageScrollPixels = 0;
                    driverMessage = rebound == null && automationCandidate != null
                            ? "The saved candidate expired; choose a fresh opportunity"
                            : "";
                    rebuildWidgets();
                })
                .bounds(x, y, width, 20).build();
        addRenderableWidget(automationChooseButton);
    }

    private void addAutomationStatusButton(int x, int y, int width, String idleLabel) {
        boolean active = DRIVER.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE;
        String label = active ? "EMERGENCY STOP — END" : idleLabel;
        automationStopButton = CockpitButton.builder(Component.literal(label), ignored -> {
                    if (DRIVER.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) {
                        DRIVER.emergencyStop();
                        driverMessage = DRIVER.inspect().description();
                    } else {
                        driverMessage = DRIVER.clearStatus()
                                ? "Status cleared; all permission settings were unchanged"
                                : "Status cannot be cleared while an operation is active";
                    }
                    rebuildWidgets();
                })
                .bounds(x, y, width, 20).build();
        addRenderableWidget(automationStopButton);
    }

    private void handleAutomationPreflight() {
        MarketWatcher.Snapshot latest = snapshot();
        String blocker = preflightBlocker(latest, automationCandidate);
        if (blocker != null) {
            driverMessage = blocker;
            rebuildWidgets();
            return;
        }
        Opportunity rebound = currentOpportunity(latest, automationCandidate);
        if (rebound == null) {
            driverMessage = "Candidate expired; choose a fresh exact listing";
            rebuildWidgets();
            return;
        }
        automationCandidate = rebound;
        ExecutionResult result = DRIVER.startDryRun(rebound);
        driverMessage = result.detail();
        rebuildWidgets();
    }

    private void handleSessionStart(AutomationSessionController.RunMode runMode) {
        MarketWatcher.Snapshot latest = snapshot();
        String blocker = sessionStartBlocker(latest);
        if (blocker != null) {
            driverMessage = blocker;
            rebuildWidgets();
            return;
        }
        ContinuousAutomationPolicy policy = DoughBayClient.continuousPolicy();
        ExecutionResult result = AUTOMATION_SESSION.start(policy, runMode);
        driverMessage = result.detail();
        rebuildWidgets();
    }

    private String sessionStartBlocker(MarketWatcher.Snapshot marketSnapshot) {
        String configuredBlocker = DoughBayClient.continuousPolicyBlocker();
        if (configuredBlocker != null && !configuredBlocker.isBlank()) {
            return configuredBlocker;
        }
        ContinuousAutomationPolicy policy = DoughBayClient.continuousPolicy();
        if (policy == null) {
            return "Continuous automation safety policy is unavailable";
        }
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        if (!session.persistenceStatus().startsWith("READY")) {
            return session.persistenceStatus().isBlank()
                    ? "Automation recovery is not ready"
                    : session.persistenceStatus();
        }
        if (session.recoveredSession()) {
            return "Resume or deliberately end the recovered session; caps cannot reset";
        }
        if (session.state() != AutomationSessionController.State.STOPPED) {
            return session.state() == AutomationSessionController.State.PAUSED
                    ? "Session is PAUSED; resolve or resume it before starting another"
                    : "An automation session is already active";
        }
        if (session.unresolvedExposure()) {
            return "A previous session still has unresolved trade exposure";
        }
        if (DRIVER.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE
                || DRIVER.inspect().armed()) {
            return "The execution driver is already active";
        }
        String authorizationBlocker = continuousAuthorizationBlocker();
        if (authorizationBlocker != null) {
            return authorizationBlocker;
        }
        if (marketSnapshot == null || marketSnapshot.demo()) {
            return "A real market snapshot is required; demo data can never execute";
        }
        if (marketSnapshot.status() == null
                || marketSnapshot.status().startsWith("STOPPED")) {
            return "The market watcher is stopped";
        }
        if (!isFresh(marketSnapshot)) {
            return "The market snapshot is stale; wait for a fresh completed scan";
        }
        return null;
    }

    private String continuousAuthorizationBlocker() {
        DoughBayConfig config = watcher.config();
        if (config == null || !config.continuousAutomationEnabled()) {
            return "Continuous automation is disabled in config";
        }
        if (!config.auctionFeesConfirmed()) {
            return "Auction fees/taxes are not explicitly confirmed in config";
        }
        String driverBlocker = DRIVER.currentEnvironmentBlocker();
        if (driverBlocker != null && !driverBlocker.isBlank()) {
            return driverBlocker;
        }
        String server = currentServerIdentity(this.minecraft);
        if (server == null) {
            return "Current server identity is unavailable; continuous automation is locked";
        }
        boolean authorized = config.continuousAutomationServers().stream()
                .filter(Objects::nonNull)
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .anyMatch(server::equals);
        if (!authorized) {
            return "Server " + server + " is not in continuousAutomationServers";
        }
        boolean notificationTrusted = config.notificationServers().stream()
                .filter(Objects::nonNull)
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .anyMatch(server::equals);
        return notificationTrusted ? null
                : "Server " + server + " is not trusted for strict sale notifications";
    }

    private static String currentServerIdentity(Minecraft client) {
        if (client == null || client.player == null || client.getConnection() == null) return null;
        if (client.isLocalServer() || client.hasSingleplayerServer()) return "singleplayer";
        var server = client.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) return null;
        return server.ip.strip().toLowerCase(Locale.ROOT);
    }

    private static AutomationMode pinnedAutomationMode(
            AutomationSessionController.SessionSnapshot session,
            AutomatedExecutionDriver.OperationIntent intent) {
        return AutomationMode.CONTINUOUS;
    }

    private static boolean canResumeSession(
            AutomationSessionController.SessionSnapshot session) {
        if (session.state() != AutomationSessionController.State.PAUSED) return false;
        return session.resumable();
    }

    private void renderAutomation(GuiGraphicsExtractor graphics,
                                  MarketWatcher.Snapshot snapshot, UiLayout ui) {
        resetListGeometry();
        AutomationModeGeometry geometry = automationModeGeometry(ui);
        Opportunity current = currentOpportunity(snapshot, automationCandidate);
        AutomatedExecutionDriver.OperationIntent intent = DRIVER.operationIntent();
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();

        graphics.text(this.font, "AUTOMATION",
                geometry.left(), geometry.titleY(), GOLD);
        if (!geometry.compact()) {
            String hint = "Continuous: buys market signals, relists at target, tracks your auction slots";
            graphics.text(this.font, truncate(hint,
                            Math.max(80, geometry.modeX() - geometry.left() - 12)),
                    geometry.left() + this.font.width("AUTOMATION") + 12,
                    geometry.titleY(), MUTED);
        }
        if (!geometry.compact()) {
            String intentText = session.state() != AutomationSessionController.State.STOPPED
                    ? "session " + session.state().name().toLowerCase(Locale.ROOT)
                    : intent == AutomatedExecutionDriver.OperationIntent.NONE
                    ? "driver idle" : "active " + intent.name().toLowerCase(Locale.ROOT);
            graphics.text(this.font, truncate(intentText, geometry.width()),
                    geometry.left(), geometry.titleY() + 12,
                    session.state() == AutomationSessionController.State.PAUSED ? BAD
                            : session.active() || intent != AutomatedExecutionDriver.OperationIntent.NONE
                            ? WARN : MUTED);
        }

        switch (automationMode) {
            case TOOLS -> {
                renderTestBuyCard(graphics, geometry);
                renderApiKeyCard(graphics, apiKeyGeometry(geometry));
            }
            case DISCORD -> renderDiscordCard(graphics, geometry);
            case CONTINUOUS -> {
                if (showLiveTradingSetup()) {
                    renderLiveTradingSetup(graphics, snapshot, geometry);
                } else {
                    renderContinuousAutomation(graphics, snapshot, geometry);
                }
            }
        }
    }

    private void renderCompactAutomation(GuiGraphicsExtractor graphics,
                                         MarketWatcher.Snapshot snapshot,
                                         Opportunity current,
                                         AutomatedExecutionDriver.PreflightReport report,
                                         AutomationGeometry geometry) {
        int x = geometry.left();
        int width = geometry.width();
        int yy = geometry.candidateY();

        String candidate = current == null
                ? automationCandidate == null ? "Candidate: none selected"
                : "Candidate: expired — choose a fresh listing"
                : "Candidate: " + titleCase(shortName(current.listing().itemId()))
                + " x" + current.listing().itemCount() + " at " + money(current.buyPrice());
        graphics.text(this.font, truncate(candidate, width), x, yy,
                current == null ? WARN : TEXT);

        int statusY = Math.max(geometry.stopY() + 24, yy + 106);
        int bottom = geometry.bottom();
        if (statusY <= bottom - this.font.lineHeight) {
            String gate = preflightBlocker(snapshot, automationCandidate);
            String line = report.outcome() != AutomatedExecutionDriver.PreflightOutcome.NOT_RUN
                    ? "Preflight " + report.outcome().name().toLowerCase(Locale.ROOT)
                    + ": " + report.detail()
                    : gate == null ? "Preflight ready: one search, exact verification, zero clicks"
                    : "Preflight locked: " + gate;
            graphics.text(this.font, truncate(line, width), x, statusY,
                    report.outcome() == AutomatedExecutionDriver.PreflightOutcome.PASSED
                            ? GOOD : gate == null ? SECONDARY : WARN);
        }
        if (statusY + 12 <= bottom - this.font.lineHeight) {
            String event = DRIVER.inspect().description();
            graphics.text(this.font, truncate("Last event: " + event, width),
                    x, statusY + 12, MUTED);
        }
    }

    private void renderAutomationCandidateCard(GuiGraphicsExtractor graphics,
                                               MarketWatcher.Snapshot snapshot,
                                               Opportunity current,
                                               AutomationGeometry geometry) {
        int x = geometry.left();
        int y = geometry.candidateY();
        int width = geometry.width();
        int height = geometry.candidateHeight();
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "ONE-SHOT CANDIDATE", x, y, width);

        int yy = y + 25;
        int textRight = geometry.chooseX() - 12;
        int textWidth = Math.max(70, textRight - (x + 9));
        if (automationCandidate == null) {
            graphics.text(this.font,
                    truncate("No listing selected. Choose a completed-sales-backed opportunity first.",
                            textWidth), x + 9, yy, MUTED);
        } else if (current == null) {
            graphics.text(this.font,
                    truncate("Saved listing expired or changed — commands remain locked.", textWidth),
                    x + 9, yy, WARN);
            graphics.text(this.font,
                    truncate(titleCase(shortName(automationCandidate.listing().itemId()))
                                    + " x" + automationCandidate.listing().itemCount(), textWidth),
                    x + 9, yy + 12, SECONDARY);
        } else {
            String name = titleCase(shortName(current.listing().itemId()))
                    + " x" + current.listing().itemCount();
            graphics.text(this.font, truncate(name, textWidth), x + 9, yy, TEXT);
            String valuation = "Buy " + money(current.buyPrice())
                    + "  •  completed-sale target " + money(current.recommendedSellPrice())
                    + "  •  " + (current.stats() == null ? 0 : current.stats().sampleCount()) + " completed samples";
            graphics.text(this.font, truncate(valuation, textWidth), x + 9, yy + 12, GOOD);
            String identity = "Seller " + current.listing().sellerName()
                    + "  •  exact listing " + current.listing().listingKey();
            graphics.text(this.font, truncate(identity, textWidth), x + 9, yy + 24, SECONDARY);
        }

        String badge = current == null ? "NOT READY" : "FRESH EXACT";
        int badgeColor = current == null ? WARN : GOOD;
        graphics.text(this.font, badge,
                x + width - 9 - this.font.width(badge), y + 7, badgeColor);
    }

    private void renderPreflightCard(GuiGraphicsExtractor graphics,
                                     MarketWatcher.Snapshot snapshot,
                                     Opportunity current,
                                     AutomatedExecutionDriver.PreflightReport report,
                                     AutomationGeometry geometry) {
        int x = geometry.preflightCardX();
        int y = geometry.middleY();
        int width = geometry.columnWidth();
        int height = geometry.middleHeight();
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "READ-ONLY ONE-SHOT PREFLIGHT", x, y, width);
        int yy = y + 25;
        int innerX = x + 9;
        int innerWidth = width - 18;

        yy = statusLine(graphics, innerX, yy, innerWidth,
                "1  Send one /ah search command", SECONDARY);
        yy = statusLine(graphics, innerX, yy, innerWidth,
                "2  Wait for a stable auction results GUI", SECONDARY);
        yy = statusLine(graphics, innerX, yy, innerWidth,
                "3  Verify ID, stack, seller, and lore price", SECONDARY);
        yy = statusLine(graphics, innerX, yy, innerWidth,
                "4  Capture evidence and STOP — zero clicks", GOOD);

        yy += 3;
        yy = kvRight(graphics, innerX, yy, innerWidth, "Preflight config",
                DRIVER.preflightEnabled() ? "enabled" : "disabled",
                DRIVER.preflightEnabled() ? GOOD : WARN);
        String environment = DRIVER.currentPreflightBlocker();
        yy = kvRight(graphics, innerX, yy, innerWidth, "Exact server gate",
                environment == null ? "allowed" : "locked",
                environment == null ? GOOD : WARN);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Fresh exact candidate",
                current == null ? "no" : "yes", current == null ? WARN : GOOD);
        kvRight(graphics, innerX, yy, innerWidth, "Last result",
                report.outcome().name().toLowerCase(Locale.ROOT),
                preflightOutcomeColor(report.outcome()));
    }

    private void renderAutomationReportCard(GuiGraphicsExtractor graphics,
                                            AutomatedExecutionDriver.PreflightReport report,
                                            AutomationGeometry geometry) {
        int x = geometry.left();
        int y = geometry.reportY();
        int width = geometry.width();
        int height = geometry.reportHeight();
        card(graphics, x, y, width, height, CARD_DARK);
        sectionTitle(graphics, "CURRENT OPERATION / LAST PREFLIGHT REPORT", x, y, width);

        int innerX = x + 9;
        int innerWidth = width - 18;
        int maxY = y + height - 31;
        int yy = y + 25;
        ExecutionStatus status = DRIVER.inspect();
        String intent = DRIVER.operationIntent().name().toLowerCase(Locale.ROOT);
        yy = statusLine(graphics, innerX, yy, innerWidth,
                "Active intent: " + intent + "  •  driver "
                        + status.state().name().toLowerCase(Locale.ROOT),
                DRIVER.operationIntent() == AutomatedExecutionDriver.OperationIntent.NONE
                        ? SECONDARY : WARN);

        boolean wideChecks = width >= 760;
        int eventWidth = wideChecks ? Math.max(90, width / 2 - 22) : innerWidth;
        String event;
        if (DRIVER.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) {
            event = status.description();
        } else if (report.outcome() != AutomatedExecutionDriver.PreflightOutcome.NOT_RUN) {
            event = report.detail();
        } else {
            event = !driverMessage.isBlank() ? driverMessage : status.description();
        }
        yy = drawWrapped(graphics, "Last event: " + event,
                innerX, yy, eventWidth, maxY, MUTED);

        List<AutomatedExecutionDriver.Check> checks = report.checks();
        if (checks.isEmpty()
                && DRIVER.operationIntent() == AutomatedExecutionDriver.OperationIntent.PREFLIGHT) {
            checks = DRIVER.verifyAgainstOpenScreen();
        }
        if (!checks.isEmpty() && yy <= maxY - 9) {
            int checkX = wideChecks ? x + width / 2 : innerX;
            int checkWidth = wideChecks ? x + width - 9 - checkX : innerWidth;
            int checkY = wideChecks ? y + 37 : yy + 2;
            for (AutomatedExecutionDriver.Check check : checks) {
                if (checkY > maxY - 9) break;
                int color = switch (check.status()) {
                    case MATCH -> GOOD;
                    case MISMATCH -> BAD;
                    case UNKNOWN -> MUTED;
                };
                String mark = switch (check.status()) {
                    case MATCH -> "✓ ";
                    case MISMATCH -> "× ";
                    case UNKNOWN -> "• ";
                };
                checkY = statusLine(graphics, checkX, checkY, checkWidth,
                        mark + check.label() + ": " + check.observed(), color);
            }
        }

        java.nio.file.Path capture = report.diagnosticsPath() != null
                ? report.diagnosticsPath() : DRIVER.lastCapturePath();
        if (capture != null && yy <= maxY - 9) {
            graphics.text(this.font,
                    truncate("Evidence: " + capture.getFileName(), innerWidth),
                    innerX, maxY - 9, Charts.SERIES_AQUA);
        }
    }

    private AutomationGeometry automationGeometry(UiLayout ui) {
        int top = ui.contentTop() + 8;
        int bottom = ui.bottom() - 8;
        int width = ui.innerWidth();
        boolean compact = width < 680 || bottom - top < 330;
        if (compact) {
            int candidateY = top + 18;
            int buttonY = candidateY + 13;
            int step = 25;
            return new AutomationGeometry(true, ui.innerLeft(), top, bottom, width, 0,
                    candidateY, 0, 0, 0, 0, 0, 0,
                    ui.innerLeft(), buttonY, width,
                    ui.innerLeft(), buttonY + step, width,
                    ui.innerLeft(), buttonY + step * 2, width,
                    ui.innerLeft(), buttonY + step * 3, width);
        }

        int gap = 8;
        int candidateY = top + 18;
        int candidateHeight = 84;
        int middleY = candidateY + candidateHeight + gap;
        int remaining = bottom - middleY;
        int middleHeight = clamp((int) (remaining * 0.56), 150, 186);
        int reportY = middleY + middleHeight + gap;
        int reportHeight = bottom - reportY;
        if (reportHeight < 92) {
            middleHeight -= 92 - reportHeight;
            reportY = middleY + middleHeight + gap;
            reportHeight = bottom - reportY;
        }
        int columnWidth = (width - gap) / 2;
        int buyX = ui.innerLeft() + columnWidth + gap;
        int chooseWidth = Math.min(195, Math.max(140, width / 5));
        int chooseX = ui.innerRight() - 9 - chooseWidth;
        int stopWidth = Math.min(180, Math.max(130, width / 5));
        return new AutomationGeometry(false, ui.innerLeft(), top, bottom, width, gap,
                candidateY, candidateHeight, middleY, middleHeight,
                reportY, reportHeight, columnWidth,
                chooseX, candidateY + candidateHeight - 28, chooseWidth,
                ui.innerLeft() + 9, middleY + middleHeight - 28, columnWidth - 18,
                buyX + 9, middleY + middleHeight - 28,
                width - columnWidth - gap - 18,
                ui.innerLeft() + 9, reportY + reportHeight - 28, stopWidth);
    }

    private record AutomationGeometry(
            boolean compact,
            int left,
            int titleY,
            int bottom,
            int width,
            int gap,
            int candidateY,
            int candidateHeight,
            int middleY,
            int middleHeight,
            int reportY,
            int reportHeight,
            int columnWidth,
            int chooseX,
            int chooseY,
            int chooseWidth,
            int preflightX,
            int preflightY,
            int preflightWidth,
            int buyX,
            int buyY,
            int buyWidth,
            int stopX,
            int stopY,
            int stopWidth
    ) {
        int preflightCardX() {
            return left;
        }

        int buyCardX() {
            return left + columnWidth + gap;
        }
    }

    private static int preflightOutcomeColor(AutomatedExecutionDriver.PreflightOutcome outcome) {
        return switch (outcome) {
            case PASSED -> GOOD;
            case RUNNING -> WARN;
            case FAILED, REFUSED -> BAD;
            case NOT_RUN -> MUTED;
        };
    }

    // The mode-specific layout below intentionally sits behind one narrow
    // boundary. AutomationSessionController can replace the CONTINUOUS helper
    // without disturbing the mature one-shot driver or any other tab.

    private AutomationModeGeometry automationModeGeometry(UiLayout ui) {
        int top = ui.contentTop() + 8;
        int bottom = ui.bottom() - 8;
        int width = ui.innerWidth();
        boolean compact = width < 680 || bottom - top < 330;
        int modeWidth = compact ? width : Math.min(230, Math.max(175, width / 4));
        int modeX = compact ? ui.innerLeft() : ui.innerRight() - modeWidth;
        int modeY = compact ? top + 14 : top;
        int bodyY = compact ? top + 42 : top + 30;
        return new AutomationModeGeometry(compact, ui.innerLeft(), top, bottom,
                width, modeX, modeY, modeWidth, bodyY, 8);
    }

    private ModeActionGeometry modeActionGeometry(AutomationModeGeometry geometry) {
        if (geometry.compact()) {
            return new ModeActionGeometry(
                    geometry.left(), geometry.bodyY() + 45, geometry.width(),
                    geometry.left(), geometry.bodyY() + 70, geometry.width());
        }
        int column = (geometry.width() - geometry.gap()) / 2;
        return new ModeActionGeometry(
                geometry.left() + 9, geometry.bottom() - 28, column - 18,
                geometry.left() + column + geometry.gap() + 9,
                geometry.bottom() - 28,
                geometry.width() - column - geometry.gap() - 18);
    }

    private void renderObserveAutomation(GuiGraphicsExtractor graphics,
                                         MarketWatcher.Snapshot snapshot,
                                         Opportunity current,
                                         AutomationModeGeometry geometry) {
        ApiKeyGeometry key = apiKeyGeometry(geometry);
        int bodyY = geometry.bodyY();
        int height = Math.max(60, key.y() - geometry.gap() - bodyY);
        if (geometry.compact()) {
            renderCompactObserveCard(graphics, snapshot, current,
                    geometry.left(), bodyY, geometry.width(), height);
        } else {
            int column = (geometry.width() - geometry.gap()) / 2;
            renderObserveAnalysisCard(graphics, snapshot, geometry.left(), bodyY, column, height);
            renderObserveSessionCard(graphics, current,
                    geometry.left() + column + geometry.gap(), bodyY,
                    geometry.width() - column - geometry.gap(), height);
        }
        renderApiKeyCard(graphics, key);
    }

    // ------------------------------------------------------ live trading setup

    /**
     * Whether the Autopilot tab shows the one-step setup card in place of the
     * session controls. Only while live trading is off and nothing is in
     * flight: a paused or recovered session always gets its normal controls,
     * so the card can never hide unresolved exposure.
     */
    private static boolean showLiveTradingSetup() {
        if (DoughBayClient.donutAutomationEnabled()) return false;
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        return session.state() == AutomationSessionController.State.STOPPED
                && !session.unresolvedExposure()
                && DRIVER.operationIntent() == AutomatedExecutionDriver.OperationIntent.NONE;
    }

    /** {cardX, cardY, cardWidth, cardHeight, fieldY, fieldWidth}, shared by init and render. */
    private int[] liveTradingSetupBounds(AutomationModeGeometry geometry) {
        int x = geometry.left();
        int y = geometry.bodyY();
        int width = geometry.width();
        int cardBottom = geometry.bottom() - 36;
        int fieldY = cardBottom - 29;
        int fieldWidth = Math.min(170, (width - 18 - 8) / 2);
        return new int[] {x, y, width, cardBottom - y, fieldY, fieldWidth};
    }

    private void initLiveTradingSetupWidgets(AutomationModeGeometry geometry) {
        DoughBayConfig config = DoughBayClient.activeConfig();
        if (setupPerTradeDraft == null) {
            long cap = config != null ? config.continuousMaxPurchasePrice() : 0;
            setupPerTradeDraft = MoneyInput.compact(cap > 0 ? cap : 5_000_000L);
        }
        if (setupPerSessionDraft == null) {
            long cap = config != null ? config.continuousMaxSessionSpend() : 0;
            setupPerSessionDraft = MoneyInput.compact(cap > 0 ? cap : 75_000_000L);
        }
        int[] b = liveTradingSetupBounds(geometry);
        int innerX = b[0] + 9;

        setupPerTradeField = new EditBox(this.font, innerX, b[4], b[5], 20,
                Component.literal("Max per trade"));
        setupPerTradeField.setMaxLength(16);
        setupPerTradeField.setHint(Component.literal("e.g. 5m"));
        setupPerTradeField.setValue(setupPerTradeDraft);
        setupPerTradeField.setResponder(value -> {
            setupPerTradeDraft = value;
            setupMessage = "";
            setupConfirmUntil = 0;
        });
        addRenderableWidget(setupPerTradeField);
        if (setupPerTradeFocused) setFocused(setupPerTradeField);

        setupPerSessionField = new EditBox(this.font, innerX + b[5] + 8, b[4], b[5], 20,
                Component.literal("Max per session"));
        setupPerSessionField.setMaxLength(16);
        setupPerSessionField.setHint(Component.literal("e.g. 75m"));
        setupPerSessionField.setValue(setupPerSessionDraft);
        setupPerSessionField.setResponder(value -> {
            setupPerSessionDraft = value;
            setupMessage = "";
            setupConfirmUntil = 0;
        });
        addRenderableWidget(setupPerSessionField);
        if (setupPerSessionFocused) setFocused(setupPerSessionField);

        boolean confirming = System.currentTimeMillis() < setupConfirmUntil;
        String label = confirming
                ? (geometry.compact() ? "Click again to confirm" : "Click again to confirm: turn on live trading")
                : (geometry.compact() ? "Enable live trading" : "Enable live trading on DonutSMP");
        addRenderableWidget(CockpitButton.builder(Component.literal(label),
                        ignored -> handleLiveTradingEnable())
                .bounds(innerX, geometry.bottom() - 28, geometry.width() - 18, 20).build());
    }

    private void renderLiveTradingSetup(GuiGraphicsExtractor graphics,
                                        MarketWatcher.Snapshot snapshot,
                                        AutomationModeGeometry geometry) {
        int[] b = liveTradingSetupBounds(geometry);
        int x = b[0];
        int y = b[1];
        int width = b[2];
        card(graphics, x, y, width, b[3], CARD_DARK);
        sectionTitle(graphics, "SET UP LIVE TRADING ON DONUTSMP", x, y, width);
        int innerX = x + 9;
        int innerWidth = width - 18;
        int yy = y + 27;

        DoughBayConfig config = DoughBayClient.activeConfig();
        String server = currentServerIdentity(Minecraft.getInstance());
        boolean onDonut = server != null && server.contains("donutsmp");
        yy = kvRight(graphics, innerX, yy, innerWidth, "Connected to DonutSMP",
                onDonut ? "yes (" + server + ")"
                        : server == null ? "not in a server yet" : "no, you're on " + server,
                onDonut ? GOOD : WARN);
        boolean key = config != null && config.hasApiKey();
        yy = kvRight(graphics, innerX, yy, innerWidth, "Donut API key",
                key ? "saved" : "missing: add it under Tools (top right)", key ? GOOD : BAD);
        boolean live = !snapshot.demo() && isFresh(snapshot);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Market data",
                live ? "live" : snapshot.demo() ? "demo until a key is saved" : "loading",
                live ? GOOD : WARN);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Live trading", "off", MUTED);

        int labelsY = b[4] - 11;
        int textBottom = labelsY - 4;
        if (!setupMessage.isBlank()) {
            yy = drawWrapped(graphics, setupMessage, innerX, yy + 4, innerWidth, textBottom,
                    setupMessageColor);
        }
        drawWrapped(graphics,
                "Turn it on and GoNuts buys underpriced items and relists them for you, never spending past "
                        + "the limits below. Stop any time with Stop, E-stop or B. DonutSMP's rules ban bots, "
                        + "so running this is a ban risk you are choosing to take.",
                innerX, yy + 4, innerWidth, textBottom, SECONDARY);
        graphics.text(this.font, "Max per trade", innerX, labelsY, MUTED);
        graphics.text(this.font, "Max per session", innerX + b[5] + 8, labelsY, MUTED);
    }

    private void handleLiveTradingEnable() {
        long perTrade = MoneyInput.parse(setupPerTradeDraft);
        long perSession = MoneyInput.parse(setupPerSessionDraft);
        if (perTrade <= 0 || perSession <= 0) {
            setupMessage = "Enter both limits, like 5m and 75m";
            setupMessageColor = WARN;
            setupConfirmUntil = 0;
            rebuildWidgets();
            return;
        }
        if (perTrade > perSession) {
            setupMessage = "The per-trade limit can't be more than the per-session limit";
            setupMessageColor = WARN;
            setupConfirmUntil = 0;
            rebuildWidgets();
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= setupConfirmUntil) {
            // First press: arm, never act. A second press inside the window is
            // the deliberate opt-in that actually widens what GoNuts may do.
            setupConfirmUntil = now + 8_000L;
            setupMessage = "This lets GoNuts buy and relist for you on DonutSMP, up to "
                    + money(perTrade) + " a trade and " + money(perSession) + " a session. Click again to confirm.";
            setupMessageColor = WARN;
            rebuildWidgets();
            return;
        }
        setupConfirmUntil = 0;
        String failure = DoughBayClient.setDonutAutomation(true, perTrade, perSession,
                currentServerIdentity(Minecraft.getInstance()));
        if (!failure.isBlank()) {
            setupMessage = failure;
            setupMessageColor = BAD;
            rebuildWidgets();
            return;
        }
        setupMessage = "";
        driverMessage = "Live trading is on. Press Start when you're ready.";
        rebuildWidgets();
    }

    private void handleLiveTradingOff() {
        String failure = DoughBayClient.setDonutAutomation(false, 0, 0, null);
        driverMessage = failure.isBlank()
                ? "Live trading is off. Nothing will be bought or listed." : failure;
        setupPerTradeDraft = null;
        setupPerSessionDraft = null;
        rebuildWidgets();
    }

    // ------------------------------------------------------------- api key card

    // Collection credentials live in Observe because entering a key grants no
    // execution permission whatsoever: it only lets the read-only collector
    // authenticate. Every command gate stays in config.json, loaded at startup.

    private record ApiKeyGeometry(int x, int y, int width, int height,
                                  int sourceX, int sourceY, int sourceWidth,
                                  int fieldX, int fieldY, int fieldWidth,
                                  int saveX, int saveY, int saveWidth,
                                  int testX, int testY, int testWidth,
                                  int statusY) {
    }

    private ApiKeyGeometry apiKeyGeometry(AutomationModeGeometry geometry) {
        boolean stacked = geometry.compact() || geometry.width() < 470;
        // Sized for two wrapped status lines: several messages run long on a
        // narrow panel and silently truncating a failure reason is useless.
        // The extra 22px is the source-toggle row above the key field.
        int height = stacked ? 118 : 94;
        int x = geometry.left();
        int width = geometry.width();
        int y = geometry.bottom() - height;
        int innerX = x + 9;
        int innerWidth = width - 18;
        int sourceY = y + 22;
        int fieldY = sourceY + 22;

        if (stacked) {
            int half = (innerWidth - 6) / 2;
            return new ApiKeyGeometry(x, y, width, height,
                    innerX, sourceY, innerWidth,
                    innerX, fieldY, innerWidth,
                    innerX, fieldY + 23, half,
                    innerX + half + 6, fieldY + 23, innerWidth - half - 6,
                    fieldY + 48);
        }
        int saveWidth = 56;
        int testWidth = 108;
        int fieldWidth = Math.max(90, innerWidth - saveWidth - testWidth - 12);
        return new ApiKeyGeometry(x, y, width, height,
                innerX, sourceY, innerWidth,
                innerX, fieldY, fieldWidth,
                innerX + fieldWidth + 6, fieldY, saveWidth,
                innerX + fieldWidth + saveWidth + 12, fieldY, testWidth,
                fieldY + 24);
    }

    private void initApiKeyWidgets(AutomationModeGeometry geometry) {
        ApiKeyGeometry key = apiKeyGeometry(geometry);
        DoughBayConfig config = DoughBayClient.activeConfig();
        boolean saved = config != null && config.hasApiKey();
        boolean testing = API_KEY_TESTER.report().running();

        // The source is chosen once from what the config is already using, then
        // owned by the toggle for the rest of the session so a rebuild does not
        // snap it back.
        if (!apiKeySourceInit) {
            apiKeySourceProxy = config != null && config.usesOwnApi();
            apiKeySourceInit = true;
        }
        boolean proxy = apiKeySourceProxy;

        apiKeySourceButton = CockpitButton.builder(
                        Component.literal(proxy ? "Key type: Server  (a shared server key)"
                                : "Key type: Donut  (your own API key)"),
                        ignored -> { apiKeySourceProxy = !apiKeySourceProxy; apiKeyMessage = ""; rebuildWidgets(); })
                .bounds(key.sourceX(), key.sourceY(), key.sourceWidth(), 18).build();
        apiKeySourceButton.active = !testing;
        addRenderableWidget(apiKeySourceButton);

        apiKeyField = new EditBox(this.font, key.fieldX(), key.fieldY(),
                key.fieldWidth(), 20, Component.literal(proxy ? "Server key" : "Donut API key"));
        apiKeyField.setMaxLength(API_KEY_MAX_INPUT);
        apiKeyField.setHint(Component.literal(
                saved ? "Key saved — type to replace"
                        : proxy ? "Paste your server key" : "Paste your Donut API key"));
        // The value is stored in full but never drawn: the formatter replaces
        // every character so a stream, screenshot, or shoulder cannot read it.
        apiKeyField.addFormatter((text, offset) -> FormattedCharSequence.forward(
                "*".repeat(text.length()), Style.EMPTY));
        apiKeyField.setValue(apiKeyDraft);
        // Attached after setValue so restoring the draft across a rebuild does
        // not itself clear the status message.
        apiKeyField.setResponder(value -> {
            apiKeyDraft = value;
            apiKeyMessage = "";
        });
        addRenderableWidget(apiKeyField);
        if (apiKeyFieldFocused) setFocused(apiKeyField);

        apiKeySaveButton = CockpitButton.builder(Component.literal("Save"),
                        ignored -> handleApiKeySave())
                .bounds(key.saveX(), key.saveY(), key.saveWidth(), 20).build();
        apiKeySaveButton.active = !apiKeyDraft.isBlank() && !testing;
        addRenderableWidget(apiKeySaveButton);

        apiKeyTestButton = CockpitButton.builder(
                        Component.literal(testing ? "Testing..." : "Test history"),
                        ignored -> handleApiKeyTest())
                .bounds(key.testX(), key.testY(), key.testWidth(), 20).build();
        apiKeyTestButton.active = !testing && (saved || !apiKeyDraft.isBlank());
        addRenderableWidget(apiKeyTestButton);
    }

    private void renderApiKeyCard(GuiGraphicsExtractor graphics, ApiKeyGeometry key) {
        card(graphics, key.x(), key.y(), key.width(), key.height(), CARD_DARK);
        DoughBayConfig config = DoughBayClient.activeConfig();
        boolean saved = config != null && config.hasApiKey();
        boolean environment = DoughBayConfig.apiKeyOverriddenByEnvironment();

        sectionTitle(graphics, environment ? "API KEY — ENVIRONMENT OVERRIDE"
                : saved ? "API KEY — SAVED" : "API KEY — NOT SET",
                key.x(), key.y(), key.width());

        int innerX = key.x() + 9;
        int innerWidth = key.width() - 18;
        String status;
        int color;
        if (!apiKeyMessage.isBlank()) {
            status = apiKeyMessage;
            color = apiKeyMessageColor;
        } else if (environment) {
            status = "DONUT_API_KEY is set in the environment and wins over anything saved here.";
            color = WARN;
        } else {
            ApiKeyTester.Report report = API_KEY_TESTER.report();
            status = switch (report.state()) {
                case IDLE -> saved
                        ? "Saved and reused at every launch. Test history to confirm it still works."
                        : apiKeySourceProxy
                        ? "Paste your minted server key, then Save — data comes from your server, no Donut key needed."
                        : "Paste the key from /apikey in game, then Save. Collection needs it to start.";
                default -> report.detail();
            };
            color = switch (report.state()) {
                case PASSED -> GOOD;
                case FAILED -> BAD;
                case SUSPECT -> WARN;
                case RUNNING -> SECONDARY;
                case IDLE -> saved ? SECONDARY : WARN;
            };
        }
        drawWrapped(graphics, status, innerX, key.statusY(), innerWidth,
                key.y() + key.height() - 4, color);
    }

    private void handleApiKeySave() {
        String candidate = apiKeyDraft.strip();
        if (candidate.isBlank()) {
            apiKeyMessage = "Enter a key first";
            apiKeyMessageColor = WARN;
            rebuildWidgets();
            return;
        }
        String source;
        if (apiKeySourceProxy) {
            // A shared-server key is validated and served by whatever server the
            // config points at (apiBaseUrl), so it works for anyone running their
            // own DoughBay server for a hive or clan - no address is baked in.
            DoughBayConfig config = DoughBayClient.activeConfig();
            source = config == null ? "" : config.apiBaseUrl();
            if (!MarketWatcher.usingProxy(source)) {
                apiKeyMessage = "Set your server address first (apiBaseUrl in config.json)";
                apiKeyMessageColor = WARN;
                rebuildWidgets();
                return;
            }
        } else {
            source = dev.doughbay.api.DonutApiConfig.DEFAULT_BASE_URL;
        }
        String failure = DoughBayClient.applyApiKey(candidate, source);
        if (!failure.isBlank()) {
            apiKeyMessage = failure;
            apiKeyMessageColor = BAD;
            rebuildWidgets();
            return;
        }
        // Drop the plaintext draft as soon as it is on disk, so the field
        // cannot hand the key back to anything else on this screen.
        apiKeyDraft = "";
        apiKeyFieldFocused = false;
        API_KEY_TESTER.reset();
        apiKeyMessage = DoughBayConfig.apiKeyOverriddenByEnvironment()
                ? "Saved, but DONUT_API_KEY in the environment still wins until you clear it"
                : "Key saved. Collection restarted — run Test history to confirm it works.";
        apiKeyMessageColor = DoughBayConfig.apiKeyOverriddenByEnvironment() ? WARN : GOOD;
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- discord

    private EditBox discordTokenField;
    private EditBox discordChannelField;
    private EditBox discordOperatorField;
    private EditBox discordGuildField;
    private String discordTokenDraft = "";
    private String discordChannelDraft;
    private String discordOperatorDraft;
    private String discordGuildDraft;
    private String discordMessage = "";
    private int discordMessageColor = SECONDARY;

    private void initDiscordWidgets(AutomationModeGeometry geometry) {
        dev.doughbay.fabric.discord.DiscordBridge bridge = DoughBayClient.discord();
        dev.doughbay.fabric.discord.DiscordConfig cfg = bridge == null
                ? dev.doughbay.fabric.discord.DiscordConfig.empty() : bridge.config();
        if (discordChannelDraft == null) discordChannelDraft = cfg.channelId();
        if (discordOperatorDraft == null) discordOperatorDraft = cfg.operatorId();
        if (discordGuildDraft == null) discordGuildDraft = cfg.guildId();
        int x = geometry.left() + 9;
        int width = geometry.width() - 18;
        int y = geometry.bodyY() + 26;
        int half = (width - 6) / 2;

        discordTokenField = new EditBox(this.font, x, y, width - 62, 20, Component.literal("Bot token"));
        discordTokenField.setMaxLength(120);
        discordTokenField.setHint(Component.literal(cfg.token().isBlank() ? "Bot token" : "Token saved; type to replace"));
        discordTokenField.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        discordTokenField.setValue(discordTokenDraft);
        discordTokenField.setResponder(v -> discordTokenDraft = v);
        addRenderableWidget(discordTokenField);
        addRenderableWidget(CockpitButton.builder(Component.literal("Save"), ignored -> handleDiscordSave())
                .bounds(x + width - 56, y, 56, 20).build());

        y += 24;
        discordChannelField = new EditBox(this.font, x, y, half, 20, Component.literal("Channel id"));
        discordChannelField.setMaxLength(32);
        discordChannelField.setHint(Component.literal("Channel id"));
        discordChannelField.setValue(discordChannelDraft);
        discordChannelField.setResponder(v -> discordChannelDraft = v);
        addRenderableWidget(discordChannelField);
        discordOperatorField = new EditBox(this.font, x + half + 6, y, width - half - 6, 20, Component.literal("Your user id"));
        discordOperatorField.setMaxLength(32);
        discordOperatorField.setHint(Component.literal("Your Discord user id"));
        discordOperatorField.setValue(discordOperatorDraft);
        discordOperatorField.setResponder(v -> discordOperatorDraft = v);
        addRenderableWidget(discordOperatorField);

        y += 24;
        discordGuildField = new EditBox(this.font, x, y, half, 20, Component.literal("Server id"));
        discordGuildField.setMaxLength(32);
        discordGuildField.setHint(Component.literal("Server (guild) id, optional"));
        discordGuildField.setValue(discordGuildDraft);
        discordGuildField.setResponder(v -> discordGuildDraft = v);
        addRenderableWidget(discordGuildField);

        // The webhook is a different thing from the bot and needs none of the
        // above, but this is the card people look at when they want DoughBay
        // to say something in Discord, so it belongs here.
        y += 30;
        if (webhookDraft == null) webhookDraft = Tuning.text("webhook.url");
        webhookField = new EditBox(this.font, x, y, width - 124, 20, Component.literal("Webhook URL"));
        webhookField.setMaxLength(240);
        webhookField.setHint(Component.literal(
                Tuning.text("webhook.url").isBlank()
                        ? "Discord webhook URL for the hourly report"
                        : "Webhook saved; type to replace"));
        webhookField.setValue(webhookDraft);
        webhookField.setResponder(v -> webhookDraft = v);
        addRenderableWidget(webhookField);
        addRenderableWidget(CockpitButton.builder(Component.literal("Save"), ignored -> handleWebhookSave())
                .bounds(x + width - 118, y, 56, 20).build());
        Button test = CockpitButton.builder(Component.literal("Test"), ignored -> handleWebhookTest())
                .bounds(x + width - 56, y, 56, 20).build();
        test.active = !Tuning.text("webhook.url").isBlank();
        addRenderableWidget(test);
    }

    private String webhookDraft;
    private EditBox webhookField;

    /** Saves the webhook URL and switches the reports on, which is what saving one means. */
    private void handleWebhookSave() {
        String url = webhookDraft == null ? "" : webhookDraft.strip();
        if (!url.isEmpty() && !url.startsWith("https://discord.com/api/webhooks/")
                && !url.startsWith("https://discordapp.com/api/webhooks/")) {
            discordMessage = "That is not a Discord webhook URL: channel settings, Integrations, New Webhook, Copy";
            discordMessageColor = WARN;
            rebuildWidgets();
            return;
        }
        Tuning.setText("webhook.url", url);
        Tuning.set("webhook.enabled", url.isEmpty() ? 0 : 1);
        discordMessage = url.isEmpty()
                ? "Webhook cleared; no reports will be posted"
                : "Webhook saved; the hourly report is on";
        discordMessageColor = url.isEmpty() ? WARN : GOOD;
        rebuildWidgets();
    }

    /** Posts one report immediately, so the URL can be proved without waiting an hour. */
    private void handleWebhookTest() {
        StatusWebhook hook = DoughBayClient.webhook();
        if (hook == null) {
            discordMessage = "The webhook is not running yet";
            discordMessageColor = BAD;
        } else {
            hook.postNow();
            discordMessage = "Report posted; check the channel";
            discordMessageColor = GOOD;
        }
        rebuildWidgets();
    }

    private void handleDiscordSave() {
        dev.doughbay.fabric.discord.DiscordBridge bridge = DoughBayClient.discord();
        if (bridge == null) {
            discordMessage = "The Discord bridge is not running";
            discordMessageColor = BAD;
            rebuildWidgets();
            return;
        }
        dev.doughbay.fabric.discord.DiscordConfig old = bridge.config();
        String token = discordTokenDraft.isBlank() ? old.token() : discordTokenDraft.strip();
        String channel = discordChannelDraft == null ? "" : discordChannelDraft.strip();
        String operator = discordOperatorDraft == null ? "" : discordOperatorDraft.strip();
        String guild = discordGuildDraft == null ? "" : discordGuildDraft.strip();
        if (token.isBlank() || channel.isBlank() || operator.isBlank()) {
            discordMessage = "Token, channel id and your user id are all needed";
            discordMessageColor = WARN;
            rebuildWidgets();
            return;
        }
        if (!channel.matches("[0-9]{5,}") || !operator.matches("[0-9]{5,}")) {
            discordMessage = "Ids are numbers: with developer mode on, right-click the channel or your name and copy the id";
            discordMessageColor = WARN;
            rebuildWidgets();
            return;
        }
        String keepMessage = channel.equals(old.channelId()) ? old.messageId() : "";
        bridge.reconfigure(new dev.doughbay.fabric.discord.DiscordConfig(token, guild, channel, operator, keepMessage));
        discordTokenDraft = "";
        discordMessage = "Saved. Connecting; the panel appears in the channel within a minute.";
        discordMessageColor = GOOD;
        rebuildWidgets();
    }

    private void renderDiscordCard(GuiGraphicsExtractor graphics, AutomationModeGeometry geometry) {
        int x = geometry.left();
        int y = geometry.bodyY();
        int width = geometry.width();
        int height = Math.max(150, geometry.bottom() - y);
        card(graphics, x, y, width, height, CARD_DARK);
        dev.doughbay.fabric.discord.DiscordBridge bridge = DoughBayClient.discord();
        String status = bridge == null ? "not running" : bridge.status();
        sectionTitle(graphics, "DISCORD COMMAND CENTRE: " + status.toUpperCase(java.util.Locale.ROOT), x, y, width);
        int textY = y + 26 + 24 * 3 + 6;
        int innerX = x + 9;
        int innerWidth = width - 18;
        textY = drawWrapped(graphics, "One message in the channel, refreshed every 30 s with pages for money, listings, "
                + "opportunities, orders, rivals and payroll; buttons start and stop the trader and tune every setting. "
                + "Only the user id above may press them.", innerX, textY, innerWidth, y + height - 14, SECONDARY);
        if (!discordMessage.isBlank()) {
            drawWrapped(graphics, discordMessage, innerX, textY + 4, innerWidth, y + height - 4, discordMessageColor);
        }
    }

    private void handleApiKeyTest() {
        DoughBayConfig config = DoughBayClient.activeConfig();
        // An unsaved draft is tested as typed, so a bad paste is caught before
        // it replaces a working key on disk.
        String candidate = apiKeyDraft.isBlank()
                ? config == null ? "" : config.apiKey() : apiKeyDraft.strip();
        apiKeyMessage = "";
        API_KEY_TESTER.start(candidate);
        rebuildWidgets();
    }

    private void renderCompactObserveCard(GuiGraphicsExtractor graphics,
                                          MarketWatcher.Snapshot snapshot,
                                          Opportunity current,
                                          int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "OBSERVE — ANALYSIS / SESSION", x, y, width);
        int yy = y + 27;
        int innerX = x + 9;
        int innerWidth = width - 18;
        ExecutionStatus status = DRIVER.inspect();
        yy = kvRight(graphics, innerX, yy, innerWidth, "Market feed",
                snapshot.demo() ? "demo preview" : isFresh(snapshot) ? "live" : "stale",
                snapshot.demo() || !isFresh(snapshot) ? WARN : GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Opportunities / markets",
                snapshot.opportunities().size() + " / " + snapshot.markets().size(), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Completed sales",
                grouped(snapshot.diagnostics().transactions()), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Driver / intent",
                status.state().name().toLowerCase(Locale.ROOT) + " / "
                        + DRIVER.operationIntent().name().toLowerCase(Locale.ROOT),
                status.armed() ? WARN : SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Saved candidate",
                current == null ? "none / expired"
                        : titleCase(shortName(current.listing().itemId()))
                        + " x" + current.listing().itemCount(),
                current == null ? MUTED : GOOD);
        yy += 5;
        drawWrapped(graphics,
                "Completed-sales valuation only. Observe exposes no command buttons; B still emergency-stops an active operation.",
                innerX, yy, innerWidth, y + height - 10, SECONDARY);
    }

    private void renderObserveAnalysisCard(GuiGraphicsExtractor graphics,
                                           MarketWatcher.Snapshot snapshot,
                                           int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "OBSERVE — ANALYSIS ONLY", x, y, width);
        int yy = y + 27;
        int innerX = x + 9;
        int innerWidth = width - 18;
        yy = kvRight(graphics, innerX, yy, innerWidth, "Market feed",
                snapshot.demo() ? "demo preview" : isFresh(snapshot) ? "live" : "stale",
                snapshot.demo() || !isFresh(snapshot) ? WARN : GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Opportunities",
                Integer.toString(snapshot.opportunities().size()), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Exact-stack markets",
                Integer.toString(snapshot.markets().size()), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Completed sales stored",
                grouped(snapshot.diagnostics().transactions()), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Snapshot",
                snapshot.updatedAt() == 0 ? "never"
                        : relativeAge(System.currentTimeMillis() - snapshot.updatedAt()),
                isFresh(snapshot) ? GOOD : WARN);
        yy += 5;
        drawWrapped(graphics,
                "Valuation comes from completed sales only. Active listings may cap a resale target, never create one.",
                innerX, yy, innerWidth, y + height - 10, SECONDARY);
    }

    private void renderObserveSessionCard(GuiGraphicsExtractor graphics,
                                          Opportunity current,
                                          int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD_DARK);
        sectionTitle(graphics, "SESSION STATUS", x, y, width);
        int yy = y + 27;
        int innerX = x + 9;
        int innerWidth = width - 18;
        ExecutionStatus status = DRIVER.inspect();
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        yy = kvRight(graphics, innerX, yy, innerWidth, "Automation session",
                session.state().name().toLowerCase(Locale.ROOT) + " / "
                        + session.runMode().name().toLowerCase(Locale.ROOT),
                sessionStateColor(session));
        yy = kvRight(graphics, innerX, yy, innerWidth, "Active intent",
                DRIVER.operationIntent().name().toLowerCase(Locale.ROOT),
                DRIVER.operationIntent() == AutomatedExecutionDriver.OperationIntent.NONE
                        ? MUTED : WARN);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Driver state",
                status.state().name().toLowerCase(Locale.ROOT),
                status.armed() ? WARN : SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Saved candidate",
                current == null ? "none / expired"
                        : titleCase(shortName(current.listing().itemId()))
                        + " x" + current.listing().itemCount(),
                current == null ? MUTED : GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Preflight permission",
                DRIVER.preflightEnabled() ? "configured" : "off", SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Live permission",
                DRIVER.authorizedExecutionEnabled() ? "configured" : "off",
                DRIVER.authorizedExecutionEnabled() ? WARN : GOOD);
        yy += 5;
        String note = status.armed()
                ? "An operation is active. Press END for the global emergency stop."
                : "No command controls exist in Observe mode. Cycle Mode to inspect another boundary.";
        drawWrapped(graphics, note, innerX, yy, innerWidth, y + height - 10,
                status.armed() ? WARN : SECONDARY);
    }

    private void renderPreflightAutomation(GuiGraphicsExtractor graphics,
                                           MarketWatcher.Snapshot snapshot,
                                           Opportunity current,
                                           AutomationModeGeometry geometry) {
        AutomatedExecutionDriver.PreflightReport report = DRIVER.lastPreflightReport();
        if (geometry.compact()) {
            int y = geometry.bodyY();
            int height = geometry.bottom() - y;
            card(graphics, geometry.left(), y, geometry.width(), height, CARD);
            sectionTitle(graphics, "PREFLIGHT — READ ONLY", geometry.left(), y,
                    geometry.width());
            int yy = y + 98;
            int innerX = geometry.left() + 9;
            int innerWidth = geometry.width() - 18;
            String blocker = preflightBlocker(snapshot, automationCandidate);
            String event = preflightEvent(report, blocker);
            int textBottom = geometry.bodyY() + 96;
            yy = drawWrapped(graphics, event, innerX, yy, innerWidth,
                    textBottom, preflightEventColor(report, blocker));
            if (yy <= textBottom - 2) {
                graphics.text(this.font,
                        truncate("Search → stable GUI → exact verify → capture → STOP", innerWidth),
                        innerX, yy + 2, GOOD);
            }
            renderTestBuyCard(graphics, geometry);
            return;
        }

        renderModeCandidateCard(graphics, current, geometry);
        int lowerY = geometry.bodyY() + 84;
        int lowerHeight = geometry.bottom() - lowerY;
        int column = (geometry.width() - geometry.gap()) / 2;
        renderTestBuyCard(graphics, geometry);
        renderPreflightReportModeCard(graphics, snapshot, report,
                geometry.left() + column + geometry.gap(), lowerY,
                geometry.width() - column - geometry.gap(), lowerHeight);
    }

    private void renderModeCandidateCard(GuiGraphicsExtractor graphics, Opportunity current,
                                         AutomationModeGeometry geometry) {
        int x = geometry.left();
        int y = geometry.bodyY();
        int width = geometry.width();
        int height = 76;
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "EXACT CANDIDATE", x, y, width);
        int chooseWidth = Math.min(205, Math.max(145, width / 4));
        int textWidth = Math.max(80, width - chooseWidth - 30);
        int yy = y + 26;
        if (automationCandidate == null) {
            graphics.text(this.font,
                    truncate("No listing selected. Choose a completed-sales-backed opportunity.",
                            textWidth), x + 9, yy, MUTED);
        } else if (current == null) {
            graphics.text(this.font,
                    truncate("Saved listing expired or changed; every command remains locked.",
                            textWidth), x + 9, yy, WARN);
        } else {
            String name = titleCase(shortName(current.listing().itemId()))
                    + " x" + current.listing().itemCount();
            graphics.text(this.font, truncate(name, textWidth), x + 9, yy, TEXT);
            graphics.text(this.font,
                    truncate("Buy " + money(current.buyPrice())
                            + "  •  completed-sale target "
                            + money(current.recommendedSellPrice())
                            + "  •  seller " + current.listing().sellerName(), textWidth),
                    x + 9, yy + 12, GOOD);
        }
        String badge = current == null ? "NOT READY" : "FRESH EXACT";
        graphics.text(this.font, badge,
                x + width - 9 - this.font.width(badge), y + 7,
                current == null ? WARN : GOOD);
    }

    private void renderPreflightProcedureCard(GuiGraphicsExtractor graphics,
                                              MarketWatcher.Snapshot snapshot,
                                              Opportunity current,
                                              int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "READ-ONLY EXACT GUI CHECK", x, y, width);
        int yy = y + 27;
        int innerX = x + 9;
        int innerWidth = width - 18;
        yy = statusLine(graphics, innerX, yy, innerWidth,
                "1  Send one /ah search command", SECONDARY);
        yy = statusLine(graphics, innerX, yy, innerWidth,
                "2  Wait for a stable auction GUI", SECONDARY);
        yy = statusLine(graphics, innerX, yy, innerWidth,
                "3  Verify ID, count, seller, lore price", SECONDARY);
        yy = statusLine(graphics, innerX, yy, innerWidth,
                "4  Capture evidence and STOP — zero clicks", GOOD);
        yy += 4;
        yy = kvRight(graphics, innerX, yy, innerWidth, "Preflight config",
                DRIVER.preflightEnabled() ? "enabled" : "disabled",
                DRIVER.preflightEnabled() ? GOOD : WARN);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Exact server gate",
                DRIVER.currentPreflightBlocker() == null ? "allowed" : "locked",
                DRIVER.currentPreflightBlocker() == null ? GOOD : WARN);
        kvRight(graphics, innerX, yy, innerWidth, "Fresh exact candidate",
                current == null ? "no" : "yes", current == null ? WARN : GOOD);
    }

    private void renderPreflightReportModeCard(GuiGraphicsExtractor graphics,
                                               MarketWatcher.Snapshot snapshot,
                                               AutomatedExecutionDriver.PreflightReport report,
                                               int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD_DARK);
        sectionTitle(graphics, "LAST PREFLIGHT REPORT", x, y, width);
        int innerX = x + 9;
        int innerWidth = width - 18;
        int maxY = y + height - 34;
        int yy = y + 27;
        String blocker = preflightBlocker(snapshot, automationCandidate);
        yy = drawWrapped(graphics, preflightEvent(report, blocker),
                innerX, yy, innerWidth, maxY, preflightEventColor(report, blocker));
        for (AutomatedExecutionDriver.Check check : report.checks()) {
            if (yy > maxY - this.font.lineHeight) break;
            int color = switch (check.status()) {
                case MATCH -> GOOD;
                case MISMATCH -> BAD;
                case UNKNOWN -> MUTED;
            };
            String mark = switch (check.status()) {
                case MATCH -> "✓ ";
                case MISMATCH -> "× ";
                case UNKNOWN -> "• ";
            };
            yy = statusLine(graphics, innerX, yy + 2, innerWidth,
                    mark + check.label() + ": " + check.observed(), color);
        }
        java.nio.file.Path capture = report.diagnosticsPath() != null
                ? report.diagnosticsPath() : DRIVER.lastCapturePath();
        if (capture != null && yy <= maxY - this.font.lineHeight) {
            graphics.text(this.font,
                    truncate("Evidence: " + capture.getFileName(), innerWidth),
                    innerX, maxY - this.font.lineHeight, Charts.SERIES_AQUA);
        }
    }

    private String preflightEvent(AutomatedExecutionDriver.PreflightReport report,
                                  String blocker) {
        if (DRIVER.operationIntent() == AutomatedExecutionDriver.OperationIntent.PREFLIGHT) {
            return "RUNNING — " + DRIVER.inspect().description();
        }
        if (report.outcome() != AutomatedExecutionDriver.PreflightOutcome.NOT_RUN) {
            return report.outcome().name() + " — " + report.detail();
        }
        if (!driverMessage.isBlank()) return driverMessage;
        return blocker == null
                ? "READY — one search, exact verification, evidence capture, zero clicks"
                : "LOCKED — " + blocker;
    }

    private int preflightEventColor(AutomatedExecutionDriver.PreflightReport report,
                                    String blocker) {
        if (DRIVER.operationIntent() == AutomatedExecutionDriver.OperationIntent.PREFLIGHT) {
            return WARN;
        }
        if (report.outcome() != AutomatedExecutionDriver.PreflightOutcome.NOT_RUN) {
            return preflightOutcomeColor(report.outcome());
        }
        return blocker == null ? GOOD : WARN;
    }

    private void renderSingleTradeAutomation(GuiGraphicsExtractor graphics,
                                             MarketWatcher.Snapshot snapshot,
                                             AutomationModeGeometry geometry) {
        renderBoundedSessionAutomation(graphics, snapshot, geometry,
                AutomationSessionController.RunMode.SINGLE);
    }

    private void renderContinuousAutomation(GuiGraphicsExtractor graphics,
                                            MarketWatcher.Snapshot snapshot,
                                            AutomationModeGeometry geometry) {
        renderBoundedSessionAutomation(graphics, snapshot, geometry,
                AutomationSessionController.RunMode.CONTINUOUS);
    }

    private void renderCompactContinuousCard(GuiGraphicsExtractor graphics,
                                             MarketWatcher.Snapshot snapshot,
                                             DoughBayConfig config,
                                             int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD_DARK);
        sectionTitle(graphics, "CONTINUOUS — SESSION / LIMITS", x, y, width);
        int yy = y + 27;
        int innerX = x + 9;
        int innerWidth = width - 18;
        yy = kvRight(graphics, innerX, yy, innerWidth, "Controller / session",
                continuousControllerAvailable() ? "connected / stopped" : "not connected / stopped",
                continuousControllerAvailable() ? GOOD : WARN);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Current position",
                "none", MUTED);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Market feed",
                snapshot.demo() ? "demo — locked" : isFresh(snapshot) ? "fresh" : "stale",
                snapshot.demo() || !isFresh(snapshot) ? WARN : GOOD);
        if (config != null) {
            yy = kvRight(graphics, innerX, yy, innerWidth, "Permission / servers",
                    (config.continuousAutomationEnabled() ? "on" : "off") + " / "
                            + config.continuousAutomationServers().size(),
                    config.continuousAutomationEnabled() ? WARN : GOOD);
            yy = kvRight(graphics, innerX, yy, innerWidth, "Purchase / session cap",
                    money(config.continuousMaxPurchasePrice()) + " / "
                            + money(config.continuousMaxSessionSpend()), TEXT);
            yy = kvRight(graphics, innerX, yy, innerWidth, "Trades / cooldown",
                    config.continuousMaxTradesPerSession() + " / "
                            + config.continuousCooldownSeconds() + "s", SECONDARY);
            yy = kvRight(graphics, innerX, yy, innerWidth, "Min profit / ROI / confidence",
                    money(config.continuousMinimumProfit()) + " / "
                            + percent(config.continuousMinimumRoiPercent()) + " / "
                            + percent(config.continuousMinimumConfidencePercent()), GOOD);
        }
        drawWrapped(graphics,
                "Controls stay disabled until the bounded controller supplies its audited state. Live trading is switched on or off from this tab.",
                innerX, yy + 5, innerWidth, y + height - 10, WARN);
    }

    private void renderContinuousLimitsCard(GuiGraphicsExtractor graphics,
                                            DoughBayConfig config,
                                            int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "CONTINUOUS SESSION — HARD LIMITS", x, y, width);
        int yy = y + 27;
        int innerX = x + 9;
        int innerWidth = width - 18;
        if (config == null) {
            drawWrapped(graphics, "Configuration is unavailable; session start is locked.",
                    innerX, yy, innerWidth, y + height - 10, WARN);
            return;
        }
        yy = kvRight(graphics, innerX, yy, innerWidth, "Continuous permission",
                config.continuousAutomationEnabled() ? "configured" : "off",
                config.continuousAutomationEnabled() ? WARN : GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Exact server entries",
                Integer.toString(config.continuousAutomationServers().size()), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Per-purchase cap",
                money(config.continuousMaxPurchasePrice()), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Session spend cap",
                money(config.continuousMaxSessionSpend()), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Trades per session",
                Integer.toString(config.continuousMaxTradesPerSession()), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Minimum profit",
                money(config.continuousMinimumProfit()), GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Minimum ROI / confidence",
                percent(config.continuousMinimumRoiPercent()) + " / "
                        + percent(config.continuousMinimumConfidencePercent()), GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Cooldown / max hold",
                config.continuousCooldownSeconds() + "s / "
                        + config.continuousMaximumHoldMinutes() + "m", SECONDARY);
        kvRight(graphics, innerX, yy, innerWidth, "Reserved hotbar slot",
                Integer.toString(config.continuousReservedHotbarSlot() + 1), SECONDARY);
    }

    private void renderContinuousStateCard(GuiGraphicsExtractor graphics,
                                           MarketWatcher.Snapshot snapshot,
                                           DoughBayConfig config,
                                           int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD_DARK);
        sectionTitle(graphics, "SESSION STATE / CURRENT POSITION", x, y, width);
        int yy = y + 27;
        int innerX = x + 9;
        int innerWidth = width - 18;
        yy = kvRight(graphics, innerX, yy, innerWidth, "Controller",
                continuousControllerAvailable() ? "connected" : "not connected",
                continuousControllerAvailable() ? GOOD : WARN);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Session",
                "STOPPED", MUTED);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Current position",
                "none", MUTED);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Underlying driver",
                DRIVER.operationIntent().name().toLowerCase(Locale.ROOT),
                DRIVER.inspect().armed() ? WARN : SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Market feed",
                snapshot.demo() ? "demo — locked" : isFresh(snapshot) ? "fresh" : "stale",
                snapshot.demo() || !isFresh(snapshot) ? WARN : GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Start gate",
                config == null || !config.continuousAutomationEnabled()
                        ? "permission off" : "awaiting controller",
                WARN);
        yy += 5;
        drawWrapped(graphics,
                "Start remains disabled until the bounded session controller supplies its audited snapshot and exact-server gate. Live trading is switched on or off from this tab.",
                innerX, yy, innerWidth, y + height - 10, WARN);
    }

    private void renderBoundedSessionAutomation(GuiGraphicsExtractor graphics,
                                                MarketWatcher.Snapshot marketSnapshot,
                                                AutomationModeGeometry geometry,
                                                AutomationSessionController.RunMode requestedMode) {
        ContinuousAutomationPolicy policy = DoughBayClient.continuousPolicy();
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        int bodyY = geometry.bodyY();
        int cardHeight = Math.max(48, geometry.bottom() - bodyY - 36);
        if (geometry.compact()) {
            renderCompactBoundedSessionCard(graphics, marketSnapshot, policy, session,
                    requestedMode, geometry.left(), bodyY, geometry.width(), cardHeight);
            return;
        }
        int column = (geometry.width() - geometry.gap()) / 2;
        int policyEnd = renderSessionPolicyCard(graphics, policy, requestedMode,
                geometry.left(), bodyY, column, cardHeight);
        renderSlotTracker(graphics, geometry.left() + 9, policyEnd + 8, column - 18,
                bodyY + cardHeight - 10, 8);
        renderActualSessionStateCard(graphics, marketSnapshot, policy, session, requestedMode,
                geometry.left() + column + geometry.gap(), bodyY,
                geometry.width() - column - geometry.gap(), cardHeight);
    }

    private void renderCompactBoundedSessionCard(GuiGraphicsExtractor graphics,
                                                 MarketWatcher.Snapshot marketSnapshot,
                                                 ContinuousAutomationPolicy policy,
                                                 AutomationSessionController.SessionSnapshot session,
                                                 AutomationSessionController.RunMode requestedMode,
                                                 int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD_DARK);
        sectionTitle(graphics,
                requestedMode == AutomationSessionController.RunMode.SINGLE
                        ? "SINGLE — BUY → RELIST → MONITOR"
                        : "CONTINUOUS — BOUNDED SESSION",
                x, y, width);
        int innerX = x + 9;
        int innerWidth = width - 18;
        int yy = y + 27;
        AutomationSessionController.RunMode shownMode = displayedRunMode(session, requestedMode);
        yy = kvRight(graphics, innerX, yy, innerWidth, "State / run mode",
                session.state().name().toLowerCase(Locale.ROOT) + " / "
                        + shownMode.name().toLowerCase(Locale.ROOT),
                sessionStateColor(session));
        yy = kvRight(graphics, innerX, yy, innerWidth, "Persistence",
                session.persistenceStatus(),
                session.persistenceStatus().startsWith("READY") ? GOOD : BAD);
        if (session.recoveredSession()) {
            yy = kvRight(graphics, innerX, yy, innerWidth, "Recovery / stage",
                    session.recoveredExposureCount() + " / "
                            + session.manualResolutionStage(), WARN);
        }
        yy = kvRight(graphics, innerX, yy, innerWidth, "Trades / spend",
                session.tradesStarted() + " / " + money(session.committedSpend()), TEXT);
        yy = renderSlotTracker(graphics, innerX, yy + 2, innerWidth, y + height - 12, 3);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Current position",
                compactPositionSummary(session),
                pausedWithUnresolvedPosition(session) ? BAD
                        : session.position() == null ? MUTED : GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Bound listing",
                session.boundListingKey().isBlank() ? "none" : session.boundListingKey(),
                session.boundListingKey().isBlank() ? MUTED : Charts.SERIES_AQUA);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Reconciliation",
                session.reconciliationStatus(),
                session.boundListingKey().isBlank() ? MUTED : WARN);
        if (policy != null) {
            yy = kvRight(graphics, innerX, yy, innerWidth, "Purchase / session cap",
                    money(policy.maxPurchasePrice()) + " / "
                            + money(policy.maxSessionSpend()), TEXT);
            yy = kvRight(graphics, innerX, yy, innerWidth, "Trade cap / cooldown",
                    (shownMode == AutomationSessionController.RunMode.SINGLE
                            ? "1" : Integer.toString(policy.maxTradesPerSession())) + " / "
                            + automationDuration(policy.cooldownMillis()), SECONDARY);
        } else {
            yy = statusLine(graphics, innerX, yy, innerWidth,
                    "LOCKED — " + safePolicyBlocker(), WARN);
        }
        String startBlocker = sessionStartBlocker(marketSnapshot);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Start gate",
                startBlocker == null ? "authorized; exact server matched" : startBlocker,
                startBlocker == null ? GOOD : WARN);
        if (pausedWithUnresolvedPosition(session)) {
            yy = statusLine(graphics, innerX, yy + 3, innerWidth,
                    "PAUSED — unresolved item; inspect manually before any resume", BAD);
        }
        if (requestedMode == AutomationSessionController.RunMode.CONTINUOUS
                && yy <= y + height - 23) {
            yy = statusLine(graphics, innerX, yy + 2, innerWidth,
                    "Continues after Esc while you play; END emergency-stops", SECONDARY);
        }
        if (yy <= y + height - 12) {
            yy = drawWrapped(graphics, session.detail(), innerX, yy + 2, innerWidth,
                    y + height - 10, sessionStateColor(session));
        }
        // What the last button press answered, e.g. why a recovery step was
        // refused. Without it a refused click looked like nothing happened.
        if (!driverMessage.isBlank() && yy <= y + height - 12) {
            drawWrapped(graphics, "Last action: " + driverMessage, innerX, yy + 2, innerWidth,
                    y + height - 10, WARN);
        }
    }

    /** Returns the y just below the last row, for whatever follows in the card. */
    private int renderSessionPolicyCard(GuiGraphicsExtractor graphics,
                                        ContinuousAutomationPolicy policy,
                                        AutomationSessionController.RunMode requestedMode,
                                        int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics,
                requestedMode == AutomationSessionController.RunMode.SINGLE
                        ? "ONE COMPLETE TRADE — HARD LIMITS"
                        : "CONTINUOUS SESSION — HARD LIMITS",
                x, y, width);
        int innerX = x + 9;
        int innerWidth = width - 18;
        int yy = y + 27;
        if (policy == null) {
            return drawWrapped(graphics, "LOCKED — " + safePolicyBlocker(),
                    innerX, yy, innerWidth, y + height - 10, WARN);
        }
        if (requestedMode == AutomationSessionController.RunMode.SINGLE) {
            yy = statusLine(graphics, innerX, yy, innerWidth,
                    "One selection → buy → exact stack prep → relist", SECONDARY);
            yy = statusLine(graphics, innerX, yy, innerWidth,
                    "→ monitor trusted sale → stop", GOOD);
            yy = statusLine(graphics, innerX, yy + 3, innerWidth,
                    "Uses the same authorization and caps as Continuous", WARN);
        } else {
            yy = statusLine(graphics, innerX, yy, innerWidth,
                    "At most one open position; every ambiguity PAUSES", GOOD);
            yy = statusLine(graphics, innerX, yy, innerWidth,
                    "Continues after Esc while you play; END stops before the next tick", SECONDARY);
        }
        yy += 5;
        yy = kvRight(graphics, innerX, yy, innerWidth, "Per-purchase cap",
                money(policy.maxPurchasePrice()), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Session spend cap",
                money(policy.maxSessionSpend()), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Trades per session",
                requestedMode == AutomationSessionController.RunMode.SINGLE
                        ? "1 (single mode)" : Integer.toString(policy.maxTradesPerSession()), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Minimum net profit",
                money(policy.minimumProfit()), GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Minimum ROI / confidence",
                percent(policy.minimumRoiPercent()) + " / "
                        + percent(policy.minimumConfidence() * 100.0), GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Cooldown / max hold",
                automationDuration(policy.cooldownMillis()) + " / "
                        + automationDuration(policy.maximumHoldMillis()), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Snapshot freshness",
                automationDuration(policy.maximumSnapshotAgeMillis()), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Open listings cap",
                policy.maxOpenListings() + " of " + SlotTracker.slots(), SECONDARY);
        return kvRight(graphics, innerX, yy, innerWidth, "Reserved hotbar slot",
                Integer.toString(policy.reservedHotbarSlot() + 1), SECONDARY);
    }

    /**
     * The 45 auction slots as a grid: green for a listing this mod parked,
     * outlined up to the session cap, dark beyond it, then the parked
     * listings themselves. Only the mod's own listings are known; anything
     * listed by hand is not counted.
     */
    private int renderSlotTracker(GuiGraphicsExtractor graphics, int x, int y, int width,
                                  int maxY, int maxRows) {
        List<AutomationSessionController.OpenListingView> open = AUTOMATION_SESSION.openListingViews();
        int cap = AUTOMATION_SESSION.maxOpenListings();
        int other = AUTOMATION_SESSION.otherListedSlots();
        long out = 0;
        long back = 0;
        for (var listing : open) {
            out += listing.purchasePrice();
            back += listing.targetPrice();
        }
        int yy = kvRight(graphics, x, y, width, "Auction slots",
                (open.size() + other) + " / " + SlotTracker.slots() + " used"
                        + (other > 0 ? " (" + other + " yours)" : "") + " · cap " + cap,
                open.size() >= cap ? WARN : open.isEmpty() ? SECONDARY : GOOD);
        yy = SlotTracker.drawGrid(graphics, x, yy + 1, width, open.size() + other, cap) + 3;
        if (yy + 10 <= maxY) {
            yy = kvRight(graphics, x, yy, width, "Ticket tiers", AUTOMATION_SESSION.tierSummary(), SECONDARY);
        }
        if (!open.isEmpty() && yy + 10 <= maxY) {
            yy = kvRight(graphics, x, yy, width, "Out / back at target",
                    money(out) + " / " + money(back), TEXT);
        }
        long now = System.currentTimeMillis();
        int rows = 0;
        for (int i = open.size() - 1; i >= 0 && rows < maxRows && yy + 10 <= maxY; i--, rows++) {
            var listing = open.get(i);
            yy = kvRight(graphics, x, yy, width,
                    titleCase(shortName(listing.itemKey())) + " x" + listing.quantity(),
                    money(listing.purchasePrice()) + " → " + money(listing.targetPrice())
                            + "  " + relativeAge(Math.max(0, now - listing.listedAt())),
                    SECONDARY);
        }
        if (rows < open.size() && yy + 10 <= maxY) {
            yy = statusLine(graphics, x, yy, width,
                    "… " + (open.size() - rows) + " more", MUTED);
        }
        return yy;
    }

    private void renderActualSessionStateCard(GuiGraphicsExtractor graphics,
                                              MarketWatcher.Snapshot marketSnapshot,
                                              ContinuousAutomationPolicy policy,
                                              AutomationSessionController.SessionSnapshot session,
                                              AutomationSessionController.RunMode requestedMode,
                                              int x, int y, int width, int height) {
        card(graphics, x, y, width, height, CARD_DARK);
        sectionTitle(graphics, "LIVE SESSION / CURRENT POSITION", x, y, width);
        int innerX = x + 9;
        int innerWidth = width - 18;
        int yy = y + 27;
        yy = kvRight(graphics, innerX, yy, innerWidth, "State",
                session.state().name(), sessionStateColor(session));
        yy = kvRight(graphics, innerX, yy, innerWidth, "Run mode",
                displayedRunMode(session, requestedMode).name(), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Persistence",
                session.persistenceStatus(),
                session.persistenceStatus().startsWith("READY") ? GOOD : BAD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Recovery lock / stage",
                session.recoveredSession()
                        ? session.recoveredExposureCount() + " exposure(s) / "
                        + session.manualResolutionStage()
                        : "none",
                session.recoveredSession() ? WARN : MUTED);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Trades started",
                session.tradesStarted() + (policy == null ? ""
                        : " / " + (displayedRunMode(session, requestedMode)
                        == AutomationSessionController.RunMode.SINGLE
                        ? 1 : policy.maxTradesPerSession())), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Committed spend",
                money(session.committedSpend()) + (policy == null ? ""
                        : " / " + money(policy.maxSessionSpend())), TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Attempted listings",
                Integer.toString(session.attemptedListings()), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Cooldown remaining",
                remainingDuration(session.cooldownUntilMillis()), SECONDARY);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Pending exact listing",
                session.pendingListingKey().isBlank() ? "none" : session.pendingListingKey(),
                session.pendingListingKey().isBlank() ? MUTED : WARN);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Bound active listing",
                session.boundListingKey().isBlank() ? "none" : session.boundListingKey(),
                session.boundListingKey().isBlank() ? MUTED : Charts.SERIES_AQUA);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Reconciliation",
                session.reconciliationStatus(),
                session.boundListingKey().isBlank() ? MUTED : WARN);
        yy += 2;
        if (session.position() == null) {
            yy = kvRight(graphics, innerX, yy, innerWidth, "Current position",
                    "none", MUTED);
        } else {
            var position = session.position();
            yy = kvRight(graphics, innerX, yy, innerWidth, "Position",
                    titleCase(shortName(position.itemKey())) + " x" + position.quantity(), TEXT);
            yy = kvRight(graphics, innerX, yy, innerWidth, "Position status",
                    position.status().name(),
                    pausedWithUnresolvedPosition(session) ? BAD : GOOD);
            yy = kvRight(graphics, innerX, yy, innerWidth, "Buy / target",
                    money(position.purchasePrice()) + " / " + money(position.targetPrice()), GOOD);
        }

        String startBlocker = sessionStartBlocker(marketSnapshot);
        yy = kvRight(graphics, innerX, yy, innerWidth, "New-session gate",
                startBlocker == null ? "authorized; exact server allowlists match" : startBlocker,
                startBlocker == null ? GOOD : WARN);
        if (pausedWithUnresolvedPosition(session)) {
            yy = statusLine(graphics, innerX, yy + 4, innerWidth,
                    "PAUSED — UNRESOLVED POSITION; DO NOT START ANOTHER TRADE", BAD);
        }
        drawWrapped(graphics, session.detail(), innerX, yy + 4, innerWidth,
                y + height - 10, sessionStateColor(session));
    }

    private static int sessionStateColor(
            AutomationSessionController.SessionSnapshot session) {
        return switch (session.state()) {
            case STOPPED -> MUTED;
            case PAUSED -> BAD;
            // Monitoring still means capital is exposed; green is reserved
            // for genuinely closed/reconciled outcomes.
            case MONITORING -> WARN;
            case SCANNING, COOLDOWN -> SECONDARY;
            case REPRICING, AUDITING_SLOTS, READING_ORDERS, BID_DESK -> WARN;
            case BUYING, PREPARING_LIST, LISTING -> WARN;
        };
    }

    private static AutomationSessionController.RunMode displayedRunMode(
            AutomationSessionController.SessionSnapshot session,
            AutomationSessionController.RunMode selectedMode) {
        return session.state() == AutomationSessionController.State.STOPPED
                && !session.unresolvedExposure()
                ? selectedMode : session.runMode();
    }

    private static boolean pausedWithUnresolvedPosition(
            AutomationSessionController.SessionSnapshot session) {
        return session.state() == AutomationSessionController.State.PAUSED
                && session.unresolvedExposure();
    }

    private static String compactPositionSummary(
            AutomationSessionController.SessionSnapshot session) {
        if (session.position() == null) {
            return session.pendingListingKey().isBlank() ? "none" : "pending exact listing";
        }
        return session.position().status().name().toLowerCase(Locale.ROOT)
                + " x" + session.position().quantity();
    }

    private static String safePolicyBlocker() {
        String blocker = DoughBayClient.continuousPolicyBlocker();
        return blocker == null || blocker.isBlank()
                ? "automation policy is unavailable" : blocker;
    }

    private static String automationDuration(long millis) {
        if (millis <= 0) return "0s";
        long seconds = millis / 1_000L;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 48) return hours + "h";
        return (hours / 24) + "d";
    }

    private static String remainingDuration(long untilMillis) {
        if (untilMillis <= 0) return "none";
        long remaining = Math.max(0, untilMillis - System.currentTimeMillis());
        return remaining == 0 ? "complete" : automationDuration(remaining);
    }

    /** The controller is constructed once by DoughBayClient and is always present. */
    private static boolean continuousControllerAvailable() {
        return AUTOMATION_SESSION != null;
    }

    private record AutomationModeGeometry(
            boolean compact, int left, int titleY, int bottom, int width,
            int modeX, int modeY, int modeWidth, int bodyY, int gap) {
        int right() {
            return left + width;
        }
    }

    private record ModeActionGeometry(
            int primaryX, int primaryY, int primaryWidth,
            int secondaryX, int secondaryY, int secondaryWidth) {
    }

    // -------------------------------------------------------------- diagnostics

    private void renderDiagnostics(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot,
                                   UiLayout ui) {
        resetListGeometry();
        MarketWatcher.Diagnostics diagnostics = snapshot.diagnostics();
        int x = ui.innerLeft();
        int y = ui.contentTop() + 8;
        int width = ui.innerWidth();
        int statusHeight = 43;
        int statusY = ui.bottom() - statusHeight;
        int bottom = statusY - 8;
        int gap = 8;
        int cardWidth = (width - gap) / 2;
        int cardHeight = Math.max(70, (bottom - y - gap) / 2);
        boolean framed = width < 680 || cardHeight < 112;

        int rightX = x + cardWidth + gap;
        int lowerY = y + cardHeight + gap;

        renderCollectionDiagnostics(graphics, snapshot, diagnostics,
                x, y, cardWidth, cardHeight, framed);
        renderApiDiagnostics(graphics, diagnostics, rightX, y,
                width - cardWidth - gap, cardHeight, framed);
        renderStorageDiagnostics(graphics, snapshot,
                x, lowerY, cardWidth, cardHeight, framed);
        renderRuntimeDiagnostics(graphics, snapshot, rightX, lowerY,
                width - cardWidth - gap, cardHeight, framed);

        if (!framed) {
            int dividerX = x + cardWidth + gap / 2;
            graphics.fill(dividerX, y + 4, dividerX + 1, bottom - 4, PANEL_INNER_EDGE);
        }

        graphics.fill(x, statusY, ui.innerRight(), ui.bottom() - 8, CARD_DARK);
        graphics.outline(x, statusY, width, statusHeight - 8, PANEL_EDGE);
        String headline = snapshot.demo()
                ? "PREVIEW MODE — add an API key to begin collecting real market history"
                : snapshot.updatedAt() == 0
                ? "STARTING — " + snapshot.status()
                : !isFresh(snapshot)
                ? "STALE — waiting for a fresh market scan"
                : snapshot.status();
        int color = snapshot.demo() ? WARN
                : snapshot.status().startsWith("STOPPED") ? BAD
                : snapshot.updatedAt() == 0 || !isFresh(snapshot) ? WARN : GOOD;
        graphics.text(this.font, truncate(headline, width - 16), x + 8, statusY + 7, color);
        String controls = "B emergency stop  •  Esc close";
        int controlsWidth = this.font.width(controls);
        if (width >= 680 && controlsWidth < width / 2) {
            String path = watcher == null ? "config/doughbay"
                    : watcher.config().directory().toString();
            graphics.text(this.font,
                    truncate("Config: " + path, width - controlsWidth - 30),
                    x + 8, statusY + 18, MUTED);
            graphics.text(this.font, controls,
                    ui.innerRight() - 8 - controlsWidth, statusY + 18, SECONDARY);
        } else {
            graphics.text(this.font, truncate(controls, width - 16),
                    x + 8, statusY + 18, SECONDARY);
        }
    }

    private void renderCollectionDiagnostics(GuiGraphicsExtractor graphics,
                                             MarketWatcher.Snapshot snapshot,
                                             MarketWatcher.Diagnostics diagnostics,
                                             int x, int y, int width, int height,
                                             boolean framed) {
        if (framed) card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "COLLECTION", x, y, width);
        int yy = y + 25;
        int innerX = x + 9;
        int innerWidth = Math.min(width - 18, 310);
        boolean compact = height < 105;
        if (!compact) {
            String collector = snapshot.demo() ? "preview paused"
                    : snapshot.updatedAt() == 0 ? "starting"
                    : !isFresh(snapshot) ? "waiting for scan" : "collecting";
            yy = kvRight(graphics, innerX, yy, innerWidth, "Collector", collector,
                    snapshot.demo() || snapshot.updatedAt() == 0 || !isFresh(snapshot)
                            ? WARN : GOOD);
        }
        yy = kvRight(graphics, innerX, yy, innerWidth, "Transactions",
                grouped(diagnostics.transactions()), diagnostics.transactions() > 0 ? TEXT : MUTED);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Distinct markets",
                grouped(diagnostics.markets()), diagnostics.markets() > 0 ? TEXT : MUTED);
        if (!compact) {
            yy = kvRight(graphics, innerX, yy, innerWidth, "Flagged outliers",
                    grouped(diagnostics.outliers()), SECONDARY);
        }
        yy = kvRight(graphics, innerX, yy, innerWidth, "Parse failures",
                Integer.toString(diagnostics.parseFailures()),
                diagnostics.parseFailures() > 0 ? WARN : GOOD);
        if (!compact) {
            // A missed sale cannot be re-fetched, so the window and any gap it
            // failed to cover belong on screen rather than only in the log.
            long gaps = diagnostics.historyGapsSuspected();
            yy = kvRight(graphics, innerX, yy, innerWidth, "Account",
                    snapshot.accountName().isEmpty() ? "not signed in"
                            : snapshot.accountName() + " • "
                            + (snapshot.accountBalance() < 0
                                    ? "balance unavailable" : money(snapshot.accountBalance())),
                    snapshot.accountName().isEmpty() || snapshot.accountBalance() < 0
                            ? WARN : GOOD);
            yy = kvRight(graphics, innerX, yy, innerWidth, "History window",
                    diagnostics.historyPagesPerPoll() + " page(s)/poll"
                            + (gaps == 0 ? "" : " • " + grouped(gaps) + " gap(s)"),
                    gaps > 0 ? BAD
                            : diagnostics.historyPagesPerPoll() > 2 ? WARN : GOOD);
        }
        if (!compact) {
            kvRight(graphics, innerX, yy, innerWidth, "Latest snapshot",
                    snapshot.updatedAt() == 0 ? "never"
                            : relativeAge(System.currentTimeMillis() - snapshot.updatedAt()),
                    isFresh(snapshot) ? GOOD : WARN);
        }
    }

    private void renderApiDiagnostics(GuiGraphicsExtractor graphics,
                                      MarketWatcher.Diagnostics diagnostics,
                                      int x, int y, int width, int height,
                                      boolean framed) {
        if (framed) card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "DONUT API", x, y, width);
        int yy = y + 25;
        int innerX = x + 9;
        int innerWidth = Math.min(width - 18, 310);
        boolean compact = height < 105;
        boolean keyPresent = "present".equalsIgnoreCase(diagnostics.apiKeyState());
        yy = kvRight(graphics, innerX, yy, innerWidth, "API key",
                diagnostics.apiKeyState(), keyPresent ? GOOD : WARN);
        if (!compact) {
            boolean healthy = "healthy".equalsIgnoreCase(diagnostics.apiConnectionState());
            yy = kvRight(graphics, innerX, yy, innerWidth, "API health",
                    diagnostics.apiConnectionState(), healthy ? GOOD : WARN);
            yy = kvRight(graphics, innerX, yy, innerWidth, "Requests total",
                    grouped(diagnostics.apiRequests()), TEXT);
        }
        yy = kvRight(graphics, innerX, yy, innerWidth, "Errors",
                grouped(diagnostics.apiErrors()), diagnostics.apiErrors() > 0 ? WARN : GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "This minute",
                diagnostics.requestsLastMinute() + " / " + diagnostics.requestBudget(), SECONDARY);
        if (!compact) {
            String transport = diagnostics.apiCooldownRemainingMillis() > 0
                    ? "cooldown " + Math.max(1, diagnostics.apiCooldownRemainingMillis() / 1000) + "s"
                    : diagnostics.apiLatencyMillis() < 0 ? "not measured"
                    : diagnostics.apiLatencyMillis() + " ms"
                    + (diagnostics.apiLastStatusCode() > 0
                    ? " • HTTP " + diagnostics.apiLastStatusCode() : "");
            kvRight(graphics, innerX, yy, innerWidth, "Transport", transport,
                    diagnostics.apiConsecutiveFailures() > 0 ? WARN : SECONDARY);
        }

        if (height < 82) return;
        int trackY = y + height - 14;
        int trackWidth = Math.max(1, innerWidth);
        graphics.fill(innerX, trackY, innerX + trackWidth, trackY + 7, TRACK);
        double ratio = diagnostics.requestBudget() <= 0 ? 0
                : diagnostics.requestsLastMinute() / (double) diagnostics.requestBudget();
        int fillWidth = (int) (Math.max(0, Math.min(1, ratio)) * trackWidth);
        graphics.fill(innerX, trackY, innerX + fillWidth, trackY + 7,
                ratio > 0.85 ? WARN : Charts.SERIES_AQUA);
    }

    private void renderStorageDiagnostics(GuiGraphicsExtractor graphics,
                                          MarketWatcher.Snapshot snapshot,
                                          int x, int y, int width, int height,
                                          boolean framed) {
        if (framed) card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "LOCAL STORAGE", x, y, width);
        int yy = y + 25;
        int innerX = x + 9;
        int innerWidth = Math.min(width - 18, 310);
        boolean compact = height < 105;
        String database = watcher == null ? "doughbay.db"
                : watcher.config().databasePath().getFileName().toString();
        yy = kvRight(graphics, innerX, yy, innerWidth, "Database", database, TEXT);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Collector",
                snapshot.demo() ? "not opened in preview"
                        : snapshot.status().startsWith("STOPPED") ? "stopped" : "SQLite collector",
                snapshot.demo() ? MUTED
                        : snapshot.status().startsWith("STOPPED") ? BAD : GOOD);
        if (!compact) {
            yy = kvRight(graphics, innerX, yy, innerWidth, "Raw responses",
                    snapshot.demo() ? "none" : "retained per record", SECONDARY);
        }
        yy = kvRight(graphics, innerX, yy, innerWidth, "Long-term history",
                snapshot.demo() ? "waiting for API key" : "building locally",
                snapshot.demo() ? MUTED : GOOD);
        if (!compact) {
            kvRight(graphics, innerX, yy, innerWidth, "Schema",
                    "migrated on startup", SECONDARY);
        }
    }

    private void renderRuntimeDiagnostics(GuiGraphicsExtractor graphics,
                                          MarketWatcher.Snapshot snapshot,
                                          int x, int y, int width, int height,
                                          boolean framed) {
        if (framed) card(graphics, x, y, width, height, CARD);
        sectionTitle(graphics, "RUNTIME", x, y, width);
        int yy = y + 25;
        int innerX = x + 9;
        int innerWidth = Math.min(width - 18, 310);
        boolean compact = height < 105;
        yy = kvRight(graphics, innerX, yy, innerWidth, "Data feed",
                snapshot.demo() ? "demo preview"
                        : snapshot.updatedAt() == 0 ? "starting"
                        : !isFresh(snapshot) ? "stale" : "live API",
                snapshot.demo() || snapshot.updatedAt() == 0 || !isFresh(snapshot) ? WARN : GOOD);
        yy = kvRight(graphics, innerX, yy, innerWidth, "Execution",
                DRIVER.modeName(), DRIVER.authorizedExecutionEnabled() ? WARN : GOOD);
        if (!compact) {
            String gate = !DRIVER.authorizedExecutionEnabled() ? "disabled"
                    : DRIVER.currentEnvironmentBlocker() == null ? "authorized" : "locked";
            yy = kvRight(graphics, innerX, yy, innerWidth, "Server gate", gate,
                    "authorized".equals(gate) ? GOOD : WARN);
            yy = kvRight(graphics, innerX, yy, innerWidth, "Driver state",
                    DRIVER.inspect().state().name().toLowerCase(Locale.ROOT),
                    DRIVER.inspect().armed() ? WARN : SECONDARY);
            yy = kvRight(graphics, innerX, yy, innerWidth, "Snapshot age",
                    snapshot.updatedAt() == 0 ? "never"
                            : relativeAge(System.currentTimeMillis() - snapshot.updatedAt()),
                    isFresh(snapshot) ? GOOD : WARN);
        }
        kvRight(graphics, innerX, yy, innerWidth, "Emergency key", "END", SECONDARY);
    }

    // --------------------------------------------------------------------- about

    private void renderAbout(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot,
                             UiLayout ui) {
        resetListGeometry();
        int x = ui.innerLeft();
        int y = ui.contentTop() + 8;
        int width = ui.innerWidth();
        int gap = 8;
        int cardWidth = (width - gap) / 2;
        int availableHeight = ui.bottom() - 10 - y;
        int cardHeight = (availableHeight - gap) / 2;
        int rightX = x + cardWidth + gap;
        int lowerY = y + cardHeight + gap;

        card(graphics, x, y, cardWidth, cardHeight, CARD);
        sectionTitle(graphics, "WHAT DOUGHBAY DOES", x, y, cardWidth);
        int yy = y + 25;
        yy = drawWrapped(graphics,
                "Collects completed sales and active listings, values exact item/stack markets, "
                        + "then ranks conservative resale opportunities.",
                x + 9, yy, cardWidth - 18, y + cardHeight - 8, TEXT);
        drawWrapped(graphics,
                "The goal is likely profit, sale probability, and time until capital is reusable — "
                        + "not merely finding the cheapest listing.",
                x + 9, yy + 5, cardWidth - 18, y + cardHeight - 8, SECONDARY);

        int rightWidth = width - cardWidth - gap;
        card(graphics, rightX, y, rightWidth, cardHeight, CARD);
        sectionTitle(graphics, "CURRENT MODE", rightX, y, rightWidth);
        int modeY = y + 25;
        modeY = kvRight(graphics, rightX + 9, modeY, rightWidth - 18,
                "Market feed", snapshot.demo() ? "demo preview"
                        : snapshot.updatedAt() == 0 ? "starting"
                        : !isFresh(snapshot) ? "stale" : "live API",
                snapshot.demo() || snapshot.updatedAt() == 0 || !isFresh(snapshot) ? WARN : GOOD);
        modeY = kvRight(graphics, rightX + 9, modeY, rightWidth - 18,
                "Game actions", DRIVER.authorizedExecutionEnabled()
                        ? "authorized test mode" : "Observe / disabled",
                DRIVER.authorizedExecutionEnabled() ? WARN : GOOD);
        modeY = kvRight(graphics, rightX + 9, modeY, rightWidth - 18,
                "Paper trading", "CLI", SECONDARY);
        drawWrapped(graphics,
                snapshot.demo()
                        ? "Demo opportunities are invented and can never execute."
                        : "Execution remains player-initiated and aborts on ambiguity.",
                rightX + 9, modeY + 4, rightWidth - 18,
                y + cardHeight - 8, snapshot.demo() ? WARN : SECONDARY);

        card(graphics, x, lowerY, cardWidth, cardHeight, CARD);
        sectionTitle(graphics, "HOW PRICE IS JUDGED", x, lowerY, cardWidth);
        int modelY = lowerY + 25;
        String[] modelLines = {
                "1  Exact item and stack completed sales",
                "2  Median/MAD outlier filtering",
                "3  Recent sales receive more weight",
                "4  Conservative quick-sale percentile",
                "5  Competition, liquidity, trend, and risk"
        };
        for (String line : modelLines) {
            if (modelY > lowerY + cardHeight - 10) break;
            graphics.text(this.font, truncate(line, cardWidth - 18), x + 9, modelY, SECONDARY);
            modelY += 12;
        }

        card(graphics, rightX, lowerY, rightWidth, cardHeight, CARD);
        sectionTitle(graphics, "CONTROLS & SAFETY", rightX, lowerY, rightWidth);
        int controlsY = lowerY + 25;
        String[] controls = {
                "B  Open GoNuts / emergency stop",
                "Click  Open a signal or switch tabs",
                "Wheel  Scroll long market tables",
                "Esc  Back from detail, then close",
                "Live automation defaults OFF"
        };
        for (String line : controls) {
            if (controlsY > lowerY + cardHeight - 10) break;
            int color = line.startsWith("Live") ? WARN : SECONDARY;
            graphics.text(this.font, truncate(line, rightWidth - 18),
                    rightX + 9, controlsY, color);
            controlsY += 12;
        }
    }

    // --------------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        AUTOMATION_SESSION.notePanelActivity();
        if (super.mouseClicked(event, doubled)) return true;

        if (tab == Tab.OVERVIEW && event.y() >= layout().contentTop() + 112 && event.y() < layout().bottom() - 10) {
            for (SignalHit hit : overviewSignals) {
                if (event.x() >= hit.x() && event.x() < hit.x() + hit.width()
                        && event.y() >= hit.y() && event.y() < hit.y() + hit.height()) {
                    tab = Tab.OPPORTUNITIES;
                    selected = hit.opportunity();
                    pageScrollPixels = 0;
                    rebuildWidgets();
                    return true;
                }
            }
        }
        if (tab == Tab.MARKETS) {
            MarketSort clicked = marketHeaderSortAt(event.x(), event.y());
            if (clicked != null) {
                if (clicked == marketSort) {
                    marketSortDescending = !marketSortDescending;
                } else {
                    marketSort = clicked;
                    // Names read naturally A-Z; every measure is more useful
                    // biggest-first, which is the whole point of sorting them.
                    marketSortDescending = clicked != MarketSort.MARKET;
                }
                scroll = 0;
                return true;
            }
        }
        if (withinScrollBar(event.x(), event.y())) {
            draggingScrollBar = true;
            scrollToTrackPosition(event.y());
            return true;
        }

        if (tab == Tab.SETTINGS) {
            for (int i = 0; i < settingRowBounds.size(); i++) {
                int[] b = settingRowBounds.get(i);
                if (event.y() >= b[0] && event.y() < b[1] && event.x() >= b[3] && event.x() < b[4]) {
                    String key = settingRowKeys.get(b[2]);
                    if (key.startsWith("key.")) {
                        rebindingKey = key.equals("key.open") ? DoughBayClient.keyOpenScreen() : DoughBayClient.keyEmergencyStop();
                        settingsMessage = rebindingKey == null ? "" : "Press the new key for "
                                + (key.equals("key.open") ? "opening the screen" : "the emergency stop") + " (Esc keeps " + keyName(rebindingKey) + ")";
                        return true;
                    }
                    if (settingField != null && settingField.isFocused()) settingField.setFocused(false);
                    selectedSetting = key;
                    settingDraft = settingDraftFor(key);
                    settingsMessage = "";
                    rebuildWidgets();
                    return true;
                }
            }
        }

        if (tab == Tab.PAYROLL) {
            for (long[] b : payrollRowBounds) {
                if (event.y() >= b[0] && event.y() < b[1]) {
                    selectRule(b[2] == selectedRule ? 0 : b[2]);
                    return true;
                }
            }
        }

        if (tab == Tab.RIVALS && selectedRival == null && rivalRowsGeometry.length == 3) {
            int index = (int) Math.floor((event.y() - rivalRowsGeometry[0]) / (double) rivalRowsGeometry[1]);
            RivalIntel intel = DoughBayClient.rivalIntel();
            if (event.y() >= rivalRowsGeometry[0] && index >= 0 && index < rivalRowsGeometry[2]
                    && intel != null && index < intel.snapshot().rivals().size()) {
                selectedRival = intel.snapshot().rivals().get(index).name();
                rebuildWidgets();
                return true;
            }
        }

        if (tab == Tab.OPPORTUNITIES && selected == null) {
            List<Opportunity> rows = snapshot().opportunities();
            int index = rowAt(event.y(), rows.size());
            if (index >= 0) {
                selected = rows.get(index);
                automationCandidate = selected;
                scroll = 0;
                pageScrollPixels = 0;
                driverMessage = "";
                rebuildWidgets();
                return true;
            }
        }

        if (tab == Tab.SEARCH) {
            if (searchDetailItem == null && searchSellerName == null && searchRowsGeometry.length == 3) {
                int index = (int) Math.floor((event.y() - searchRowsGeometry[0]) / (double) searchRowsGeometry[1]);
                if (event.y() >= searchRowsGeometry[0] && index >= 0
                        && index < searchRowsGeometry[2] && index < searchRows.size()) {
                    SearchRow row = searchRows.get(index);
                    // Right-click a seller to track their whole line without
                    // opening them; left-click still opens the seller or item.
                    if (event.button() == 1 && row.seller()) {
                        trackSellerFromList(row.id());
                    } else {
                        searchSelected = index;
                        openSearchRow(row);
                    }
                    return true;
                }
            }
            if (searchSellerName != null && searchSaleRowsGeometry.length == 3) {
                int index = (int) Math.floor((event.y() - searchSaleRowsGeometry[0]) / (double) searchSaleRowsGeometry[1]);
                List<MarketQueryClient.Sale> sales = searchSellerView == null
                        ? List.of() : searchSellerView.sales();
                if (event.y() >= searchSaleRowsGeometry[0] && index >= 0
                        && index < searchSaleRowsGeometry[2] && index < sales.size()) {
                    openSearchItem(sales.get(index).itemId());
                    return true;
                }
            }
        }

        if (tab == Tab.ITEMS) {
            int hit = itemsGridIndexAt(event.x(), event.y());
            if (hit >= 0 && hit < itemsFrame.size()) {
                String id = itemsFrame.get(hit).id();
                if (!itemsSelected.remove(id)) itemsSelected.add(id);
                rebuildWidgets();   // refresh the action-row counts
                return true;
            }
        }
        return false;
    }

    /** 0 exclude, 1 include, 2 clear-from-both, applied to every selected item. */
    private void applyItemsSelection(int option) {
        for (String id : itemsSelected) {
            switch (option) {
                case 0 -> {
                    Tuning.removeFromList("items.allow", id);
                    Tuning.addToList("items.deny", id);
                }
                case 1 -> {
                    Tuning.removeFromList("items.deny", id);
                    Tuning.addToList("items.allow", id);
                }
                default -> {
                    Tuning.removeFromList("items.deny", id);
                    Tuning.removeFromList("items.allow", id);
                }
            }
        }
        rebuildWidgets();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        AUTOMATION_SESSION.notePanelActivity();
        if (draggingScrollBar) {
            scrollToTrackPosition(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollBar = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        AUTOMATION_SESSION.notePanelActivity();
        if (selected != null || tab == Tab.OVERVIEW || tab == Tab.ACTIVITY) {
            int step = Math.max(16, this.font.lineHeight * 2);
            int next = pageScrollPixels - (int) Math.signum(deltaY) * step;
            pageScrollPixels = clamp(next, 0, maxPageScrollPixels);
            return true;
        }
        if (tab == Tab.SETTINGS) {
            settingsScroll = clamp(settingsScroll - (int) Math.signum(deltaY) * 24, 0, Math.max(0, settingsMaxScroll));
            return true;
        }
        if (tab == Tab.ITEMS) {
            itemsScrollRow = Math.max(0, itemsScrollRow - (int) Math.signum(deltaY));
            return true;
        }
        if (tab == Tab.LOGS) {
            logsScroll = Math.max(0, logsScroll - (int) Math.signum(deltaY));
            return true;
        }
        if (tab != Tab.OPPORTUNITIES && tab != Tab.MARKETS) {
            return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
        }
        if (mouseY < listTop || mouseY >= listBottom) return false;
        int next = scroll - (int) Math.signum(deltaY);
        scroll = Math.max(0, Math.min(next, maxScroll));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        AUTOMATION_SESSION.notePanelActivity();
        if (rebindingKey != null) {
            KeyMapping mapping = rebindingKey;
            rebindingKey = null;
            if (event.key() != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                mapping.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(event.key()));
                KeyMapping.resetMapping();
                Minecraft.getInstance().options.save();
                settingsMessage = "Saved: " + keyName(mapping) + " (kept in Minecraft's controls)";
            } else {
                settingsMessage = "Kept " + keyName(mapping);
            }
            return true;
        }
        if (selected != null && event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            selected = null;
            scroll = 0;
            pageScrollPixels = 0;
            driverMessage = "";
            rebuildWidgets();
            return true;
        }
        if (tab == Tab.ITEMS && !itemsSelected.isEmpty()
                && event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            itemsSelected.clear();
            rebuildWidgets();
            return true;
        }
        if (tab == Tab.SEARCH) {
            int key = event.key();
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
                    && (searchDetailItem != null || searchSellerName != null)) {
                searchDetailItem = null;
                searchSellerName = null;
                rebuildWidgets();
                return true;
            }
            if (searchDetailItem == null && searchSellerName == null && !searchRows.isEmpty()) {
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
                    searchSelected = Math.min(searchRows.size() - 1, searchSelected + 1);
                    return true;
                }
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP) {
                    searchSelected = Math.max(0, searchSelected - 1);
                    return true;
                }
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                        || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                    openSearchRow(searchRows.get(Math.min(searchSelected, searchRows.size() - 1)));
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // -------------------------------------------------------------------- layout

    private UiLayout layout() {
        int gutterX = clamp(this.width / 40, 6, 32);
        int gutterY = clamp(this.height / 50, 4, 16);
        int panelWidth = Math.max(1, Math.min(MAX_PANEL_WIDTH, this.width - gutterX * 2));
        int panelHeight = Math.max(1, Math.min(MAX_PANEL_HEIGHT, this.height - gutterY * 2));
        int left = (this.width - panelWidth) / 2;
        int top = (this.height - panelHeight) / 2;
        int inset = panelWidth >= 700 ? 16 : 9;
        int sidebar = panelWidth >= 680 && panelHeight >= 430 ? 144 : 0;
        int columns = Math.max(3, Math.min(7, (panelWidth - inset * 2) / 90));
        int rows = (Tab.values().length + columns - 1) / columns;
        int headerHeight = sidebar > 0 ? 64 : 58 + rows * 22;
        return new UiLayout(left, top, left + panelWidth, top + panelHeight, inset,
                headerHeight, top + 52, 20, sidebar, columns);
    }

    private record UiLayout(int left, int top, int right, int bottom,
                            int inset, int headerHeight, int tabY, int tabHeight,
                            int sidebar, int navColumns) {
        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }

        int innerLeft() {
            return left + sidebar + inset;
        }

        int innerRight() {
            return right - inset;
        }

        int innerWidth() {
            return innerRight() - innerLeft();
        }

        int contentTop() {
            return top + headerHeight;
        }
    }

    private static int[] wideColumns(int width) {
        int usable = width - 10;
        return new int[]{
                10,
                (int) (usable * 0.35),
                (int) (usable * 0.48),
                (int) (usable * 0.60),
                (int) (usable * 0.72),
                (int) (usable * 0.815),
                (int) (usable * 0.905),
                usable
        };
    }

    private static int[] compactColumns(int width) {
        int usable = width - 10;
        return new int[]{
                10,
                (int) (usable * 0.43),
                (int) (usable * 0.58),
                (int) (usable * 0.73),
                (int) (usable * 0.85),
                usable
        };
    }

    private void drawWideHeader(GuiGraphicsExtractor graphics, int left, int y,
                                int[] columns, String[] labels) {
        for (int i = 0; i < labels.length; i++) {
            cell(graphics, labels[i], left, columns[i], y, GOLD, i > 0);
        }
    }

    /**
     * Draws the market header and records where each sortable label sits.
     *
     * <p>Hit boxes follow the drawn text rather than the column bands: the
     * labels are right-aligned to their column offset, so a band would put the
     * clickable area beside the word instead of on it.
     */
    private void drawSortableHeader(GuiGraphicsExtractor graphics, int left, int y,
                                    int[] columns, String[] labels, MarketSort[] sorts) {
        for (int i = 0; i < labels.length; i++) {
            MarketSort sort = i < sorts.length ? sorts[i] : null;
            boolean active = sort != null && sort == marketSort;
            String label = labels[i] + (active ? (marketSortDescending ? " ▼" : " ▲") : "");
            boolean rightAligned = i > 0;
            int width = this.font.width(label);
            int x = left + columns[i] - (rightAligned ? width : 0);
            graphics.text(this.font, label, x, y, active ? TEXT : GOLD);
            if (sort != null) {
                // A few pixels of slack: the labels are small targets.
                marketHeaderHits.add(new HeaderHit(x - 3, x + width + 3, sort));
            }
        }
    }

    /** The column a click landed on, or null when it missed every label. */
    private MarketSort marketHeaderSortAt(double mouseX, double mouseY) {
        if (mouseY < marketHeaderTop || mouseY > marketHeaderBottom) return null;
        for (HeaderHit hit : marketHeaderHits) {
            if (hit.contains(mouseX)) return hit.sort();
        }
        return null;
    }

    /**
     * The search field and the three toggles that actually get used.
     *
     * <p>A per-item on/off list was the obvious reading of "filter", but the
     * table can hold hundreds of markets, so that control would be longer than
     * the thing it filters. Typing a few letters reaches one market faster,
     * and the toggles cover the standing questions: is it trustworthy, is it
     * liquid, and is it the plain item rather than a named or filled variant.
     */

    private void initMarketFilterWidgets(UiLayout ui) {
        int y = ui.contentTop() + 4;
        int left = ui.innerLeft();
        int width = ui.innerWidth();
        boolean wide = width >= 680;

        int toggleWidth = wide ? 104 : Math.max(58, (width - 12) / 4);
        int gap = 4;
        int searchWidth = Math.max(70, width - (toggleWidth + gap) * 3);

        marketSearchField = new EditBox(this.font, left, y, searchWidth, 18,
                Component.literal("Filter markets"));
        marketSearchField.setMaxLength(128);
        marketSearchField.setHint(Component.literal("Search markets..."));
        marketSearchField.setValue(marketSearch);
        // Attached after setValue so restoring the text does not reset scroll.
        marketSearchField.setResponder(value -> {
            marketSearch = value;
            scroll = 0;
        });
        addRenderableWidget(marketSearchField);
        if (marketSearchFocused) setFocused(marketSearchField);

        int x = left + searchWidth + gap;
        x = addMarketToggle(x, y, toggleWidth, wide ? "Confidence" : "Conf",
                hideLowConfidence, () -> hideLowConfidence = !hideLowConfidence) + gap;
        x = addMarketToggle(x, y, toggleWidth, wide ? "Liquid only" : "Liquid",
                hideIlliquid, () -> hideIlliquid = !hideIlliquid) + gap;
        addMarketToggle(x, y, toggleWidth, wide ? "Plain only" : "Plain",
                hideVariants, () -> hideVariants = !hideVariants);
    }

    /** @return the toggle's right edge, so the next one can follow it */
    private int addMarketToggle(int x, int y, int width, String label,
                                boolean on, Runnable toggle) {
        Button button = CockpitButton.builder(
                        Component.literal((on ? "☑ " : "☐ ") + label), ignored -> {
                            toggle.run();
                            scroll = 0;
                            rebuildWidgets();
                        })
                .bounds(x, y, width, 18).build();
        addRenderableWidget(button);
        return x + width;
    }

    // ------------------------------------------------------------ Search tab

    private void initSearchWidgets(UiLayout ui) {
        int y = ui.contentTop() + 4;
        int left = ui.innerLeft();
        int width = ui.innerWidth();
        if (searchDetailItem == null && searchSellerName == null) {
            searchField = new EditBox(this.font, left, y, width, 18, Component.literal("Search"));
            searchField.setMaxLength(128);
            searchField.setHint(Component.literal("Search item or seller — e.g. redstone, beforewing"));
            searchField.setValue(searchQuery);
            // Responder only stores text; typing never rebuilds the widgets, so
            // the caret keeps its place and one cached /v1/markets feeds every
            // keystroke. Matching runs on that cache, never on the network.
            searchField.setResponder(value -> {
                searchQuery = value;
                searchSelected = 0;
            });
            addRenderableWidget(searchField);
            setFocused(searchField);
            return;
        }
        // Detail mode: a Back control, and for a seller the window toggles.
        Button back = CockpitButton.builder(Component.literal("Back"), ignored -> {
            searchDetailItem = null;
            searchSellerName = null;
            rebuildWidgets();
        }).bounds(ui.innerRight() - 58, ui.contentTop() + 3, 58, 18).build();
        addRenderableWidget(back);
        if (searchSellerName != null) {
            int bw = 46;
            int gap = 4;
            int bx = ui.innerLeft();
            int wy = ui.contentTop() + 3;
            bx = addSearchWindowButton(bx, wy, bw, "24h", 24L * 3_600_000L) + gap;
            bx = addSearchWindowButton(bx, wy, bw, "7d", 7L * 24 * 3_600_000L) + gap;
            addSearchWindowButton(bx, wy, bw, "30d", 30L * 24 * 3_600_000L);
            // All of this seller's items, in one click: onto the watch list, the
            // exclude or include list, or the clipboard. No typing, no cap.
            int rx = ui.innerRight() - 58;                 // left edge of Back
            int inclW = 74;
            int exclW = 74;
            int copyW = 54;
            int trackW = 64;
            rx -= gap + inclW;
            addRenderableWidget(CockpitButton.builder(Component.literal("→ Include"),
                    ignored -> addSellerItemsToList("items.allow", "items.deny"))
                    .bounds(rx, wy, inclW, 18).build());
            rx -= gap + exclW;
            addRenderableWidget(CockpitButton.builder(Component.literal("→ Exclude"),
                    ignored -> addSellerItemsToList("items.deny", "items.allow"))
                    .bounds(rx, wy, exclW, 18).build());
            rx -= gap + copyW;
            addRenderableWidget(CockpitButton.builder(Component.literal("Copy"),
                    ignored -> copySellerItems()).bounds(rx, wy, copyW, 18).build());
            rx -= gap + trackW;
            addRenderableWidget(CockpitButton.builder(Component.literal("→ Track"),
                    ignored -> addSellerItemsToTracked()).bounds(rx, wy, trackW, 18).build());
        }
    }

    /** Adds every one of the seller's items to the watch list (tracked markets). */
    private void addSellerItemsToTracked() {
        java.util.LinkedHashSet<String> ids = sellerItemIds();
        if (ids.isEmpty()) {
            searchMessage = "No items yet";
            rebuildWidgets();
            return;
        }
        seedTrackedIfNeeded();
        for (String id : ids) Tuning.addToList("watch.tracked", id);
        searchMessage = "Tracking " + ids.size() + " item(s) from " + searchSellerName;
        rebuildWidgets();
    }

    /** The seller's distinct items, deduped and normalized to bare ids. */
    private java.util.LinkedHashSet<String> sellerItemIds() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        MarketQueryClient.SellerView view = searchSellerView;
        if (view == null) return ids;
        for (MarketQueryClient.Sale s : view.sales()) {
            String bare = Tuning.normalizeItem(s.itemId());
            if (!bare.isEmpty()) ids.add(bare);
        }
        return ids;
    }

    private void copySellerItems() {
        java.util.LinkedHashSet<String> ids = sellerItemIds();
        if (ids.isEmpty()) {
            searchMessage = "No items to copy yet";
            rebuildWidgets();
            return;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(String.join(", ", ids));
        searchMessage = "Copied " + ids.size() + " item(s) to the clipboard";
        rebuildWidgets();
    }

    /** Adds every one of the seller's items to a list, taking them off the other. */
    private void addSellerItemsToList(String listKey, String otherKey) {
        java.util.LinkedHashSet<String> ids = sellerItemIds();
        if (ids.isEmpty()) {
            searchMessage = "No items yet";
            rebuildWidgets();
            return;
        }
        for (String id : ids) {
            Tuning.removeFromList(otherKey, id);
            Tuning.addToList(listKey, id);
        }
        searchMessage = (listKey.equals("items.deny") ? "Excluded " : "Included ")
                + ids.size() + " item(s) from " + searchSellerName;
        rebuildWidgets();
    }

    private int addSearchWindowButton(int x, int y, int width, String label, long millis) {
        boolean on = searchWindowMillis == millis;
        Button button = CockpitButton.builder(Component.literal((on ? "● " : "○ ") + label),
                        ignored -> setSearchWindow(millis))
                .bounds(x, y, width, 18).build();
        addRenderableWidget(button);
        return x + width;
    }

    private void setSearchWindow(long millis) {
        searchWindowMillis = millis;
        if (searchSellerName != null) openSearchSeller(searchSellerName);
    }

    private void openSearchRow(SearchRow row) {
        if (row.seller()) {
            openSearchSeller(row.id());
        } else {
            openSearchItem(row.id());
        }
    }

    private void openSearchItem(String itemId) {
        searchDetailItem = itemId;
        searchSellerName = null;
        searchMessage = "";
        rebuildWidgets();
    }

    private void openSearchSeller(String name) {
        searchSellerName = name;
        searchDetailItem = null;
        searchMessage = "";
        MarketQueryClient client = DoughBayClient.marketQuery();
        searchSellerView = client == null
                ? null
                : client.requestSeller(name, System.currentTimeMillis() - searchWindowMillis);
        rebuildWidgets();
    }

    /** Fetches a seller in the background and tracks all their items once it loads. */
    private void trackSellerFromList(String name) {
        MarketQueryClient client = DoughBayClient.marketQuery();
        if (client == null) return;
        pendingTrackSeller = client.requestSeller(name, System.currentTimeMillis() - searchWindowMillis);
        searchMessage = "Tracking " + name + "'s items…";
    }

    /** When a right-click track fetch finishes, add its items to the watch list. */
    private void drainPendingTrack() {
        MarketQueryClient.SellerView v = pendingTrackSeller;
        if (v == null || v.loading()) return;
        pendingTrackSeller = null;
        if (v.error() != null || v.sales().isEmpty()) {
            searchMessage = "Could not load " + v.name();
            return;
        }
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (MarketQueryClient.Sale s : v.sales()) {
            String b = Tuning.normalizeItem(s.itemId());
            if (!b.isEmpty()) ids.add(b);
        }
        seedTrackedIfNeeded();
        for (String id : ids) Tuning.addToList("watch.tracked", id);
        searchMessage = "Tracked " + ids.size() + " item(s) from " + v.name();
    }

    private void renderSearch(GuiGraphicsExtractor graphics, UiLayout ui, int mouseX, int mouseY) {
        resetListGeometry();
        searchRowsGeometry = new int[0];
        searchSaleRowsGeometry = new int[0];
        if (searchDetailItem != null) {
            renderSearchItemDetail(graphics, ui);
            return;
        }
        if (searchSellerName != null) {
            renderSearchSeller(graphics, ui, mouseY);
            return;
        }
        renderSearchResults(graphics, ui, mouseY);
    }

    private void renderSearchResults(GuiGraphicsExtractor graphics, UiLayout ui, int mouseY) {
        int x = ui.innerLeft();
        int width = ui.innerWidth();
        int top = ui.contentTop() + 28;         // below the search field
        int bottom = ui.bottom() - 10;
        MarketQueryClient client = DoughBayClient.marketQuery();
        if (client == null) {
            graphics.text(this.font, "Search is unavailable (no API base URL configured).", x, top, MUTED);
            searchRows = List.of();
            return;
        }
        List<SearchRow> rows = new ArrayList<>();
        String q = searchQuery.strip();
        drainPendingTrack();
        if (!searchMessage.isBlank()) {
            String note = truncate(searchMessage, width / 2);
            graphics.text(this.font, note, ui.innerRight() - this.font.width(note), top, GOOD);
        }
        if (q.isEmpty()) {
            graphics.text(this.font,
                    "TOP SELLERS  ·  most sales, last 24 h  ·  right-click a seller to track their items",
                    x, top, SECONDARY);
            top += 14;
            int i = 0;
            for (MarketQueryClient.Rival r : client.rivals()) {
                if (i++ >= 20) break;
                rows.add(new SearchRow(true, r.seller(), r.seller(),
                        grouped(r.sales()) + " sales · " + money(r.revenue()) + " · " + r.items() + " item(s)"));
            }
            if (rows.isEmpty()) {
                graphics.text(this.font, "Loading the seller board…", x, top + 4, MUTED);
            }
        } else {
            for (MarketQueryClient.ItemMatch m : client.matchItems(q, 8)) {
                rows.add(new SearchRow(false, m.itemId(), m.displayName(), m.itemId()));
            }
            rows.add(new SearchRow(true, q, "seller: " + q, "open this player's sale history"));
        }
        searchRows = rows;
        if (rows.isEmpty()) return;
        if (searchSelected >= rows.size()) searchSelected = rows.size() - 1;
        if (searchSelected < 0) searchSelected = 0;

        int rowHeight = 22;
        int count = Math.min(rows.size(), Math.max(1, (bottom - top) / rowHeight));
        searchRowsGeometry = new int[]{top, rowHeight, count};
        for (int r = 0; r < count; r++) {
            SearchRow row = rows.get(r);
            int ry = top + r * rowHeight;
            boolean hot = r == searchSelected;
            boolean hover = mouseY >= ry && mouseY < ry + rowHeight;
            if (hot || hover) {
                graphics.fill(x, ry, x + width, ry + rowHeight, hot ? CARD : CARD_DARK);
            }
            graphics.text(this.font, row.seller() ? "◆" : "▪", x + 4, ry + 3,
                    row.seller() ? WARN : GOOD);
            graphics.text(this.font, truncate(row.label(), width - 24), x + 16, ry + 3, TEXT);
            graphics.text(this.font, truncate(row.sub(), width - 24), x + 16, ry + 12, MUTED);
        }
    }

    private void renderSearchItemDetail(GuiGraphicsExtractor graphics, UiLayout ui) {
        int x = ui.innerLeft();
        int width = ui.innerWidth();
        int top = ui.contentTop() + 26;
        MarketQueryClient client = DoughBayClient.marketQuery();
        graphics.text(this.font, MarketQueryClient.displayName(searchDetailItem), x, top, GOLD);
        graphics.text(this.font, searchDetailItem, x, top + 11, MUTED);
        MarketStats stats = client == null ? null : client.statsFor(searchDetailItem);
        int cardTop = top + 26;
        int cardWidth = Math.min(width, 320);
        int cardHeight = Math.min(150, ui.bottom() - 10 - cardTop);
        renderMarketCard(graphics, stats, x, cardTop, cardWidth, cardHeight);
        if (stats == null && width > cardWidth + 24) {
            graphics.text(this.font, "No cached market stats for this item yet.",
                    x + cardWidth + 12, cardTop + 6, MUTED);
        }
    }

    private void renderSearchSeller(GuiGraphicsExtractor graphics, UiLayout ui, int mouseY) {
        int x = ui.innerLeft();
        int width = ui.innerWidth();
        int top = ui.contentTop() + 26;
        graphics.text(this.font, searchSellerName, x, top, GOLD);
        MarketQueryClient.SellerView view = searchSellerView;
        String windowLabel = searchWindowMillis >= 30L * 24 * 3_600_000L ? "30 d"
                : searchWindowMillis >= 7L * 24 * 3_600_000L ? "7 d" : "24 h";
        if (view == null) {
            graphics.text(this.font, "Seller lookup is unavailable.", x, top + 14, MUTED);
            return;
        }
        if (view.loading()) {
            graphics.text(this.font, "Loading " + searchSellerName + "…", x, top + 14, SECONDARY);
            return;
        }
        if (view.error() != null) {
            graphics.text(this.font, truncate("Lookup failed: " + view.error(), width), x, top + 14, WARN);
            return;
        }
        List<MarketQueryClient.Sale> sales = view.sales();
        graphics.text(this.font, grouped(sales.size()) + " sales · " + money(view.revenue())
                + " revenue · last " + windowLabel, x, top + 12, SECONDARY);
        if (!searchMessage.isBlank()) {
            String note = truncate(searchMessage, width / 2);
            graphics.text(this.font, note, ui.innerRight() - this.font.width(note), top + 12, GOOD);
        }
        int listTop = top + 28;
        int bottom = ui.bottom() - 10;
        if (sales.isEmpty()) {
            graphics.text(this.font, "No sales in this window.", x, listTop + 4, MUTED);
            return;
        }
        int rowHeight = 12;
        int count = Math.min(sales.size(), Math.max(1, (bottom - listTop) / rowHeight));
        searchSaleRowsGeometry = new int[]{listTop, rowHeight, count};
        long now = System.currentTimeMillis();
        int colItem = 82;
        int colCountRight = width - 92;
        for (int i = 0; i < count; i++) {
            MarketQueryClient.Sale s = sales.get(i);
            int ry = listTop + i * rowHeight;
            boolean hover = mouseY >= ry && mouseY < ry + rowHeight;
            if (hover) graphics.fill(x, ry, x + width, ry + rowHeight, CARD_DARK);
            graphics.text(this.font, relativeAge(now - s.soldAt()), x + 2, ry + 2, MUTED);
            graphics.text(this.font, truncate(titleCase(shortName(s.itemId())), colCountRight - colItem - 6),
                    x + colItem, ry + 2, TEXT);
            cell(graphics, "x" + s.itemCount(), x, colCountRight, ry + 2, SECONDARY, true);
            cell(graphics, money(s.totalPrice()), x, width, ry + 2, GOOD, true);
        }
    }

    // ------------------------------------------------------------- Items tab

    /** Every registered item with a stack to draw, built once and sorted by name. */
    private static List<ItemEntry> allItems() {
        if (ALL_ITEMS != null) return ALL_ITEMS;
        List<ItemEntry> out = new ArrayList<>();
        for (net.minecraft.resources.Identifier id
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
            String key = id.toString();
            if (key.equals("minecraft:air")) continue;
            net.minecraft.world.item.Item item =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
            if (item == null) continue;
            String bare = Tuning.normalizeItem(key);
            out.add(new ItemEntry(key, bare, MarketQueryClient.displayName(key),
                    new net.minecraft.world.item.ItemStack(item), categoryOf(item, bare)));
        }
        out.sort(Comparator.comparing(ItemEntry::name, String.CASE_INSENSITIVE_ORDER));
        ALL_ITEMS = out;
        return out;
    }

    /**
     * Whether an item is covered by a list, the same way the trader decides it:
     * an exact entry, or a suffix rule (a {@code boots} entry covers
     * {@code netherite_boots}). So the grid's colours show what the bot will
     * actually do with the shared config, never a second, disagreeing view.
     */
    private static boolean listMatches(java.util.Set<String> tokens, String bare) {
        if (tokens.contains(bare)) return true;
        for (String t : tokens) if (bare.endsWith("_" + t)) return true;
        return false;
    }

    private List<ItemEntry> filteredItems() {
        String needle = MarketQueryClient.normalizeQuery(itemsSearch);
        java.util.Set<String> deny = itemsFilter == ItemsFilter.EXCLUDED ? Tuning.itemSet("items.deny") : null;
        java.util.Set<String> allow = itemsFilter == ItemsFilter.INCLUDED ? Tuning.itemSet("items.allow") : null;
        java.util.Set<String> tracked = itemsFilter == ItemsFilter.TRACKED ? trackedBareSet() : null;
        List<ItemEntry> out = new ArrayList<>();
        for (ItemEntry e : allItems()) {
            if (!needle.isEmpty() && !e.bare().contains(needle)) continue;
            if (itemsCat != ItemCat.ALL && e.cat() != itemsCat) continue;
            if (deny != null && !listMatches(deny, e.bare())) continue;
            if (allow != null && !listMatches(allow, e.bare())) continue;
            if (tracked != null && !tracked.contains(e.bare())) continue;
            out.add(e);
        }
        return out;
    }

    private void initItemsWidgets(UiLayout ui) {
        int y = ui.contentTop() + 4;
        int left = ui.innerLeft();
        int width = ui.innerWidth();
        int filterW = width >= 560 ? 150 : 118;
        int catW = width >= 560 ? 118 : 92;
        int gap = 4;
        int searchW = Math.max(70, width - filterW - catW - gap * 2);
        itemsSearchField = new EditBox(this.font, left, y, searchW, 18, Component.literal("Filter items"));
        itemsSearchField.setMaxLength(128);
        itemsSearchField.setHint(Component.literal("Search items..."));
        itemsSearchField.setValue(itemsSearch);
        itemsSearchField.setResponder(value -> {
            itemsSearch = value;
            itemsScrollRow = 0;
        });
        addRenderableWidget(itemsSearchField);
        setFocused(itemsSearchField);
        // One button that cycles the view: all, included, excluded, tracked.
        String showing = switch (itemsFilter) {
            case ALL -> "All items";
            case INCLUDED -> "Included only";
            case EXCLUDED -> "Excluded only";
            case TRACKED -> "Tracked only";
        };
        addRenderableWidget(CockpitButton.builder(Component.literal("Show: " + showing), ignored -> {
            itemsFilter = switch (itemsFilter) {
                case ALL -> ItemsFilter.INCLUDED;
                case INCLUDED -> ItemsFilter.EXCLUDED;
                case EXCLUDED -> ItemsFilter.TRACKED;
                case TRACKED -> ItemsFilter.ALL;
            };
            itemsScrollRow = 0;
            rebuildWidgets();
        }).bounds(left + searchW + gap, y, filterW, 18).build());

        // A second cycle button groups the grid like the creative menu.
        addRenderableWidget(CockpitButton.builder(Component.literal("Cat: " + itemsCat.label), ignored -> {
            ItemCat[] all = ItemCat.values();
            itemsCat = all[(itemsCat.ordinal() + 1) % all.length];
            itemsScrollRow = 0;
            rebuildWidgets();
        }).bounds(left + searchW + gap + filterW + gap, y, catW, 18).build());

        // Bottom action row: acts on every ticked item at once. Exclude /
        // Include / Clear edit the trade filter; Track / Untrack edit the watch
        // list; All / None manage the tick set.
        int ay = ui.bottom() - 22;
        int aGap = 4;
        int aw = (width - aGap * 6) / 7;
        int ax = left;
        String[] labels = {"Exclude", "Include", "Clear", "Track", "Untrack", "All", "None"};
        Runnable[] actions = {
            () -> applyItemsSelection(0),
            () -> applyItemsSelection(1),
            () -> applyItemsSelection(2),
            () -> trackSelected(true),
            () -> trackSelected(false),
            () -> { for (ItemEntry e : filteredItems()) itemsSelected.add(e.id()); rebuildWidgets(); },
            () -> { itemsSelected.clear(); rebuildWidgets(); },
        };
        for (int i = 0; i < labels.length; i++) {
            Runnable act = actions[i];
            addRenderableWidget(CockpitButton.builder(Component.literal(labels[i]), ignored -> act.run())
                    .bounds(ax, ay, aw, 18).build());
            ax += aw + aGap;
        }
    }

    /**
     * The effective watch list as bare ids. Once the list has been edited in
     * game ({@code watch.tracked_managed}) the hot list is the whole truth, even
     * empty; before that it mirrors the config file so an untouched build shows
     * its real set.
     */
    private java.util.Set<String> trackedBareSet() {
        if (Tuning.get("watch.tracked_managed") >= 0.5) {
            return Tuning.itemSet("watch.tracked");
        }
        java.util.Set<String> out = new java.util.HashSet<>();
        DoughBayConfig cfg = DoughBayClient.activeConfig();
        if (cfg != null) {
            for (String id : cfg.trackedCommodities()) {
                String b = Tuning.normalizeItem(id);
                if (!b.isEmpty()) out.add(b);
            }
        }
        return out;
    }

    /**
     * On the first in-game edit, copy the config watch list into the hot one and
     * mark it managed, so nothing is lost and later edits stick even to empty.
     */
    private void seedTrackedIfNeeded() {
        if (Tuning.get("watch.tracked_managed") >= 0.5) return;
        DoughBayConfig cfg = DoughBayClient.activeConfig();
        java.util.LinkedHashSet<String> bare = new java.util.LinkedHashSet<>();
        if (cfg != null) {
            for (String id : cfg.trackedCommodities()) {
                String b = Tuning.normalizeItem(id);
                if (!b.isEmpty()) bare.add(b);
            }
        }
        Tuning.setText("watch.tracked", String.join(", ", bare));
        Tuning.set("watch.tracked_managed", 1);
    }

    private void trackSelected(boolean track) {
        seedTrackedIfNeeded();
        for (String id : itemsSelected) {
            if (track) Tuning.addToList("watch.tracked", id);
            else Tuning.removeFromList("watch.tracked", id);
        }
        rebuildWidgets();
    }

    private void renderItems(GuiGraphicsExtractor graphics, UiLayout ui, int mouseX, int mouseY) {
        resetListGeometry();
        int x = ui.innerLeft();
        int width = ui.innerWidth();
        int top = ui.contentTop() + 40;         // below the search row and the count line
        int bottom = ui.bottom() - 28;           // leave the action row a strip
        List<ItemEntry> items = filteredItems();
        itemsFrame = items;

        java.util.Set<String> denySet = Tuning.itemSet("items.deny");
        java.util.Set<String> allowSet = Tuning.itemSet("items.allow");
        java.util.Set<String> trackedSet = trackedBareSet();
        String counts = items.size() + " item(s)  ·  " + denySet.size() + " excluded  ·  "
                + allowSet.size() + " included  ·  " + trackedSet.size() + " tracked";
        counts += itemsSelected.isEmpty()
                ? "  ·  tick icons, then use the buttons below"
                : "  ·  " + itemsSelected.size() + " selected";
        graphics.text(this.font, counts, x, ui.contentTop() + 28, SECONDARY);

        int cell = 20;
        int cols = Math.max(1, width / cell);
        int visibleRows = Math.max(1, (bottom - top) / cell);
        int totalRows = (items.size() + cols - 1) / cols;
        int maxScroll = Math.max(0, totalRows - visibleRows);
        if (itemsScrollRow > maxScroll) itemsScrollRow = maxScroll;
        if (itemsScrollRow < 0) itemsScrollRow = 0;
        itemsGridLeft = x;
        itemsGridTop = top;
        itemsCell = cell;
        itemsCols = cols;
        itemsVisibleRows = visibleRows;

        itemsHoveredId = null;
        int start = itemsScrollRow * cols;
        for (int r = 0; r < visibleRows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = start + r * cols + c;
                if (idx >= items.size()) break;
                ItemEntry e = items.get(idx);
                int cx = x + c * cell;
                int cy = top + r * cell;
                boolean hover = mouseX >= cx && mouseX < cx + cell && mouseY >= cy && mouseY < cy + cell;
                boolean ticked = itemsSelected.contains(e.id());
                boolean isTracked = trackedSet.contains(e.bare());
                graphics.fill(cx, cy, cx + cell - 1, cy + cell - 1,
                        ticked ? 0xFF2F4A6B : hover ? CARD : CARD_DARK);
                if (listMatches(denySet, e.bare())) {
                    graphics.outline(cx, cy, cell - 1, cell - 1, 0xFFE0553B);
                } else if (listMatches(allowSet, e.bare())) {
                    graphics.outline(cx, cy, cell - 1, cell - 1, GOOD);
                } else if (isTracked) {
                    graphics.outline(cx, cy, cell - 1, cell - 1, GOLD);
                }
                graphics.item(e.stack(), cx + 2, cy + 2);
                // A gold corner tick marks a tracked item even when a list colour
                // or the selection frame already owns its border.
                if (isTracked) graphics.fill(cx + cell - 6, cy + 1, cx + cell - 2, cy + 3, GOLD);
                // A ticked item gets a bright frame over any list colour, so the
                // selection reads even on an item already excluded or included.
                if (ticked) graphics.outline(cx, cy, cell - 1, cell - 1, 0xFF5B9BFF);
                if (hover) itemsHoveredId = e.id();
            }
        }
        // Scroll hint on the right edge when there is more below.
        if (maxScroll > 0) {
            String more = (itemsScrollRow < maxScroll ? "▼ " : "") + (itemsScrollRow > 0 ? "▲" : "");
            if (!more.isBlank()) {
                graphics.text(this.font, more.strip(), x + width - this.font.width(more.strip()), top - 10, MUTED);
            }
        }

        // The item under the cursor, named on the right of the count row, tinted
        // by which list it is already on.
        if (itemsHoveredId != null) {
            String bare = Tuning.normalizeItem(itemsHoveredId);
            String info = MarketQueryClient.displayName(itemsHoveredId)
                    + (listMatches(denySet, bare) ? " · EXCLUDED" : listMatches(allowSet, bare) ? " · INCLUDED" : "")
                    + (trackedSet.contains(bare) ? " · TRACKED" : "");
            String shown = truncate(info, width / 2);
            graphics.text(this.font, shown, ui.innerRight() - this.font.width(shown), ui.contentTop() + 28,
                    listMatches(denySet, bare) ? 0xFFE0553B : listMatches(allowSet, bare) ? GOOD
                            : trackedSet.contains(bare) ? GOLD : TEXT);
        }
    }

    // -------------------------------------------------------------- Logs tab

    private void renderLogs(GuiGraphicsExtractor graphics, UiLayout ui) {
        resetListGeometry();
        int x = ui.innerLeft();
        int width = ui.innerWidth();
        int top = ui.contentTop() + 7;
        AutomationSessionController.SessionSnapshot s = AUTOMATION_SESSION.snapshot();
        graphics.text(this.font, "LOGS  ·  what the bot is doing, newest first", x, top, GOLD);

        AutomationSessionController.State st = s.state();
        int stColor = st == AutomationSessionController.State.PAUSED ? WARN
                : st == AutomationSessionController.State.STOPPED ? MUTED : GOOD;
        graphics.text(this.font, "State: " + st.name(), x, top + 14, stColor);

        // The one line that matters when it stops: why, and what to do about it.
        String guidance = AUTOMATION_SESSION.pauseGuidance();
        int headerBottom = drawWrapped(graphics, guidance, x, top + 26, width, top + 62,
                st == AutomationSessionController.State.PAUSED ? WARN : SECONDARY);
        graphics.fill(x, headerBottom + 2, x + width, headerBottom + 3, PANEL_INNER_EDGE);

        int listTop = headerBottom + 8;
        int bottom = ui.bottom() - 10;
        List<AutomationLog.Line> lines = AutomationLog.recent();
        if (lines.isEmpty()) {
            graphics.text(this.font, "No steps captured yet this session.", x, listTop + 4, MUTED);
            return;
        }
        int rowH = 11;
        int visible = Math.max(1, (bottom - listTop) / rowH);
        int maxScroll = Math.max(0, lines.size() - visible);
        logsScroll = clamp(logsScroll, 0, maxScroll);
        int stampW = this.font.width("00:00:00 ");
        for (int r = 0; r < visible; r++) {
            int idx = lines.size() - 1 - (logsScroll + r);
            if (idx < 0) break;
            AutomationLog.Line ln = lines.get(idx);
            int ly = listTop + r * rowH;
            String low = ln.text().toLowerCase(Locale.ROOT);
            int color = low.contains("abort") || low.contains("refus") || low.contains("could not")
                    || low.contains("failed") || low.contains("stuck") ? BAD
                    : "PAUSED".equals(ln.state()) || low.contains("pause") ? WARN
                    : low.contains("bought") || low.contains("sold") || low.contains("listed") ? GOOD
                    : SECONDARY;
            graphics.text(this.font, clockOf(ln.at()), x, ly, MUTED);
            String tag = "[" + ln.state() + "] ";
            graphics.text(this.font, tag, x + stampW, ly, MUTED);
            int mx = x + stampW + this.font.width(tag);
            graphics.text(this.font, truncate(ln.text(), Math.max(20, width - (mx - x))), mx, ly, color);
        }
        if (maxScroll > 0) {
            String more = (logsScroll < maxScroll ? "▼" : "") + (logsScroll > 0 ? " ▲" : "");
            if (!more.isBlank()) {
                graphics.text(this.font, more.strip(),
                        x + width - this.font.width(more.strip()), top + 14, MUTED);
            }
        }
    }

    private static String clockOf(long at) {
        java.time.LocalTime t = java.time.Instant.ofEpochMilli(at)
                .atZone(java.time.ZoneId.systemDefault()).toLocalTime();
        return String.format(Locale.ROOT, "%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
    }

    /** Index into {@code itemsFrame} at a screen point, or -1 outside the grid. */
    private int itemsGridIndexAt(double px, double py) {
        if (itemsCell <= 0) return -1;
        int relX = (int) px - itemsGridLeft;
        int relY = (int) py - itemsGridTop;
        if (relX < 0 || relY < 0) return -1;
        int col = relX / itemsCell;
        int row = relY / itemsCell;
        if (col >= itemsCols || row >= itemsVisibleRows) return -1;
        return (itemsScrollRow + row) * itemsCols + col;
    }

    /**
     * Applies the search text and toggles.
     *
     * <p>Filtering is presentation only: nothing here changes what was
     * collected, valued, or admitted to opportunity detection.
     */
    private List<MarketStats> filteredMarkets(List<MarketStats> rows) {
        String needle = marketSearch.strip().toLowerCase(Locale.ROOT);
        List<MarketStats> kept = new ArrayList<>();
        for (MarketStats stats : rows) {
            if (hideLowConfidence && stats.confidence() < LOW_CONFIDENCE) continue;
            if (hideIlliquid && stats.salesPerHour() < ILLIQUID_SALES_PER_HOUR) continue;
            if (hideVariants && stats.itemKey() != null
                    && stats.itemKey().indexOf('#') >= 0) {
                continue;
            }
            if (!needle.isEmpty()
                    && !marketName(stats).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            kept.add(stats);
        }
        return kept;
    }

    private List<MarketStats> sortedMarkets(List<MarketStats> rows) {
        Comparator<MarketStats> order = switch (marketSort) {
            case MARKET -> Comparator.comparing(
                    DoughBayScreen::marketName, String.CASE_INSENSITIVE_ORDER);
            case QUICK -> Comparator.comparingDouble(MarketStats::quickSalePrice);
            case MEDIAN -> Comparator.comparingDouble(MarketStats::weightedMedian);
            case PATIENT -> Comparator.comparingDouble(MarketStats::patientSalePrice);
            case SALES -> Comparator.comparingDouble(MarketStats::salesPerHour);
            case VOLATILITY -> Comparator.comparingDouble(MarketStats::robustVolatility);
            case CONFIDENCE -> Comparator.comparingDouble(MarketStats::confidence);
        };
        if (marketSortDescending) order = order.reversed();
        // Break ties by name so equal values keep a fixed order between frames
        // rather than shuffling under the cursor.
        order = order.thenComparing(DoughBayScreen::marketName, String.CASE_INSENSITIVE_ORDER);
        List<MarketStats> sorted = new ArrayList<>(rows);
        sorted.sort(order);
        return sorted;
    }

    private static String sortLabel(MarketSort sort) {
        return switch (sort) {
            case MARKET -> "name";
            case QUICK -> "quick price";
            case MEDIAN -> "median";
            case PATIENT -> "patient price";
            case SALES -> "sales/hour";
            case VOLATILITY -> "volatility";
            case CONFIDENCE -> "confidence";
        };
    }

    /**
     * Draws the scroll track and thumb, and records the track for dragging.
     *
     * <p>The thumb is sized by the visible fraction and floored at a grabbable
     * height, so a list of hundreds still leaves something you can catch.
     */
    private void drawScrollBar(GuiGraphicsExtractor graphics, int tableRight,
                               int top, int bottom, int rowCount) {
        if (maxScroll <= 0 || bottom - top < 20) {
            scrollBarLeft = scrollBarRight = scrollBarTop = scrollBarBottom = 0;
            return;
        }
        int width = 5;
        scrollBarLeft = tableRight - width - 1;
        scrollBarRight = tableRight - 1;
        scrollBarTop = top;
        scrollBarBottom = bottom;

        int trackHeight = bottom - top;
        graphics.fill(scrollBarLeft, top, scrollBarRight, bottom, TRACK);

        int thumbHeight = Math.max(18,
                (int) ((long) trackHeight * visibleRows / Math.max(1, rowCount)));
        int travel = trackHeight - thumbHeight;
        int thumbTop = top + (int) ((long) travel * scroll / Math.max(1, maxScroll));
        graphics.fill(scrollBarLeft, thumbTop, scrollBarRight, thumbTop + thumbHeight, RANGE);
    }

    /** Maps a y inside the track to a scroll offset, centring the thumb on it. */
    private void scrollToTrackPosition(double mouseY) {
        int trackHeight = scrollBarBottom - scrollBarTop;
        if (trackHeight <= 0 || maxScroll <= 0) return;
        double fraction = (mouseY - scrollBarTop) / trackHeight;
        scroll = clamp((int) Math.round(fraction * maxScroll), 0, maxScroll);
    }

    private boolean withinScrollBar(double mouseX, double mouseY) {
        return scrollBarRight > scrollBarLeft
                && mouseX >= scrollBarLeft - 2 && mouseX <= scrollBarRight + 2
                && mouseY >= scrollBarTop && mouseY <= scrollBarBottom;
    }

    private void drawCompactHeader(GuiGraphicsExtractor graphics, int left, int y,
                                   int[] columns, String[] labels) {
        for (int i = 0; i < labels.length; i++) {
            cell(graphics, labels[i], left, columns[i], y, GOLD, i > 0);
        }
    }



    private void renderEmptyState(GuiGraphicsExtractor graphics, int x, int y, int width,
                                  String heading, String lineOne, String lineTwo) {
        int height = 92;
        card(graphics, x, y, width, height, CARD_DARK);
        graphics.text(this.font, heading, x + 12, y + 12, GOLD);
        int yy = drawWrapped(graphics, lineOne, x + 12, y + 32,
                width - 24, y + height - 12, TEXT);
        drawWrapped(graphics, lineTwo, x + 12, yy + 3,
                width - 24, y + height - 8, MUTED);
    }

    private void resetListGeometry() {
        listTop = 0;
        listBottom = 0;
        listRowHeight = 1;
        firstVisibleIndex = 0;
        visibleRows = 0;
        maxScroll = 0;
        scroll = 0;
    }

    private int rowAt(double mouseY, int rowCount) {
        if (listBottom <= listTop || mouseY < listTop || mouseY >= listBottom) return -1;
        int index = firstVisibleIndex + (int) ((mouseY - listTop) / listRowHeight);
        return index >= 0 && index < rowCount ? index : -1;
    }

    // ------------------------------------------------------------------- drawing

    private void scaledText(GuiGraphicsExtractor graphics, String text,
                            int x, int y, float scale, int color) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale);
        graphics.text(this.font, text, 0, 0, color);
        graphics.pose().popMatrix();
    }

    private static String keyName(KeyMapping mapping) {
        return mapping == null ? "unbound" : mapping.getTranslatedKeyMessage().getString();
    }

    /** "+", "++" or "+++" for the rank; nothing for no rank or one the badge does not know. */
    private static String rankBadge(String rank) {
        if (rank == null) return "";
        int plus = 0;
        for (char c : rank.toCharArray()) if (c == '+') plus++;
        if (plus == 0) {
            String lower = rank.toLowerCase(Locale.ROOT);
            int i = 0;
            while ((i = lower.indexOf("plus", i)) >= 0) {
                plus++;
                i += 4;
            }
        }
        return "+".repeat(Math.min(3, plus));
    }

    // ------------------------------------------------------------ Payroll tab

    private EditBox payrollField(int x, int y, int width, String draft, java.util.function.Consumer<String> responder) {
        EditBox box = new EditBox(this.font, x, y, width, 20, Component.literal(""));
        box.setMaxLength(24);
        box.setValue(draft);
        box.setResponder(responder);
        addRenderableWidget(box);
        return box;
    }

    private void initPayrollWidgets(UiLayout ui) {
        int y = ui.bottom() - 27;
        int x = ui.innerLeft();
        payrollFieldX = new int[6];
        payrollFieldX[0] = x;
        payrollField(x, y, 96, payrollName, v -> payrollName = v);
        x += 100;
        payrollFieldX[1] = x;
        payrollField(x, y, 44, payrollPercent, v -> payrollPercent = v);
        x += 48;
        payrollFieldX[2] = x;
        Button trigger = CockpitButton.builder(Component.literal(triggerLabel(payrollTrigger)), b -> {
            payrollTrigger = switch (payrollTrigger) {
                case "HOURS" -> "SALES";
                case "SALES" -> "BALANCE";
                default -> "HOURS";
            };
            b.setMessage(Component.literal(triggerLabel(payrollTrigger)));
        }).bounds(x, y, 78, 20).build();
        addRenderableWidget(trigger);
        x += 82;
        payrollFieldX[3] = x;
        payrollField(x, y, 60, payrollValue, v -> payrollValue = v);
        x += 64;
        payrollFieldX[4] = x;
        payrollField(x, y, 72, payrollReserve, v -> payrollReserve = v);
        x += 76;
        payrollFieldX[5] = x;
        payrollField(x, y, 72, payrollCap, v -> payrollCap = v);
        x += 76;
        Button online = CockpitButton.builder(Component.literal(payrollOnline ? "Online only" : "Any time"), b -> {
            payrollOnline = !payrollOnline;
            b.setMessage(Component.literal(payrollOnline ? "Online only" : "Any time"));
        }).bounds(x, y, 84, 20).build();
        addRenderableWidget(online);
        x += 88;
        addRenderableWidget(CockpitButton.builder(Component.literal(selectedRule == 0 ? "Add" : "Save"), ignored -> savePayrollRule())
                .bounds(x, y, 56, 20).build());

        // Row actions on the title line, right-aligned.
        Payroll payroll = DoughBayClient.payroll();
        int ty = ui.contentTop() + 3;
        int rx = ui.innerRight();
        if (payroll != null && selectedRule != 0) {
            dev.doughbay.storage.PayrollRepository.Rule rule = null;
            for (var r : payroll.rules()) if (r.ruleId() == selectedRule) rule = r;
            if (rule != null) {
                final long id = rule.ruleId();
                final boolean paused = rule.paused();
                rx -= 58;
                addRenderableWidget(CockpitButton.builder(Component.literal("Delete"), ignored -> {
                    payroll.delete(id);
                    payrollMessage = "Deleted the rule";
                    selectRule(0);
                }).bounds(rx, ty, 58, 18).build());
                rx -= 64;
                addRenderableWidget(CockpitButton.builder(Component.literal(paused ? "Resume" : "Pause"), ignored -> {
                    payroll.setPaused(id, !paused);
                    payrollMessage = paused ? "Rule resumed" : "Rule paused";
                    rebuildWidgets();
                }).bounds(rx, ty, 60, 18).build());
            }
        }
        if (payroll != null && payroll.pendingConfirmation() != null) {
            Payroll.PendingConfirmation p = payroll.pendingConfirmation();
            String label = "Confirm first payment: " + p.name() + " " + money(p.amount());
            int w = this.font.width(label) + 16;
            rx -= w + 4;
            addRenderableWidget(CockpitButton.builder(Component.literal(label), ignored -> {
                payroll.confirm(p.ruleId());
                payrollMessage = "Confirmed; " + p.name() + " is paid on the next check";
                rebuildWidgets();
            }).bounds(Math.max(ui.innerLeft(), rx), ty, w, 18).build());
        }
    }

    private static String triggerLabel(String kind) {
        return switch (kind) {
            case "SALES" -> "Every N sales";
            case "BALANCE" -> "Balance ≥ $";
            default -> "Every N hours";
        };
    }

    private static String triggerText(dev.doughbay.storage.PayrollRepository.Rule r) {
        return switch (r.triggerKind()) {
            case "SALES" -> "every " + Math.round(r.triggerValue()) + " sales";
            case "BALANCE" -> "balance ≥ " + money(r.triggerValue());
            default -> "every " + trimNumber(r.triggerValue()) + " h";
        };
    }

    private static String trimNumber(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    /** "1.5m", "250k", "$12,000" and plain numbers all read as money. */
    private static double parseMoney(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace("$", "").replace(",", "").replace("_", "");
        double scale = 1;
        if (t.endsWith("k")) { scale = 1e3; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("m")) { scale = 1e6; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("b")) { scale = 1e9; t = t.substring(0, t.length() - 1); }
        try {
            return Double.parseDouble(t.trim()) * scale;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private void selectRule(long ruleId) {
        selectedRule = ruleId;
        Payroll payroll = DoughBayClient.payroll();
        if (ruleId != 0 && payroll != null) {
            for (var r : payroll.rules()) {
                if (r.ruleId() != ruleId) continue;
                payrollName = r.name();
                payrollPercent = trimNumber(r.percent());
                payrollTrigger = r.triggerKind();
                payrollValue = trimNumber(r.triggerValue());
                payrollReserve = String.valueOf(r.reserve());
                payrollCap = String.valueOf(r.dailyCap());
                payrollOnline = r.onlyOnline();
            }
        } else if (ruleId == 0) {
            payrollName = "";
        }
        rebuildWidgets();
    }

    private void savePayrollRule() {
        Payroll payroll = DoughBayClient.payroll();
        if (payroll == null) return;
        String name = payrollName.trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            payrollMessage = "Name must be a Minecraft username (3 to 16 letters, digits or _)";
            return;
        }
        double percent = parseMoney(payrollPercent);
        double value = parseMoney(payrollValue);
        double reserve = parseMoney(payrollReserve);
        double cap = parseMoney(payrollCap);
        if (!(percent > 0 && percent <= 100)) {
            payrollMessage = "Share must be between 0 and 100 percent of profit";
            return;
        }
        if (!(value > 0)) {
            payrollMessage = "The trigger value must be positive";
            return;
        }
        if (Double.isNaN(reserve) || Double.isNaN(cap)) {
            payrollMessage = "Reserve and daily cap must be amounts (0 for none)";
            return;
        }
        dev.doughbay.storage.PayrollRepository.Rule existing = null;
        for (var r : payroll.rules()) if (r.ruleId() == selectedRule) existing = r;
        long now = System.currentTimeMillis();
        boolean sameName = existing != null && existing.name().equalsIgnoreCase(name);
        var rule = new dev.doughbay.storage.PayrollRepository.Rule(
                existing == null ? now : existing.ruleId(), name, percent, payrollTrigger, value, payrollOnline,
                Math.max(0, Math.round(reserve)), Math.max(0, Math.round(cap)),
                existing != null && existing.paused(), true,
                existing == null ? now : existing.createdAt(), existing == null ? 0 : existing.lastPaidAt(),
                existing == null ? 0 : existing.totalPaid());
        payroll.addOrUpdate(rule);
        payrollMessage = (existing == null ? "Added " : "Saved ") + name + ": " + trimNumber(percent) + "% of profit, "
                + triggerText(rule) + " · pays while the session runs";
        selectRule(0);
    }

    /** The auction's asking price per single item for a market, from the best stack size the statistics have; 0 when none. */
    private static double askPerItem(MarketWatcher.Snapshot snapshot, String itemId) {
        if (snapshot == null) return 0;
        double best = 0;
        for (dev.doughbay.core.model.MarketStats m : snapshot.markets()) {
            if (!m.itemKey().equals(itemId) || !m.hasPrices() || !(m.quickSalePrice() > 0)) continue;
            int per = switch (m.bucket()) {
                case X64 -> 64;
                case X32 -> 32;
                case X16 -> 16;
                default -> 1;
            };
            double unit = m.quickSalePrice() / per;
            if (best == 0 || m.sampleCount() > 20) best = unit;
        }
        return best;
    }

    private void renderOrders(GuiGraphicsExtractor graphics, MarketWatcher.Snapshot snapshot, UiLayout ui, int mouseX, int mouseY) {
        resetListGeometry();
        OrderBook book = DoughBayClient.orderBook();
        int x = ui.innerLeft();
        int width = ui.innerWidth();
        int right = ui.innerRight();
        int top = ui.contentTop() + 7;
        long now = System.currentTimeMillis();
        graphics.text(this.font, "ORDERS  ·  what buyers are paying right now, beside the auction's asking price", x, top, GOLD);
        String stamp = book == null || book.readAt() == 0 ? "not read yet; the bot reads /orders between trades"
                : "read " + relativeAge(Math.max(0, now - book.readAt())) + "  ·  " + book.status();
        graphics.text(this.font, stamp, right - this.font.width(stamp), top, MUTED);
        List<OrderBook.Order> orders = book == null ? List.of() : new ArrayList<>(book.orders());
        record Row(OrderBook.Order order, double ask, double spread) {
        }
        List<Row> rows = new ArrayList<>();
        for (OrderBook.Order o : orders) {
            double ask = o.parts().isEmpty() ? askPerItem(snapshot, o.itemId()) : 0;
            double spread = ask > 0 ? (o.unitPrice() - ask) / ask : Double.NaN;
            rows.add(new Row(o, ask, spread));
        }
        rows.sort((a, b) -> {
            boolean an = Double.isNaN(a.spread()), bn = Double.isNaN(b.spread());
            if (an != bn) return an ? 1 : -1;
            if (!an) return Double.compare(b.spread(), a.spread());
            return Long.compare(b.order().value(), a.order().value());
        });
        int y = top + 22;
        int rowHeight = 13;
        int listBottom = ui.bottom() - 10;
        List<AutomatedExecutionDriver.OwnOrderRow> bids = AUTOMATION_SESSION.ownOrderViews();
        if (!bids.isEmpty() || Tuning.get("orders.bid") >= 0.5) {
            int bidHeight = 27 + Math.max(1, bids.size()) * rowHeight + 6;
            card(graphics, x, y, width, bidHeight, CARD_DARK);
            long held = 0;
            for (var b : bids) held += b.unitPrice() * b.remaining();
            sectionTitle(graphics, "YOUR BIDS  ·  " + bids.size() + " open  ·  " + money(held) + " held"
                    + (AUTOMATION_SESSION.ownOrdersReadAt() > 0 ? "  ·  read " + relativeAge(Math.max(0, now - AUTOMATION_SESSION.ownOrdersReadAt())) : ""), x, y, width);
            int by = y + 27;
            if (bids.isEmpty()) graphics.text(this.font, "No bids yet. The desk places them after the next order read.", x + 9, by + 1, MUTED);
            for (var b : bids) {
                if (by + rowHeight > y + bidHeight - 4) break;
                net.minecraft.world.item.ItemStack stack = stackFor(b.itemKey(), 1);
                if (stack != null) graphics.item(stack, x + 9, by - 3);
                graphics.text(this.font, truncate(title(b.itemId()), (int) (width * 0.35)), x + 29, by + 2, TEXT);
                cell(graphics, money(b.unitPrice()) + " each", x + 9, (int) (width * 0.55), by + 2, GOLD, true);
                cell(graphics, b.delivered() + "/" + b.requested() + " delivered", x + 9, (int) (width * 0.78), by + 2, SECONDARY, true);
                cell(graphics, b.expiresInMillis() > 0 ? "expires in " + relativeAge(b.expiresInMillis()) : "", x + 9, width - 18, by + 2, MUTED, true);
                by += rowHeight;
            }
            y += bidHeight + 8;
        }
        int cardHeight = Math.min(listBottom - y, 27 + 12 + Math.max(1, rows.size()) * rowHeight + 6);
        card(graphics, x, y, width, cardHeight, CARD);
        sectionTitle(graphics, "OPEN BUY ORDERS  ·  " + rows.size() + "  ·  green spread: the order pays more than the auction asks", x, y, width);
        int innerX = x + 9;
        int innerWidth = width - 18;
        int c1 = (int) (innerWidth * 0.40), c2 = (int) (innerWidth * 0.52), c3 = (int) (innerWidth * 0.64),
                c4 = (int) (innerWidth * 0.76), c5 = (int) (innerWidth * 0.87), c6 = innerWidth;
        int hy = y + 27;
        graphics.text(this.font, "ITEM  ·  REQUIRED PARTS", innerX, hy, MUTED);
        cell(graphics, "PAYS EACH", innerX, c1, hy, MUTED, true);
        cell(graphics, "WANTS", innerX, c2, hy, MUTED, true);
        cell(graphics, "AUCTION ASK", innerX, c3, hy, MUTED, true);
        cell(graphics, "SPREAD", innerX, c4, hy, MUTED, true);
        cell(graphics, "VALUE", innerX, c5, hy, MUTED, true);
        cell(graphics, "PAGE", innerX, c6, hy, MUTED, true);
        int yy = hy + 12;
        if (rows.isEmpty()) {
            graphics.text(this.font, "Nothing read yet. With the session running, the bot opens /orders every few minutes and this fills in.", innerX, yy + 1, MUTED);
        }
        for (Row r : rows) {
            if (yy + rowHeight > y + cardHeight - 4) break;
            OrderBook.Order o = r.order();
            net.minecraft.world.item.ItemStack stack = stackFor(o.itemKey(), 1);
            if (stack != null) graphics.item(stack, innerX, yy - 3);
            String label = title(o.itemId()) + (o.parts().isEmpty() ? "" : "  ·  " + String.join(", ", o.parts()));
            graphics.text(this.font, truncate(label, c1 - 70), innerX + 20, yy + 2, o.parts().isEmpty() ? TEXT : SECONDARY);
            cell(graphics, money(o.unitPrice()), innerX, c1, yy + 2, GOLD, true);
            cell(graphics, compactCount(o.remaining()), innerX, c2, yy + 2, SECONDARY, true);
            cell(graphics, r.ask() > 0 ? money(r.ask()) : "—", innerX, c3, yy + 2, SECONDARY, true);
            String spread = Double.isNaN(r.spread()) ? "—" : (r.spread() >= 0 ? "+" : "") + Math.round(r.spread() * 100) + "%";
            cell(graphics, spread, innerX, c4, yy + 2, Double.isNaN(r.spread()) ? MUTED : r.spread() > 0.03 ? GOOD : r.spread() < 0 ? BAD : SECONDARY, true);
            cell(graphics, money(o.value()), innerX, c5, yy + 2, SECONDARY, true);
            cell(graphics, String.valueOf(o.page()), innerX, c6, yy + 2, MUTED, true);
            yy += rowHeight;
        }
    }

    private static String title(String itemId) {
        String name = itemId.substring(itemId.indexOf(':') + 1).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String w : name.split(" ")) {
            if (w.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    private static String compactCount(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fm", n / 1e6);
        if (n >= 10_000) return String.format(Locale.ROOT, "%.1fk", n / 1e3);
        return String.valueOf(n);
    }

    private void renderPayroll(GuiGraphicsExtractor graphics, UiLayout ui, int mouseX, int mouseY) {
        resetListGeometry();
        payrollRowBounds.clear();
        Payroll payroll = DoughBayClient.payroll();
        int x = ui.innerLeft();
        int width = ui.innerWidth();
        int right = ui.innerRight();
        int top = ui.contentTop() + 7;
        graphics.text(this.font, "PAYROLL  ·  each name gets a share of the profit since its last payout", x, top, GOLD);
        long now = System.currentTimeMillis();
        List<dev.doughbay.storage.PayrollRepository.Rule> rules = payroll == null ? List.of() : payroll.rules();
        List<dev.doughbay.storage.PayrollRepository.Payment> payments = payroll == null ? List.of() : payroll.payments();

        int y = top + 22;
        int rowHeight = 13;
        int cardHeight = 27 + 12 + Math.max(1, rules.size()) * rowHeight + 6;
        card(graphics, x, y, width, cardHeight, CARD);
        sectionTitle(graphics, "RULES  ·  " + rules.size() + (AUTOMATION_SESSION.armed()
                ? "  ·  paying while the session runs" : "  ·  pays only after Start or Resume"), x, y, width);
        int innerX = x + 9;
        int innerWidth = width - 18;
        int c1 = (int) (innerWidth * 0.18), c2 = (int) (innerWidth * 0.26), c3 = (int) (innerWidth * 0.46),
                c4 = (int) (innerWidth * 0.58), c5 = (int) (innerWidth * 0.70), c6 = (int) (innerWidth * 0.82), c7 = innerWidth;
        int hy = y + 27;
        graphics.text(this.font, "NAME", innerX, hy, MUTED);
        graphics.text(this.font, "SHARE", innerX + c1, hy, MUTED);
        graphics.text(this.font, "TRIGGER", innerX + c2, hy, MUTED);
        graphics.text(this.font, "RESERVE", innerX + c3, hy, MUTED);
        graphics.text(this.font, "DAILY CAP", innerX + c4, hy, MUTED);
        graphics.text(this.font, "PAID", innerX + c5, hy, MUTED);
        graphics.text(this.font, "LAST", innerX + c6, hy, MUTED);
        cell(graphics, "STATUS", innerX, c7, hy, MUTED, true);
        int yy = hy + 12;
        if (rules.isEmpty()) {
            graphics.text(this.font, "No rules yet. Fill in the bar below and press Add: name, share of profit, trigger, value, reserve, daily cap.", innerX, yy + 1, MUTED);
        }
        for (var r : rules) {
            boolean hover = mouseX >= innerX && mouseX < innerX + innerWidth && mouseY >= yy && mouseY < yy + rowHeight;
            boolean selected = r.ruleId() == selectedRule;
            if (hover || selected) graphics.fill(innerX - 3, yy - 1, innerX + innerWidth + 3, yy + rowHeight - 1, selected ? 0xFF2A3140 : ROW_HOVER);
            payrollRowBounds.add(new long[] {yy, yy + rowHeight, r.ruleId()});
            String status = r.paused() ? "paused" : "active";
            int statusColor = r.paused() ? WARN : GOOD;
            graphics.text(this.font, truncate(r.name() + (r.onlyOnline() ? "  (online only)" : ""), c1 - 6), innerX, yy + 2, TEXT);
            graphics.text(this.font, trimNumber(r.percent()) + "%", innerX + c1, yy + 2, GOLD);
            graphics.text(this.font, truncate(triggerText(r), c3 - c2 - 6), innerX + c2, yy + 2, SECONDARY);
            graphics.text(this.font, r.reserve() > 0 ? money(r.reserve()) : "none", innerX + c3, yy + 2, SECONDARY);
            graphics.text(this.font, r.dailyCap() > 0 ? money(r.dailyCap()) : "none", innerX + c4, yy + 2, SECONDARY);
            graphics.text(this.font, money(r.totalPaid()), innerX + c5, yy + 2, GOOD);
            graphics.text(this.font, r.lastPaidAt() > 0 ? relativeAge(Math.max(0, now - r.lastPaidAt())) : "never", innerX + c6, yy + 2, SECONDARY);
            cell(graphics, status, innerX, c7, yy + 2, statusColor, true);
            yy += rowHeight;
        }
        y += cardHeight + 8;

        int paymentsBottom = ui.bottom() - 62;
        int shown = Math.max(0, Math.min(payments.size(), (paymentsBottom - y - 33) / rowHeight));
        int payHeight = 27 + Math.max(1, shown) * rowHeight + 6;
        if (y + payHeight <= paymentsBottom + 6) {
            card(graphics, x, y, width, payHeight, CARD_DARK);
            sectionTitle(graphics, "PAYMENTS  ·  written before /pay goes out, settled by the chat receipt", x, y, width);
            yy = y + 27;
            if (shown == 0) {
                graphics.text(this.font, "No payments yet.", innerX, yy + 1, MUTED);
            }
            for (int i = 0; i < shown; i++) {
                var p = payments.get(i);
                int color = "PAID".equals(p.status()) ? GOOD : "MANUAL".equals(p.status()) ? SECONDARY : "FAILED".equals(p.status()) ? BAD : WARN;
                graphics.text(this.font, relativeAge(Math.max(0, now - p.requestedAt())), innerX, yy + 2, MUTED);
                graphics.text(this.font, truncate(p.name(), c2 - c1 - 6), innerX + c1, yy + 2, TEXT);
                graphics.text(this.font, money(p.amount()), innerX + c2, yy + 2, GOLD);
                graphics.text(this.font, p.status(), innerX + c3, yy + 2, color);
                graphics.text(this.font, truncate(p.note().isBlank() ? "of " + money(p.profit()) + " profit" : p.note(), innerWidth - c4), innerX + c4, yy + 2, SECONDARY);
                yy += rowHeight;
            }
        }

        // The bar: labels above the fields, messages above the labels.
        int barY = ui.bottom() - 27;
        if (payrollFieldX.length == 6) {
            String[] labels = {"name", "share %", "trigger", "value", "reserve $", "daily cap $"};
            for (int i = 0; i < labels.length; i++) graphics.text(this.font, labels[i], payrollFieldX[i] + 1, barY - 10, MUTED);
        }
        String live = payroll == null ? "" : payroll.status();
        if (!payrollMessage.isBlank()) graphics.text(this.font, truncate(payrollMessage, width), x, barY - 34, GOOD);
        if (!live.isBlank()) graphics.text(this.font, truncate(live, width), x, barY - 23, live.contains("failed") || live.contains("error") ? BAD : SECONDARY);
        graphics.text(this.font, truncate("Anything not paid out stays in the bot as working capital. Every share is of the profit since that name's last payout.", width),
                right - this.font.width(truncate("Anything not paid out stays in the bot as working capital. Every share is of the profit since that name's last payout.", width)), top + 11, MUTED);
    }

    private void card(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) return;
        UiTheme.panel(graphics, x, y, width, height, color);
    }

    private void sectionTitle(GuiGraphicsExtractor graphics, String title,
                              int x, int y, int width) {
        graphics.text(this.font, truncate(title, width - 16), x + 8, y + 7, GOLD);
        graphics.fill(x + 8, y + 19, x + width - 8, y + 20, PANEL_INNER_EDGE);
    }

    private void cell(GuiGraphicsExtractor graphics, String value, int left, int column,
                      int y, int color, boolean rightAlign) {
        int x = left + column;
        if (rightAlign) x -= this.font.width(value);
        graphics.text(this.font, value, x, y, color);
    }

    private int kvRight(GuiGraphicsExtractor graphics, int x, int y, int width,
                        String label, String value, int valueColor) {
        String safeValue = value == null || value.isBlank() ? "unknown" : value;

        // Give the value whatever the label does not need, rather than a fixed
        // half. These values are mostly numbers, and the previous fixed split
        // trimmed them from the front when they overflowed — rendering "142 /
        // 27 sellers" as "2 / 27 sellers", which is not a clipped label but a
        // different and far worse number.
        int gap = 6;
        int labelWidth = Math.min(this.font.width(label), Math.max(18, width / 2));
        int valueRoom = Math.max(22, width - labelWidth - gap);
        if (this.font.width(safeValue) > valueRoom) {
            // Still too long: trim the tail, where losing characters reads as
            // truncation instead of as a smaller value.
            safeValue = truncate(safeValue, valueRoom);
        }

        graphics.text(this.font,
                this.font.plainSubstrByWidth(label,
                        Math.max(18, width - this.font.width(safeValue) - gap)),
                x, y, MUTED);
        graphics.text(this.font, safeValue, x + width - this.font.width(safeValue), y, valueColor);
        return y + 11;
    }

    private int statusLine(GuiGraphicsExtractor graphics, int x, int y, int width,
                           String text, int color) {
        graphics.text(this.font, this.font.plainSubstrByWidth(text, width), x, y, color);
        return y + 11;
    }

    private int drawWrapped(GuiGraphicsExtractor graphics, String text, int x, int y,
                            int width, int maxY, int color) {
        if (width <= 0 || y > maxY) return y;
        for (FormattedCharSequence line : this.font.split(Component.literal(text), width)) {
            if (y > maxY - this.font.lineHeight) break;
            graphics.text(this.font, line, x, y, color);
            y += 11;
        }
        return y;
    }

    private int centeredTextY(int rowY, int rowHeight) {
        return rowY + Math.max(0, (rowHeight - this.font.lineHeight) / 2);
    }

    // -------------------------------------------------------------------- values

    private String actionStateKey(MarketWatcher.Snapshot snapshot) {
        ExecutionStatus status = DRIVER.inspect();
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        return snapshot.updatedAt() + "|" + snapshot.status() + "|" + snapshot.demo()
                + "|" + isFresh(snapshot) + "|" + status.state() + "|" + status.armed()
                + "|" + session.state() + "|" + session.runMode()
                + "|" + session.unresolvedExposure() + "|" + session.resumable()
                + "|" + session.persistenceStatus() + "|" + session.recoveredSession()
                + "|" + session.manualResolutionStage()
                + "|" + executionBlocker(snapshot, selected);
    }

    private String automationStateKey(MarketWatcher.Snapshot snapshot) {
        ExecutionStatus status = DRIVER.inspect();
        AutomatedExecutionDriver.PreflightReport report = DRIVER.lastPreflightReport();
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        String listingKey = automationCandidate == null
                ? "none" : Objects.toString(automationCandidate.listing().listingKey(), "missing");
        return snapshot.updatedAt() + "|" + snapshot.status() + "|" + snapshot.demo()
                + "|" + automationMode
                + "|" + listingKey
                + "|" + (currentOpportunity(snapshot, automationCandidate) != null)
                + "|" + DRIVER.operationIntent()
                + "|" + status.state() + "|" + status.armed()
                + "|" + status.description()
                + "|" + report.outcome() + "|" + report.updatedAtMillis()
                + "|" + session.state() + "|" + session.runMode()
                + "|" + session.detail() + "|" + session.pendingListingKey()
                + "|" + session.boundListingKey() + "|" + session.reconciliationStatus()
                + "|" + session.tradesStarted() + "|" + session.committedSpend()
                + "|" + session.attemptedListings() + "|" + session.cooldownUntilMillis()
                + "|" + session.updatedAtMillis() + "|" + session.unresolvedExposure()
                + "|" + session.resumable() + "|" + session.requestedCoverageItemId()
                + "|" + session.persistenceStatus() + "|" + session.recoveredSession()
                + "|" + session.recoveredExposureCount()
                + "|" + session.manualResolutionStage()
                + "|" + (session.position() == null ? "none"
                : session.position().positionId() + ":" + session.position().status())
                + "|" + API_KEY_TESTER.report().state()
                + "|" + API_KEY_TESTER.report().updatedAtMillis()
                + "|" + apiKeyMessage
                + "|" + (DoughBayClient.activeConfig() != null
                && DoughBayClient.activeConfig().hasApiKey())
                + "|" + apiKeyDraft.isBlank()
                + "|" + safePolicyBlocker()
                + "|" + sessionStartBlocker(snapshot)
                + "|" + preflightBlocker(snapshot, automationCandidate)
                + "|" + executionBlocker(snapshot, automationCandidate);
    }

    private String preflightBlocker(MarketWatcher.Snapshot snapshot,
                                    Opportunity opportunity) {
        if (opportunity == null || opportunity.listing() == null) {
            return "Choose an opportunity before running a preflight";
        }
        String listingKey = opportunity.listing().listingKey();
        if (snapshot.demo() || (listingKey != null && listingKey.startsWith("demo-"))) {
            return "Demo data is preview-only and cannot send /ah search";
        }
        if (listingKey == null || listingKey.isBlank()) {
            return "Listing identity is missing; preflight is locked";
        }
        if (opportunity.listing().sellerName() == null
                || opportunity.listing().sellerName().isBlank()) {
            return "An exact seller is required for a no-guess preflight";
        }
        if (snapshot.status().startsWith("STOPPED")) {
            return "Market watcher is stopped; preflight is locked";
        }
        if (!isFresh(snapshot)) {
            return "Market snapshot is stale; wait for a fresh scan";
        }
        if (currentOpportunity(snapshot, opportunity) == null) {
            return "Candidate expired or changed; choose a fresh exact listing";
        }
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        if (session.state() != AutomationSessionController.State.STOPPED
                || session.unresolvedExposure()) {
            return "Stop or resolve the automation session before running a preflight";
        }
        String environment = DRIVER.currentPreflightBlocker();
        if (environment != null) return environment;
        if (DRIVER.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) {
            return "Another one-shot operation is already in progress";
        }
        return null;
    }

    private String executionBlocker(MarketWatcher.Snapshot snapshot, Opportunity opportunity) {
        String listingKey = opportunity == null ? null : opportunity.listing().listingKey();
        AutomationSessionController.SessionSnapshot session = AUTOMATION_SESSION.snapshot();
        if (session.state() != AutomationSessionController.State.STOPPED) {
            return session.state() == AutomationSessionController.State.PAUSED
                    ? "Automation is PAUSED; resolve or resume its position first"
                    : "An Automation session is already active";
        }
        if (!session.pendingListingKey().isBlank()) {
            return "Automation still has a pending exact listing";
        }
        if (session.position() != null && !session.position().status().isClosed()) {
            return "Automation still has an unresolved position";
        }
        if (snapshot.demo() || (listingKey != null && listingKey.startsWith("demo-"))) {
            return "Demo data is preview-only and can never execute";
        }
        if (!DRIVER.authorizedExecutionEnabled()) {
            return "Observe mode is active; live execution is disabled";
        }
        String environmentBlocker = DRIVER.currentEnvironmentBlocker();
        if (environmentBlocker != null) {
            return environmentBlocker;
        }
        if (listingKey == null || listingKey.isBlank()) {
            return "Listing identity is missing; execution is locked";
        }
        if (snapshot.status().startsWith("STOPPED")) {
            return "Market watcher is stopped; execution is locked";
        }
        if (!isFresh(snapshot)) {
            return "Market snapshot is stale; wait for a fresh scan";
        }
        if (currentOpportunity(snapshot, opportunity) == null) {
            return "Signal expired; this exact listing is no longer in the current scan";
        }
        return null;
    }

    /**
     * Rebinds a detail selection to the latest immutable watcher snapshot.
     * Price, item, count, and seller must all still match; a reused listing key
     * cannot make stale UI data executable.
     */
    /**
     * Re-resolves a saved candidate against the newest snapshot.
     *
     * <p>Falls back to the active book when the listing is still live but no
     * longer scores as an opportunity. A preflight only reads the auction
     * screen, so it must not require a profitable trade to exist — otherwise
     * the one check that proves GUI parsing works can only run at the moments
     * it is least needed.
     */
    private static Opportunity currentOpportunity(MarketWatcher.Snapshot snapshot,
                                                  Opportunity candidate) {
        if (snapshot == null || candidate == null) return null;
        var expected = candidate.listing();
        for (Opportunity current : snapshot.opportunities()) {
            var observed = current.listing();
            if (Objects.equals(expected.listingKey(), observed.listingKey())
                    && Objects.equals(expected.itemKey(), observed.itemKey())
                    && Objects.equals(expected.itemId(), observed.itemId())
                    && expected.itemCount() == observed.itemCount()
                    && expected.totalPrice() == observed.totalPrice()
                    && Objects.equals(expected.sellerUuid(), observed.sellerUuid())
                    && Objects.equals(expected.sellerName(), observed.sellerName())) {
                return current;
            }
        }
        for (Listing observed : snapshot.activeListings()) {
            if (Objects.equals(expected.listingKey(), observed.listingKey())
                    && Objects.equals(expected.itemKey(), observed.itemKey())
                    && Objects.equals(expected.itemId(), observed.itemId())
                    && expected.itemCount() == observed.itemCount()
                    && expected.totalPrice() == observed.totalPrice()
                    && Objects.equals(expected.sellerUuid(), observed.sellerUuid())
                    && Objects.equals(expected.sellerName(), observed.sellerName())) {
                return rehearsalCandidate(observed);
            }
        }
        return null;
    }

    private static boolean isFresh(MarketWatcher.Snapshot snapshot) {
        return snapshot.updatedAt() > 0
                && System.currentTimeMillis() - snapshot.updatedAt() <= EXECUTION_FRESHNESS_MILLIS;
    }

    private static int priceX(double value, double lo, double hi, int left, int right) {
        double ratio = (value - lo) / (hi - lo);
        ratio = Math.max(0, Math.min(1, ratio));
        return clamp(left + (int) Math.round(ratio * (right - left)), left, right - 1);
    }

    private static int confidenceColor(double confidence) {
        if (confidence >= 0.8) return GOOD;
        if (confidence >= 0.6) return TEXT;
        return WARN;
    }

    /**
     * The vanilla icon for a namespaced item id.
     *
     * <p>Empty for anything the registry does not know: DoughBay tracks ids
     * observed in API responses, which may name items this client's version or
     * modset has never heard of. A missing icon must degrade to a plain text
     * row, never to a crash inside the render loop.
     */
    private static ItemStack itemIcon(String itemId) {
        if (itemId == null || itemId.isBlank()) return ItemStack.EMPTY;
        return ICON_CACHE.computeIfAbsent(baseItemId(itemId), id -> {
            try {
                Identifier parsed = Identifier.tryParse(id);
                if (parsed == null) return ItemStack.EMPTY;
                Item item = BuiltInRegistries.ITEM.getValue(parsed);
                return item == null ? ItemStack.EMPTY : new ItemStack(item);
            } catch (RuntimeException e) {
                return ItemStack.EMPTY;
            }
        });
    }

    /**
     * Draws a row's item icon, vertically centred.
     *
     * @return the horizontal space consumed, so the label can be indented past
     *         it; zero when there is no icon, leaving the layout untouched
     */
    private int drawItemIcon(GuiGraphicsExtractor graphics, String itemId,
                             int x, int rowY, int rowHeight) {
        ItemStack stack = itemIcon(itemId);
        if (stack.isEmpty()) return 0;
        graphics.item(stack, x, rowY + Math.max(0, (rowHeight - 16) / 2));
        return ICON_SLOT_WIDTH;
    }

    /**
     * The plain item id behind a market key.
     *
     * <p>An item carrying metadata — a custom name, enchantments, lore, or
     * container contents — keys as {@code itemId + "#" + hash}, which is not a
     * parseable identifier and reads as gibberish in a label. The base id is
     * still the right icon and the right word for it; the metadata is what
     * makes it a separate market, not a separate item.
     */
    private static String baseItemId(String itemKey) {
        if (itemKey == null) return "";
        int marker = itemKey.indexOf('#');
        return marker < 0 ? itemKey : itemKey.substring(0, marker);
    }

    /**
     * A preflight-only stand-in for a live listing.
     *
     * <p>Carries no valuation: every projected figure is zero, so it can never
     * be mistaken for a trade worth making. The execution gates validate the
     * listing identity — exact id, stack size, seller, price — and nothing
     * about profit, which is exactly the part a GUI rehearsal needs.
     */
    private static Opportunity rehearsalCandidate(Listing listing) {
        return new Opportunity(listing, null, listing.totalPrice(), 0,
                0, 0, 0, 0, 0, 0,
                List.of("Preflight rehearsal only — not a valued opportunity"));
    }

    /**
     * The cheapest live listing that a preflight could search for.
     *
     * <p>Requires a named seller and a stable listing key because the driver
     * refuses to guess between two similar rows; anything else is unusable as
     * a rehearsal target however cheap it is.
     */
    private static Opportunity rehearsalTarget(MarketWatcher.Snapshot snapshot) {
        if (snapshot == null || snapshot.demo()) return null;
        Listing best = null;
        for (Listing listing : snapshot.activeListings()) {
            String key = listing.listingKey();
            String seller = listing.sellerName();
            if (key == null || key.isBlank() || key.startsWith("demo-")) continue;
            if (seller == null || seller.isBlank()) continue;
            if (listing.itemCount() <= 0 || listing.totalPrice() <= 0) continue;
            if (best == null || listing.totalPrice() < best.totalPrice()) {
                best = listing;
            }
        }
        return best == null ? null : rehearsalCandidate(best);
    }

    private static String marketName(MarketStats stats) {
        String key = stats.itemKey();
        // Say so rather than showing sixteen hex characters: this row is a
        // named, enchanted, or filled variant priced apart from the plain item.
        String variant = key != null && key.indexOf('#') >= 0 ? " (variant)" : "";
        return titleCase(shortName(baseItemId(key))) + variant
                + " " + stats.bucket().label();
    }

    private static String holdTime(double hours) {
        if (!Double.isFinite(hours)) return "unknown";
        double minutes = hours * 60;
        if (minutes < 1) return "<1m";
        if (minutes < 90) return String.format(Locale.ROOT, "%.0fm", minutes);
        return String.format(Locale.ROOT, "%.1fh", hours);
    }

    private static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        if (minutes < 60) return minutes + " min";
        long hours = minutes / 60;
        return hours + "h " + minutes % 60 + "m";
    }

    private static String relativeAge(long millis) {
        long seconds = Math.max(0, millis) / 1_000;
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        return hours + "h ago";
    }

    private static String shortName(String itemId) {
        String bare = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        return bare.replace('_', ' ');
    }

    private static String titleCase(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean upper = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ') {
                upper = true;
                result.append(c);
            } else {
                result.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return result.toString();
    }

    private static String money(long value) {
        return money((double) value);
    }

    private static String money(double value) {
        if (!Double.isFinite(value)) return "$—";
        String sign = value < 0 ? "-" : "";
        double absolute = Math.abs(value);
        if (absolute >= 1_000_000) {
            return sign + "$" + String.format(Locale.ROOT, "%.2fm", absolute / 1e6);
        }
        if (absolute >= 1_000) {
            return sign + "$" + String.format(Locale.ROOT, "%.1fk", absolute / 1e3);
        }
        return sign + "$" + String.format(Locale.ROOT, "%.0f", absolute);
    }

    private static String signedMoney(long value) {
        return value > 0 ? "+" + money(value) : money(value);
    }

    private static String availablePercent(Double value) {
        return value == null ? "not measured"
                : String.format(Locale.ROOT, "%.1f%%", value);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value);
    }

    private static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private String truncate(String text, int maxWidth) {
        if (text == null || maxWidth <= 0) return "";
        if (this.font.width(text) <= maxWidth) return text;
        return this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - this.font.width("…"))) + "…";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

}
