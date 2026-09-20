package dev.doughbay.core.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Pure-Java canonical model for container contents.
 *
 * <p>Equivalent stacks are aggregated and entries are sorted by exact item
 * identity, including recursively nested contents. Limits are checked across
 * the whole tree before an identity is produced. This class does not interpret
 * API fields; an adapter must first validate and map those fields explicitly.</p>
 */
public final class ContainerContents {

    public static final Limits DEFAULT_LIMITS = new Limits(4, 256);

    private final List<Entry> entries;

    public ContainerContents(Collection<Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        this.entries = List.copyOf(entries);
    }

    public static ContainerContents empty() {
        return new ContainerContents(List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Entry> entries() {
        return entries;
    }

    public String canonicalForm() {
        return canonicalForm(DEFAULT_LIMITS);
    }

    public String canonicalForm(Limits limits) {
        Objects.requireNonNull(limits, "limits");
        LimitState state = new LimitState(limits);
        return canonicalize(this, 1, state);
    }

    public String sha256() {
        return ItemFingerprint.sha256(canonicalForm());
    }

    private static String canonicalize(ContainerContents contents, int depth, LimitState state) {
        if (depth > state.limits.maxDepth()) {
            throw new IllegalArgumentException("container depth limit exceeded");
        }
        state.addEntries(contents.entries.size());

        Map<Identity, Long> aggregated = new TreeMap<>();
        for (Entry entry : contents.entries) {
            String nested = entry.nestedContents == null
                    ? ""
                    : canonicalize(entry.nestedContents, depth + 1, state);
            Identity identity = new Identity(entry.fingerprint.identityHash(), nested);
            try {
                aggregated.merge(identity, entry.count, Math::addExact);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("aggregated container count overflow", ex);
            }
        }

        StringBuilder result = new StringBuilder("{\"items\":[");
        boolean first = true;
        for (Map.Entry<Identity, Long> aggregatedEntry : aggregated.entrySet()) {
            if (!first) result.append(',');
            Identity identity = aggregatedEntry.getKey();
            result.append("{\"count\":").append(aggregatedEntry.getValue())
                    .append(",\"fingerprint\":\"").append(identity.fingerprintHash)
                    .append("\",\"nested\":");
            if (identity.nestedCanonical.isEmpty()) {
                result.append("null");
            } else {
                result.append(identity.nestedCanonical);
            }
            result.append('}');
            first = false;
        }
        return result.append("]}").toString();
    }

    public record Entry(ItemFingerprint fingerprint, long count, ContainerContents nestedContents) {
        public Entry {
            Objects.requireNonNull(fingerprint, "fingerprint");
            if (count < 1) throw new IllegalArgumentException("container item count must be positive");
        }

        public Entry(ItemFingerprint fingerprint, long count) {
            this(fingerprint, count, null);
        }
    }

    public record Limits(int maxDepth, int maxEntries) {
        public Limits {
            if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be positive");
            if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        }
    }

    public static final class Builder {
        private final List<Entry> entries = new ArrayList<>();

        public Builder add(ItemFingerprint fingerprint, long count) {
            entries.add(new Entry(fingerprint, count));
            return this;
        }

        public Builder add(ItemFingerprint fingerprint, long count, ContainerContents nested) {
            entries.add(new Entry(fingerprint, count, nested));
            return this;
        }

        public ContainerContents build() {
            return new ContainerContents(entries);
        }
    }

    private record Identity(String fingerprintHash, String nestedCanonical)
            implements Comparable<Identity> {
        @Override
        public int compareTo(Identity other) {
            int fingerprintOrder = fingerprintHash.compareTo(other.fingerprintHash);
            return fingerprintOrder != 0
                    ? fingerprintOrder
                    : nestedCanonical.compareTo(other.nestedCanonical);
        }
    }

    private static final class LimitState {
        private final Limits limits;
        private int entries;

        private LimitState(Limits limits) {
            this.limits = limits;
        }

        private void addEntries(int count) {
            if (count > limits.maxEntries() - entries) {
                throw new IllegalArgumentException("container entry limit exceeded");
            }
            entries += count;
        }
    }
}
