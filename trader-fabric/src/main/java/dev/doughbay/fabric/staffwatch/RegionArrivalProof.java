package dev.doughbay.fabric.staffwatch;

/** A post-dispatch server teleport plus a fresh round trip, never a cached reply. */
final class RegionArrivalProof {
    private long requestedAt, teleportAt;
    private int probeId = -1;
    private boolean replied;
    synchronized void begin(long at) { requestedAt=at; teleportAt=0; probeId=-1; replied=false; }
    synchronized void teleport(long at, long dispatchedAt) {
        if (teleportAt==0 && dispatchedAt>0 && dispatchedAt>=requestedAt && at>=dispatchedAt) teleportAt=at;
    }
    synchronized boolean needsProbe(boolean moved) { return moved && teleportAt>0 && probeId<0; }
    synchronized void probe(int id) { if(teleportAt>0 && probeId<0)probeId=id; }
    synchronized boolean complete(int id) { if(id!=probeId||probeId<0)return false;replied=true;return true; }
    synchronized boolean confirmed(boolean dispatched, boolean moved) { return dispatched&&moved&&teleportAt>0&&replied; }
}
