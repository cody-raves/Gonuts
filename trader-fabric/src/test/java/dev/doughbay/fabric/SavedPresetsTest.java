package dev.doughbay.fabric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class SavedPresetsTest {
 @TempDir Path dir;
 @Test void newNamesAreIndependentAndDurable() throws Exception {
  var store=new SavedPresets(dir);String code=SettingsCode.exportPreset(Map.of(),Map.of());
  var first=store.save("","Baseline",code);var second=store.save("","Experiment",code);
  assertEquals(2,new SavedPresets(dir).entries().size());
  store.save(second.id(),"Experiment 2",code);
  assertEquals("Baseline",store.get(first.id()).name());
  store.delete(second.id());assertEquals(first,new SavedPresets(dir).get(first.id()));
 }
 @Test void duplicateNamesAndCorruptFilesDoNotOverwrite() throws Exception {
  var store=new SavedPresets(dir);String code=SettingsCode.exportPreset(Map.of(),Map.of());store.save("","Baseline",code);
  byte[] original=Files.readAllBytes(dir.resolve("saved-presets.json"));
  assertThrows(java.io.IOException.class,()->store.save("","baseline",code));
  assertArrayEquals(original,Files.readAllBytes(dir.resolve("saved-presets.json")));
  Files.writeString(dir.resolve("saved-presets.json"),"broken");
  assertThrows(java.io.IOException.class,()->store.save("","Fresh",code));
  assertEquals("broken",Files.readString(dir.resolve("saved-presets.json")));
 }
 @Test void applyingSnapshotRestoresEmptyListsAndKeepsSlots() {
  String before=SettingsCode.exportPreset(Map.of(),Map.of());double slots=Tuning.get("slots.max");
  try{
   Tuning.setText("watch.tracked","");String baseline=SettingsCode.exportPreset(Map.of(),Map.of());
   Tuning.setText("watch.tracked","diamond");Tuning.set("slots.max",18);
   assertTrue(SettingsCode.apply(baseline).ok());assertEquals("",Tuning.text("watch.tracked"));assertEquals(18,Tuning.get("slots.max"));
  }finally{SettingsCode.apply(before);Tuning.set("slots.max",slots);}
 }
}
