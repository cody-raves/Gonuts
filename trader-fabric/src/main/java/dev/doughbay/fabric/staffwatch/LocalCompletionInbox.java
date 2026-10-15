package dev.doughbay.fabric.staffwatch;

import java.util.LinkedHashMap;
import java.util.List;

/** Discards replies to probes sent before a transfer, including late Netty delivery. */
public final class LocalCompletionInbox {
    private final LinkedHashMap<Integer, Long> requests = new LinkedHashMap<>();
    private long epoch;
    private List<String> pending;
    public synchronized void register(int id) {
        requests.put(id, epoch);
        while (requests.size() > 8) requests.remove(requests.keySet().iterator().next());
    }
    public synchronized boolean complete(int id, List<String> names) {
        Long requestedEpoch = requests.remove(id);
        if (requestedEpoch == null) return false;
        if (requestedEpoch == epoch) pending = List.copyOf(names);
        return true;
    }
    public synchronized List<String> take() {
        List<String> result = pending; pending = null; return result;
    }
    public synchronized void reset() { epoch++; pending = null; }
}
