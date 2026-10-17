package dev.doughbay.fabric;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.lwjgl.opengl.GL33.*;
import org.lwjgl.system.MemoryUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
/** Offline preview using visibly labelled invented fixtures. Never joins a server. */
public final class PresetsAnalyzerSmoke implements ClientModInitializer {
 private int phase,ticks;private long started=System.currentTimeMillis();
 public void onInitializeClient(){Tuning.set("join.auto",0);Tuning.set("update.auto",0);Tuning.set("session.auto_resume",0);ClientTickEvents.END_CLIENT_TICK.register(this::tick);}
 private void tick(Minecraft mc){
  if(phase==99)return;
  try{
   if(System.currentTimeMillis()-started>120000)throw new IllegalStateException("Preview timed out");
   if(mc.level!=null)throw new IllegalStateException("Preview must remain offline");
   if(mc.gui.overlay()!=null)return;ticks++;
   switch(phase){
    case 0->{if(ticks<75)return;mc.options.pauseOnLostFocus=false;mc.options.guiScale().set(2);mc.resizeGui();
      if(SavedPresets.current().entries().isEmpty()){SavedPresets.current().save("","DEMO Baseline stacks",SettingsCode.exportPreset(Map.of(),Map.of()));
      SavedPresets.current().save("","DEMO Experiment pool",SettingsCode.exportPreset(Map.of("buy.purchase_cap_pct",8d),Map.of()));}
      mc.gui.setScreen(new PresetsScreen(mc.gui.screen()));phase++;ticks=0;}
    case 1->{if(ticks<30)return;capture(mc,"presets-preview.png");
      var f=SettingsAnalyzer.class.getDeclaredField("report");f.setAccessible(true);
      f.set(null,new SettingsAnalyzer.Report(System.currentTimeMillis(),List.of("DEMO PREVIEW — invented figures; not live trading data", "24h local ledger: 240 sales; $4,800,000 realized profit", "24h wall-clock average: $200,000/h; 10 sales/h", "This account bank: $20,000,000; effective purchase cap $2,800,000", "2 active clients; this account slot limit 90", "Recorded account active time: 12h; not used to divide hive profit"),
       List.of(new SettingsAnalyzer.Finding("DEMO Review a weak market","10 completed sales lost $20,000; two positions remain open.","Trial a replacement while preserving management of existing stock.","No profit/hour uplift established; compare actual results after the trial.")),Map.of(),Map.of(),"","",demoCapital(System.currentTimeMillis(),20000000),
       List.of(demoCapital(System.currentTimeMillis()-900000,26000000),demoCapital(System.currentTimeMillis()-600000,24000000),demoCapital(System.currentTimeMillis()-300000,22000000),demoCapital(System.currentTimeMillis(),20000000)),"DEMO sampled history — invented data"));
      var status=SettingsAnalyzer.class.getDeclaredField("status");status.setAccessible(true);status.set(null,"DEMO PREVIEW — analysis changes no settings");
      var screen=new DoughBayScreen(DoughBayClient.watcher());var tab=DoughBayScreen.class.getDeclaredField("tab");tab.setAccessible(true);
      @SuppressWarnings({"unchecked","rawtypes"}) Object value=Enum.valueOf((Class)tab.getType(),"ANALYZER");tab.set(screen,value);mc.gui.setScreen(screen);phase++;ticks=0;}
    case 2->{if(ticks<30)return;capture(mc,"analyzer-preview.png");mc.options.guiScale().set(1);mc.resizeGui();phase++;ticks=0;}
    case 3->{if(ticks<30)return;capture(mc,"analyzer-wide-preview.png");
      ((net.minecraft.client.gui.components.Button)net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(mc.gui.screen()).stream().filter(w->w.getMessage().getString().equals("Next")).findFirst().orElseThrow()).onPress(null);phase++;ticks=0;}
    case 4->{if(ticks<30)return;capture(mc,"analyzer-finding-preview.png");
      ((net.minecraft.client.gui.components.Button)net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(mc.gui.screen()).stream().filter(w->w.getMessage().getString().equals("Capital charts")).findFirst().orElseThrow()).onPress(null);phase++;ticks=0;}
    case 5->{if(ticks<30)return;capture(mc,"capital-charts-preview.png");phase=99;mc.stop();}
   }
  }catch(Exception e){try{Files.writeString(Path.of("preview-error.txt"),e.toString());}catch(Exception ignored){}phase=99;mc.stop();}
 }
    private CapitalBreakdown.Snapshot demoCapital(long at,long bank){return new CapitalBreakdown.Snapshot(at,bank,3000000L,2000000L,1000000L,6000000L,500000L,8000000L,at,at,at,List.of("DEMO preview: invented capital observations"));}
    private void capture(Minecraft mc, String name) throws Exception {
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        int fb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), pack = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING), align = glGetInteger(GL_PACK_ALIGNMENT);
        int row = glGetInteger(GL_PACK_ROW_LENGTH), sx = glGetInteger(GL_PACK_SKIP_PIXELS), sy = glGetInteger(GL_PACK_SKIP_ROWS);
        var bytes = MemoryUtil.memAlloc(w*h*4);
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,0); glReadBuffer(GL_BACK); glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
            glPixelStorei(GL_PACK_ALIGNMENT,1); glPixelStorei(GL_PACK_ROW_LENGTH,0); glPixelStorei(GL_PACK_SKIP_PIXELS,0); glPixelStorei(GL_PACK_SKIP_ROWS,0);
            glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
            var image = new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
            for (int y=0;y<h;y++) for (int x=0;x<w;x++) {int p=(y*w+x)*4; image.setRGB(x,h-1-y,((bytes.get(p)&255)<<16)|((bytes.get(p+1)&255)<<8)|(bytes.get(p+2)&255));}
            ImageIO.write(image,"png",Path.of(name).toFile());
        } finally {MemoryUtil.memFree(bytes); glBindFramebuffer(GL_READ_FRAMEBUFFER,fb); glBindBuffer(GL_PIXEL_PACK_BUFFER,pack); glPixelStorei(GL_PACK_ALIGNMENT,align); glPixelStorei(GL_PACK_ROW_LENGTH,row); glPixelStorei(GL_PACK_SKIP_PIXELS,sx); glPixelStorei(GL_PACK_SKIP_ROWS,sy);}
    }
}
