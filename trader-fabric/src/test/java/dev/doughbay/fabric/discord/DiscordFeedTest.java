package dev.doughbay.fabric.discord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordFeedTest {

    @AfterEach
    void off() {
        DiscordFeed.setEnabled(false);
    }

    @Test
    void nothingIsQueuedWhileTheFeedIsOff() {
        DiscordFeed.post("Listed 64x glass at 43.5K");
        DiscordFeed.setEnabled(true);
        assertEquals("", DiscordFeed.nextMessage());
    }

    @Test
    void linesGoOutOldestFirstAsOneMessage() {
        DiscordFeed.setEnabled(true);
        DiscordFeed.post("Order placed: 64x glass at 577 each (36.93K)");
        DiscordFeed.post("Collected 64x glass from an order at 577 each (36.93K)");
        DiscordFeed.post("Listed 64x glass at 43.5K");
        assertEquals("Order placed: 64x glass at 577 each (36.93K)\n"
                + "Collected 64x glass from an order at 577 each (36.93K)\n"
                + "Listed 64x glass at 43.5K", DiscordFeed.nextMessage());
        assertEquals("", DiscordFeed.nextMessage());
    }

    @Test
    void aBusyMinuteIsSplitAtDiscordsLimitAndNothingIsLost() {
        DiscordFeed.setEnabled(true);
        for (int i = 0; i < 120; i++) DiscordFeed.post("Sold 1x ender chest for 5,500 (+1,900 profit) #" + i);
        int lines = 0;
        for (int message = 0; message < 20; message++) {
            String text = DiscordFeed.nextMessage();
            if (text.isEmpty()) break;
            assertTrue(text.length() <= 2000, "a message must fit Discord's limit");
            lines += text.split("\n").length;
        }
        assertEquals(120, lines);
    }

    @Test
    void aFeedThatFallsFarBehindSaysWhatItSkipped() {
        DiscordFeed.setEnabled(true);
        for (int i = 0; i < DiscordFeed.MAX_QUEUED + 25; i++) DiscordFeed.post("line " + i);
        String first = DiscordFeed.nextMessage();
        assertTrue(first.startsWith("_(25 earlier line(s) skipped"), first.substring(0, 60));
        assertTrue(first.contains("line 25\n"));
        assertFalse(first.contains("line 24\n"));
    }

    @Test
    void aPlayersNameCannotPingAnyoneOrBreakTheLine() {
        assertEquals("Guard: teleported away (@​everyone)", DiscordFeed.clean("Guard: teleported away (@everyone)"));
        assertEquals("one two", DiscordFeed.clean("one\ntwo"));
    }

    @Test
    void moneyReadsTheWayTheGameAbbreviatesIt() {
        assertEquals("1.1M", DiscordFeed.money(1_100_000));
        assertEquals("43.5K", DiscordFeed.money(43_500));
        assertEquals("5,500", DiscordFeed.money(5_500));
        assertEquals("-12K", DiscordFeed.money(-12_000));
        assertEquals("deepslate diamond ore", DiscordFeed.item("minecraft:deepslate_diamond_ore"));
    }
}
