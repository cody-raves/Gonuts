package dev.doughbay.fabric;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SafeHomesTest {
    @TempDir Path root;
    private final Map<String,Object> saved = new HashMap<>();
    @BeforeEach void remember() throws Exception {
        for (String name : List.of("homes","allies","lastUsed","lastUsedAt","file","reloadedAt","seenModified")) {
            var field = SafeHomes.class.getDeclaredField(name); field.setAccessible(true); saved.put(name,field.get(null));
        }
        SafeHomes.load(root); SafeHomes.noteUsed("");
    }
    @AfterEach void restore() throws Exception {
        for (var value : saved.entrySet()) {
            var field = SafeHomes.class.getDeclaredField(value.getKey()); field.setAccessible(true); field.set(null,value.getValue());
        }
    }
    @Test void namedHomesCycleInEnteredOrderAndRtpDoesNotAdvanceThem() throws Exception {
        SafeHomes.saveHomes("base, vault, farm");
        assertEquals("home base", SafeHomes.teleportCommand(true));
        assertEquals("rtp", SafeHomes.teleportCommand(false));
        assertEquals("home vault", SafeHomes.teleportCommand(true));
        assertEquals("home farm", SafeHomes.teleportCommand(true));
        assertEquals("home base", SafeHomes.teleportCommand(true));
        SafeHomes.saveHomes(""); assertEquals("", SafeHomes.teleportCommand(true));
    }
    @Test void savingNamesPreservesAlliesAndOtherFileFieldsAcrossReload() throws Exception {
        Files.writeString(root.resolve("homes.json"),"{\"homes\":[\"old\"],\"allies\":[\"Friend\"],\"other\":17}");
        SafeHomes.load(root); SafeHomes.saveHomes("base BASE, vault\nfarm");
        SafeHomes.load(root);
        assertEquals(List.of("base","vault","farm"),SafeHomes.homes());
        assertTrue(SafeHomes.isAlly("friend"));
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(root.resolve("homes.json")));
        assertEquals(17,data.path("other").asInt());
        assertEquals("Friend",data.path("allies").get(0).asText());
    }
    @Test void invalidCommandsAndMalformedFilesNeverReplaceGoodSettings() throws Exception {
        SafeHomes.saveHomes("base");
        String before = Files.readString(root.resolve("homes.json"));
        assertThrows(IllegalArgumentException.class,()->SafeHomes.saveHomes("base, /home bad"));
        assertThrows(IllegalArgumentException.class,()->SafeHomes.saveHomes("base;quit"));
        assertEquals(before,Files.readString(root.resolve("homes.json")));
        Files.writeString(root.resolve("homes.json"),"{broken");
        assertThrows(java.io.IOException.class,()->SafeHomes.saveHomes("newbase"));
        assertEquals("{broken",Files.readString(root.resolve("homes.json")));
        assertEquals(List.of("base"),SafeHomes.homes());
    }
    @Test void webEvasionPageContainsOnlyEvasionControlsAndAcceptsValidatedHomeNames() throws Exception {
        var answer = new com.fasterxml.jackson.databind.ObjectMapper().readTree(WebPages.changeEvasion(Map.of("action","homes","names","base, farm")));
        assertTrue(answer.path("ok").asBoolean());
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(WebPages.evasion());
        assertEquals(1,data.path("groups").size());
        assertEquals("Evasion",data.path("groups").get(0).path("name").asText());
        assertFalse(data.has("lists")); assertFalse(data.has("presets"));
        assertEquals(2,data.path("homes").size());
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(WebPages.changeEvasion(Map.of("action","homes","names","/quit"))).path("ok").asBoolean());
        assertEquals(List.of("base","farm"),SafeHomes.homes());
    }
}
