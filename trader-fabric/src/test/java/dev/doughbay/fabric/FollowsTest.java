package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FollowsTest {
    @Test
    void theNoticesTheServerSendsAboutAFollowedPlayerAreRead() {
        // Lines as observed on 2026-10-10.
        Follows.Event order = Follows.parse("traderone ordered 128 Nether Wart for $ 50K");
        assertNotNull(order);
        assertEquals("traderone", order.player());
        assertEquals("ordered", order.kind());
        assertEquals(128, order.quantity());
        assertEquals("Nether Wart", order.item());
        assertEquals(50_000, order.price());

        Follows.Event listing = Follows.parse("GearSeller listed 1 Diamond Boots for $ 93.7K");
        assertEquals("listed", listing.kind());
        assertEquals("Diamond Boots", listing.item());
        assertEquals(93_700, listing.price());

        Follows.Event buy = Follows.parse(".BedrockBuyer1 bought 1 Item Frame for $ 39.8K");
        assertEquals(".BedrockBuyer1", buy.player());
        assertEquals(39_800, buy.price());

        assertEquals(979, Follows.parse("GearSeller bought 1 Slime Block for $ 979").price());
        assertEquals(34_900, Follows.parse("traderone listed 1 Minecart with Chest for $ 34.9K").price());
        assertEquals(1_200_000, Follows.parse("someone listed 64 Diamond Block for $1.2M").price());
    }

    @Test
    void comingAndGoingIsRead() {
        Follows.Event left = Follows.parse(".BedrockBuyer2 left the game");
        assertEquals("left", left.kind());
        assertEquals(".BedrockBuyer2", left.player());
        assertEquals("joined", Follows.parse(".BedrockBuyer2 joined the game").kind());
    }

    @Test
    void ourOwnReceiptsAndOrdinaryChatAreNotNotices() {
        assertNull(Follows.parse("You listed 64 Obsidian for $ 48K"));
        assertNull(Follows.parse(".SomeBuyer77 bought your Deepslate Diamond Ore for $27.8K"));
        assertNull(Follows.parse("[GoNuts] Listed black stained glass x64 at 130,905"));
        assertNull(Follows.parse("SomeSeller delivered you 64 TNT"));
        assertNull(Follows.parse("Steve: traderone listed 64 Diamond for $ 1"));
        assertNull(Follows.parse("You followed traderone"));
        assertNull(Follows.parse(null));
    }

    @Test
    void theAnswerToAFollowIsToldApartFromEverythingElse() {
        assertEquals(Follows.Reply.FOLLOWED, Follows.classify("You followed traderone", "traderone"));
        assertEquals(Follows.Reply.FOLLOWED, Follows.classify("You followed TraderOne", "traderone"));
        assertEquals(Follows.Reply.NOT_FOUND, Follows.classify("No player named BedrockBuyer2 was found.", "BedrockBuyer2"));
        assertEquals(Follows.Reply.UNFOLLOWED, Follows.classify("You unfollowed tradertwo", "tradertwo"));
        // An answer about somebody else, typed by hand at the same moment.
        assertEquals(Follows.Reply.UNRELATED, Follows.classify("You followed tradertwo", "traderone"));
        assertEquals(Follows.Reply.UNRELATED, Follows.classify("GearSeller bought 1 Slime Block for $ 979", "tradertwo"));
        assertEquals(Follows.Reply.UNRELATED, Follows.classify("Steve: follow me to spawn", "tradertwo"));
        // Words not seen before are how a limit will announce itself.
        assertEquals(Follows.Reply.REFUSED, Follows.classify("You can only follow 50 players", "tradertwo"));
        assertEquals(Follows.Reply.REFUSED, Follows.classify("Follow limit reached", "tradertwo"));
        assertEquals(Follows.Reply.FOLLOWED, Follows.classify("You are already following tradertwo", "tradertwo"));
    }
}
