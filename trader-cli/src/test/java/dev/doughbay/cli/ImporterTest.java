package dev.doughbay.cli;

import dev.doughbay.core.model.Sale;
import dev.doughbay.storage.Database;
import dev.doughbay.storage.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImporterTest {

    @TempDir
    Path tempDir;

    private Database db;
    private TransactionRepository repo;
    private Importer importer;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        repo = new TransactionRepository(db);
        importer = new Importer(repo);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    void importsCsvWithHeaderAndVariedColumnNames() throws Exception {
        Path csv = tempDir.resolve("history.csv");
        Files.writeString(csv, """
                Date,Seller,Item,Quantity,Price
                1700000000000,PlayerOne,minecraft:ender_pearl,16,9340
                1700000060000,PlayerTwo,minecraft:ender_pearl,16,9500
                1700000120000,PlayerOne,minecraft:diamond,1,5000
                """);
        Importer.Report report = importer.importFile(csv, "history.csv");
        assertEquals(3, report.read());
        assertEquals(3, report.imported());
        assertEquals(0, report.rejected());

        List<Sale> pearls = repo.findByItemKeySince("minecraft:ender_pearl", 0);
        assertEquals(2, pearls.size());
        assertEquals(9340, pearls.get(0).totalPrice());
        assertEquals(16, pearls.get(0).itemCount());
    }

    @Test
    void reimportingTheSameFileCreatesNoDuplicates() throws Exception {
        Path csv = tempDir.resolve("history.csv");
        Files.writeString(csv, """
                timestamp,seller,item,count,price
                1700000000000,A,minecraft:redstone,64,3000
                """);
        assertEquals(1, importer.importFile(csv, "history.csv").imported());
        Importer.Report second = importer.importFile(csv, "history.csv");
        assertEquals(0, second.imported());
        assertEquals(1, second.duplicates());
        assertEquals(1, repo.count());
    }

    @Test
    void importsJsonInApiShape() throws Exception {
        Path json = tempDir.resolve("page.json");
        Files.writeString(json, """
                {"status":200,"result":[
                  {"unixMillisDateSold":1700000000000,"price":9340,
                   "seller":{"uuid":"abc","name":"S"},
                   "item":{"id":"minecraft:ender_pearl","count":16}}
                ]}""");
        Importer.Report report = importer.importFile(json, "page.json");
        assertEquals(1, report.imported());
        assertEquals(9340, repo.findByItemKeySince("minecraft:ender_pearl", 0).get(0).totalPrice());
    }

    @Test
    void secondsTimestampsAreConvertedToMillis() {
        long millis = Importer.parseTimestamp("1700000000000");
        long seconds = Importer.parseTimestamp("1700000000");
        assertEquals(millis, seconds, "seconds input must be scaled to the same instant");
    }

    @Test
    void isoTimestampsAreAccepted() {
        assertEquals(1700000000000L, Importer.parseTimestamp("2023-11-14T22:13:20Z"));
    }

    @Test
    void moneyFormattingIsTolerated() {
        assertEquals(1234567, Importer.parseLong("$1,234,567"));
        assertEquals(9340, Importer.parseLong(" 9340 "));
        assertEquals(9340, Importer.parseLong("9340.00"));
    }

    @Test
    void malformedRowsAreRejectedNotCrashing() throws Exception {
        Path csv = tempDir.resolve("messy.csv");
        Files.writeString(csv, """
                timestamp,seller,item,count,price
                1700000000000,A,minecraft:redstone,64,3000
                not-a-date,B,minecraft:redstone,64,3000
                1700000000000,C,minecraft:redstone,64,notanumber
                1700000000000,D,minecraft:redstone,64,0
                """);
        Importer.Report report = importer.importFile(csv, "messy.csv");
        assertEquals(1, report.imported());
        assertEquals(3, report.rejected());
        assertTrue(report.problems().size() > 0);
    }

    @Test
    void missingRequiredColumnsIsAClearError() throws Exception {
        Path csv = tempDir.resolve("bad.csv");
        Files.writeString(csv, "foo,bar\n1,2\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> importer.importFile(csv, "bad.csv"));
        assertTrue(e.getMessage().contains("timestamp"), e.getMessage());
    }

    @Test
    void quotedCsvCellsAreHandled() throws Exception {
        Path csv = tempDir.resolve("quoted.csv");
        Files.writeString(csv, """
                timestamp,seller,item,count,price
                1700000000000,"Player, The First",minecraft:diamond,1,"12,500"
                """);
        Importer.Report report = importer.importFile(csv, "quoted.csv");
        assertEquals(1, report.imported());
        assertEquals(12500, repo.findByItemKeySince("minecraft:diamond", 0).get(0).totalPrice());
    }
}
