package dev.doughbay.fabric.web;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
class PresetsAnalyzerJavascriptTest {
 @TempDir Path root;
 @Test void presetAndAnalyzerControlsRenderSafelyAndSaveWithoutApplying()throws Exception{
  Path html=root.resolve("panel.html"),script=root.resolve("test.cjs");Files.writeString(html,Pages.APP);
  try(var input=getClass().getResourceAsStream("/presets-analyzer.cjs")){assertNotNull(input);Files.copy(input,script);}
  var p=new ProcessBuilder(System.getenv().getOrDefault("NODE_BINARY","node"),script.toString(),html.toString()).redirectErrorStream(true).start();
  if(!p.waitFor(15,TimeUnit.SECONDS)){p.destroyForcibly();fail("JavaScript test timed out");}
  assertEquals(0,p.exitValue(),new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
 }
}
