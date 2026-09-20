package dev.doughbay.storage;

/** A purchased position reserved for retail sale, linked to its original batch. */
public record RetailLot(long positionId, long rootPositionId, long unitTarget) {
    public RetailLot {
        if (positionId <= 0 || rootPositionId <= 0 || unitTarget <= 0)
            throw new IllegalArgumentException("Retail lot requires positive ids and price");
    }

    public RetailLot child(long id) { return new RetailLot(id, rootPositionId, unitTarget); }
}
