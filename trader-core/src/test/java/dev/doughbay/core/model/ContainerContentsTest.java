package dev.doughbay.core.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContainerContentsTest {

    @Test
    void orderAndEquivalentStackSplitsDoNotChangeIdentity() {
        ItemFingerprint stone = ItemFingerprint.builder("stone").build();
        ItemFingerprint pearl = ItemFingerprint.builder("ender_pearl").build();
        ContainerContents split = ContainerContents.builder()
                .add(stone, 10)
                .add(pearl, 2)
                .add(stone, 22)
                .build();
        ContainerContents combinedAndReordered = ContainerContents.builder()
                .add(pearl, 2)
                .add(stone, 32)
                .build();

        assertEquals(split.canonicalForm(), combinedAndReordered.canonicalForm());
        assertEquals(split.sha256(), combinedAndReordered.sha256());
    }

    @Test
    void nestedContentsRemainPartOfExactIdentity() {
        ItemFingerprint shulker = ItemFingerprint.builder("shulker_box").build();
        ContainerContents stoneBox = ContainerContents.builder()
                .add(ItemFingerprint.builder("stone").build(), 64).build();
        ContainerContents pearlBox = ContainerContents.builder()
                .add(ItemFingerprint.builder("ender_pearl").build(), 16).build();

        ContainerContents first = ContainerContents.builder().add(shulker, 1, stoneBox).build();
        ContainerContents second = ContainerContents.builder().add(shulker, 1, pearlBox).build();
        assertNotEquals(first.sha256(), second.sha256());
    }

    @Test
    void rejectsTreesBeyondDepthOrTotalEntryCap() {
        ItemFingerprint shulker = ItemFingerprint.builder("shulker_box").build();
        ContainerContents nested = ContainerContents.builder().add(shulker, 1).build();
        nested = ContainerContents.builder().add(shulker, 1, nested).build();
        nested = ContainerContents.builder().add(shulker, 1, nested).build();
        ContainerContents tooDeep = ContainerContents.builder().add(shulker, 1, nested).build();
        assertThrows(IllegalArgumentException.class,
                () -> tooDeep.canonicalForm(new ContainerContents.Limits(3, 256)));

        List<ContainerContents.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 257; i++) {
            entries.add(new ContainerContents.Entry(
                    ItemFingerprint.builder("stone").build(), 1));
        }
        ContainerContents tooMany = new ContainerContents(entries);
        assertThrows(IllegalArgumentException.class, tooMany::canonicalForm);
    }

    @Test
    void jsonMetadataIsCanonicalBeforeHashing() {
        ItemFingerprint first = ItemFingerprint.builder("shulker_box")
                .containerContents("[{\"count\":64,\"id\":\"minecraft:stone\"}]")
                .extraMetadata("{\"b\":2,\"a\":1}")
                .build();
        ItemFingerprint second = ItemFingerprint.builder("shulker_box")
                .containerContents("[ { \"id\": \"minecraft:stone\", \"count\": 64.0 } ]")
                .extraMetadata("{\"a\":1.0,\"b\":2}")
                .build();

        assertEquals(first, second);
    }
}
