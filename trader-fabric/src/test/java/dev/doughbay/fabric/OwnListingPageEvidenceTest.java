package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static dev.doughbay.fabric.AutomatedExecutionDriver.*;
import static dev.doughbay.fabric.ListingPageTurn.Result.*;

class OwnListingPageEvidenceTest {
    // Actual Your Items tooltip captured from the server. An unpriced pane
    // alone is not enough: the server must identify it as a vacant sell slot.
    private static final OwnListingCell VACANT = new OwnListingCell(
            "minecraft:gray_stained_glass_pane", 1, List.of("List", "Click to sell an item"));
    private static final OwnListingCell AIR = new OwnListingCell("minecraft:air", 0, List.of());
    private static final OwnListingCell LISTING = new OwnListingCell(
            "minecraft:bone_meal", 64, List.of("Bone Meal", "$ 6.5K", "Time Left:", "23h 59m"));

    private List<OwnListingCell> page(OwnListingCell cell) {
        return new ArrayList<>(Collections.nCopies(45, cell));
    }

    @Test void emptyBookHasPositiveVacantSlotEvidence() {
        var empty = ownListingPageEvidence(page(VACANT));
        assertTrue(empty.loaded());
        assertTrue(empty.empty(), "the persistent Next arrow must not require another page");
    }

    @Test void loadingAndPartiallyPopulatedMenusCannotClearTheBook() {
        assertFalse(ownListingPageEvidence(page(AIR)).loaded());
        assertFalse(ownListingPageEvidence(List.of(VACANT)).loaded());
        var partial = page(VACANT);
        partial.set(44, AIR);
        assertFalse(ownListingPageEvidence(partial).loaded());
        partial.set(0, LISTING);
        assertFalse(ownListingPageEvidence(partial).loaded(), "one real row cannot prove the rest has loaded");
    }

    @Test void emptyFinalPageArrivesWithoutLosingTheEarlierPopulatedPage() {
        var first = page(VACANT);
        first.set(0, LISTING);
        var evidence = ownListingPageEvidence(first);
        assertTrue(evidence.loaded());
        assertFalse(evidence.empty());
        var turn = new ListingPageTurn();
        turn.requested("menu1|listing-and-vacancies", 1_000);
        assertEquals(WAITING, turn.observe("menu1|listing-and-vacancies", 1_100, evidence.loaded()));
        assertEquals(WAITING, turn.observe("menu2|air", 1_200, ownListingPageEvidence(page(AIR)).loaded()));
        var empty = ownListingPageEvidence(page(VACANT));
        assertEquals(ARRIVED, turn.observe("menu2|vacancies", 1_300, empty.loaded()));
        assertTrue(empty.empty());
        assertEquals(NONE, turn.observe("menu2|vacancies", 1_400, empty.loaded()));
    }

    @Test void fullPagesAndExpiredRowsAreOccupied() {
        assertTrue(ownListingPageEvidence(page(LISTING)).loaded());
        assertFalse(ownListingPageEvidence(page(LISTING)).empty());
        var expired = page(VACANT);
        expired.set(0, new OwnListingCell("minecraft:stone", 64, List.of("Stone", "Click to collect")));
        assertTrue(ownListingPageEvidence(expired).loaded());
        assertFalse(ownListingPageEvidence(expired).empty());
    }

    @Test void unknownFurnitureAndUnpricedItemsAreNotVacancies() {
        for (var unknown : List.of(
                new OwnListingCell("minecraft:gray_stained_glass_pane", 1, List.of("List")),
                new OwnListingCell("minecraft:stone", 1, List.of("List", "Click to sell an item")),
                new OwnListingCell("minecraft:gray_stained_glass_pane", 64, VACANT.tooltip()),
                new OwnListingCell("minecraft:diamond", 1, List.of("Diamond")))) {
            var cells = page(VACANT);
            cells.set(10, unknown);
            assertFalse(ownListingPageEvidence(cells).loaded());
            assertFalse(ownListingPageEvidence(cells).empty());
        }
    }

    @Test void pricedPaneNamedListIsStillAListing() {
        var cells = page(VACANT);
        cells.set(0, new OwnListingCell(VACANT.itemId(), 1,
                List.of("List", "Click to sell an item", "$ 5K")));
        assertTrue(ownListingPageEvidence(cells).loaded());
        assertFalse(ownListingPageEvidence(cells).empty());
    }

    @Test void aSmallerRanksLockedCellsAreNotHeldToTheEvidence() {
        // An 18-slot rank: 18 usable cells, the other 27 are the server's own.
        var cells = page(new OwnListingCell("minecraft:barrier", 1, List.of("Locked slot")));
        for (int i = 0; i < 18; i++) cells.set(i, VACANT);
        cells.set(0, LISTING);
        var evidence = ownListingPageEvidence(cells, 18);
        assertTrue(evidence.loaded());
        assertFalse(evidence.empty());
        // The whole book of a rank under 45 slots is on this page: the Next
        // arrow is drawn anyway and leads nowhere, so it is never clicked.
        assertTrue(evidence.last());
        assertFalse(ownListingPageEvidence(page(LISTING)).last());
        for (int i = 0; i < 18; i++) cells.set(i, VACANT);
        assertTrue(ownListingPageEvidence(cells, 18).empty());
        // The usable cells are still held strictly: air there is a page still loading.
        cells.set(5, AIR);
        assertFalse(ownListingPageEvidence(cells, 18).loaded());
        // And the full-rank check is unchanged: those barriers would fail it.
        assertFalse(ownListingPageEvidence(cells, 45).loaded());
    }

    @Test void theNoRankSlotCountIsReadOffThePage() {
        var cells = page(new OwnListingCell("minecraft:barrier", 1, List.of("Locked slot")));
        for (int i = 0; i < 18; i++) cells.set(i, VACANT);
        cells.set(0, LISTING);
        assertEquals(18, rankSlotsFromPage(cells));
        // A full-rank page proves nothing about a smaller rank.
        assertEquals(0, rankSlotsFromPage(page(LISTING)));
        assertEquals(0, rankSlotsFromPage(page(VACANT)));
        // Air past the 18th cell is a page still loading, not a locked cell.
        var loading = new ArrayList<>(cells);
        loading.set(30, AIR);
        assertEquals(0, rankSlotsFromPage(loading));
        // Nor does an unloaded usable cell.
        var early = new ArrayList<>(cells);
        early.set(3, AIR);
        assertEquals(0, rankSlotsFromPage(early));
    }
}
