package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MascotAnimationTest {
    @Test void readingEyesSweepAndMoveToTheNextLine() {
        var left = MascotAnimation.sample(MascotAnimation.Mood.WATCH, 0, false);
        var right = MascotAnimation.sample(MascotAnimation.Mood.WATCH, 950, false);
        var next = MascotAnimation.sample(MascotAnimation.Mood.WATCH, 1200, false);
        assertTrue(left.gazeX() < -.9);
        assertTrue(right.gazeX() > .9);
        assertEquals(left.gazeX(), next.gazeX(), .001);
        assertTrue(next.gazeY() > left.gazeY());
    }

    @Test void dollarReelsMoveContinuouslyThenStopSeparatelyOnWholeSymbols() {
        var a = MascotAnimation.sample(MascotAnimation.Mood.SALE, 600, false);
        var b = MascotAnimation.sample(MascotAnimation.Mood.SALE, 616, false);
        assertTrue(b.leftReel() > a.leftReel());
        assertTrue(b.leftReel() - a.leftReel() < 1);
        var firstStopped = MascotAnimation.sample(MascotAnimation.Mood.SALE, 1800, false);
        assertEquals(9, firstStopped.leftReel());
        assertTrue(firstStopped.rightReel() < 11);
        var finished = MascotAnimation.sample(MascotAnimation.Mood.SALE, 2500, false);
        assertEquals(9, finished.leftReel());
        assertEquals(11, finished.rightReel());
    }

    @Test void boredIdleGlancesRollsEyesAndYawnsWithoutChangingMood() {
        var bored = MascotAnimation.sample(MascotAnimation.Mood.IDLE, 0, false);
        var left = MascotAnimation.sample(MascotAnimation.Mood.IDLE, 1500, false);
        var up = MascotAnimation.sample(MascotAnimation.Mood.IDLE, 4900, false);
        var yawn = MascotAnimation.sample(MascotAnimation.Mood.IDLE, 7900, false);
        assertTrue(bored.leftOpen() < .6);
        assertTrue(left.gazeX() < -.8);
        assertTrue(up.gazeY() < -.8);
        assertTrue(yawn.mouthOpen() > .8);
        assertTrue(yawn.leftOpen() < bored.leftOpen());
    }

    @Test void animationDoesNotDependOnRenderFrameCount() {
        var frequent = new MascotAnimation();
        var sparse = new MascotAnimation();
        frequent.frame(MascotAnimation.Mood.WATCH, 1000, 0, false);
        sparse.frame(MascotAnimation.Mood.WATCH, 1000, 0, false);
        for (int t = 1016; t < 1700; t += 16) frequent.frame(MascotAnimation.Mood.WATCH, t, 0, false);
        assertEquals(frequent.frame(MascotAnimation.Mood.WATCH, 1700, 0, false),
                sparse.frame(MascotAnimation.Mood.WATCH, 1700, 0, false));
    }

    @Test void moodChangesMorphAndAnotherSaleRestartsTheReels() {
        var animation = new MascotAnimation();
        var idle = animation.frame(MascotAnimation.Mood.IDLE, 0, 0, false);
        var start = animation.frame(MascotAnimation.Mood.SALE, 1000, 1, false);
        assertEquals(idle.leftOpen(), start.leftOpen());
        assertEquals(idle.money(), start.money());
        var settled = animation.frame(MascotAnimation.Mood.SALE, 4000, 1, false);
        assertEquals(9, settled.leftReel());
        var repeat = animation.frame(MascotAnimation.Mood.SALE, 4010, 2, false);
        assertEquals(0, repeat.leftReel());
    }

    @Test void reducedMotionKeepsEveryMoodStill() {
        for (var mood : MascotAnimation.Mood.values()) {
            assertEquals(MascotAnimation.sample(mood, 0, true), MascotAnimation.sample(mood, 7900, true));
        }
    }
}
