package dev.doughbay.fabric;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardWebsiteAccessTest {
    @Test void silentLauncherDoesNotClaimSuccessAndManualCopyStillWorks() {
        List<String> copied = new ArrayList<>();
        DashboardWebsiteAccess access = new DashboardWebsiteAccess(uri -> {}, copied::add);
        access.open();
        assertEquals("No browser? Copy this address.", access.message());
        access.copy();
        assertEquals(List.of("https://gonuts.at/dashboard/"), copied);
    }

    @Test void missingDesktopLauncherCannotEscapeTheGuiCallback() {
        List<String> copied = new ArrayList<>();
        DashboardWebsiteAccess access = new DashboardWebsiteAccess(uri -> {
            throw new IllegalStateException("desktop unavailable");
        }, copied::add);
        assertDoesNotThrow(access::open);
        assertEquals("Browser unavailable. Copy this address.", access.message());
        access.copy();
        assertEquals(access.address(), copied.getFirst());
    }

    @Test void copyingNeverOpensBrowserOrRequiresPairing() {
        DashboardWebsiteAccess access = new DashboardWebsiteAccess(uri -> fail("unexpected launch"), value -> {});
        access.copy();
        assertEquals("Address copied. Paste in your browser.", access.message());
    }

    @Test void unavailableClipboardLeavesPublicAddressForManualEntry() {
        DashboardWebsiteAccess access = new DashboardWebsiteAccess(uri -> {}, value -> {
            throw new IllegalStateException("clipboard unavailable");
        });
        assertDoesNotThrow(access::copy);
        assertEquals("Copy failed. Type the address above.", access.message());
        assertEquals("https://gonuts.at/dashboard/", access.address());
    }
}
