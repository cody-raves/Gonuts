package dev.doughbay.fabric;
import java.nio.file.*;import java.util.*;import java.awt.image.BufferedImage;import javax.imageio.ImageIO;import static org.lwjgl.opengl.GL33.*;import org.lwjgl.system.MemoryUtil;
import net.fabricmc.api.ClientModInitializer;import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;import net.minecraft.client.Minecraft;import net.minecraft.client.gui.GuiGraphicsExtractor;import net.minecraft.client.gui.screens.Screen;import net.minecraft.client.gui.components.*;import net.minecraft.network.chat.Component;
public final class WebsiteGuiSmoke implements ClientModInitializer {
 private int ticks,phase,checks;private Fixture fixture;private final List<String> copied=new ArrayList<>();private boolean failLaunch;private int launches;
 public void onInitializeClient(){Tuning.set("join.auto",0);Tuning.set("update.auto",0);Tuning.set("session.auto_resume",0);ClientTickEvents.END_CLIENT_TICK.register(this::tick);}
 private void require(boolean ok,String message){checks++;if(!ok)throw new IllegalStateException(message);}
 private void tick(Minecraft mc){try{
  require(mc.level==null,"fixture joined a server");if(mc.gui.overlay()!=null||++ticks<35)return;ticks=0;
  switch(phase++){
   case 0->{mc.options.pauseOnLostFocus=false;mc.options.guiScale().set(2);mc.resizeGui();fixture=new Fixture();mc.gui.setScreen(fixture);}
   case 1->{Button copy=button("Copy website address");require(copy.visible&&copy.active,"manual fallback missing");press(copy);require(copied.equals(List.of("https://gonuts.at/dashboard/")),"wrong public address");require(launches==0,"copy launched browser");press(button("1. Open website & sign in"));require(launches==1,"open request missing");require(fixture.access.message().equals("No browser? Copy this address."),"false browser success");capture(mc,"website-silent-launch.png");failLaunch=true;press(button("1. Open website & sign in"));require(fixture.access.message().startsWith("Browser unavailable"),"launch refusal lost");press(button("Copy website address"));require(copied.size()==2,"fallback unusable after failure");mc.options.guiScale().set(3);mc.resizeGui();}
   case 2->{Button open=button("1. Open website & sign in"),copy=button("Copy website address");require(open.visible&&copy.visible,"compact fallback clipped");require(open.getY()+open.getHeight()<=copy.getY(),"buttons overlap");capture(mc,"website-compact.png");fixture.panel.scroll(fixture.left+10,30,-10);}
   case 3->{capture(mc,"website-scrolled.png");fixture.panel.scroll(fixture.left+10,30,100);}
   case 4->{require(button("Copy website address").visible,"fallback lost after scroll");mc.gui.setScreen(new Fixture());}
   case 5->{require(button("Copy website address").visible,"fallback missing after rebuild");require(!DoughBayClient.donutAutomationEnabled(),"navigation enabled trading");Files.writeString(Path.of("website-preview-success.txt"),"Actual offline GUI: silent/throwing browser launcher, copy without pairing, resize, scroll and rebuild passed; checks="+checks);mc.stop();}
  }
 }catch(Throwable e){try{Files.writeString(Path.of("website-preview-error.txt"),e.toString());}catch(Exception ignored){}mc.stop();}}
 private Button button(String text){return fixture.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getMessage().getString().equals(text)).findFirst().orElseThrow(()->new IllegalStateException("Missing action: "+text));}
 private void press(Button b)throws Exception{for(var f:Button.class.getDeclaredFields())if(f.getType()==Button.OnPress.class){f.setAccessible(true);((Button.OnPress)f.get(b)).onPress(b);return;}throw new IllegalStateException("no native button callback");}
 private final class Fixture extends Screen {
  SwarmPanel panel;DashboardWebsiteAccess access;int left;
  Fixture(){super(Component.literal("Offline website fallback regression"));access=new DashboardWebsiteAccess(uri->{launches++;if(failLaunch)throw new IllegalStateException("simulated missing desktop launcher");},copied::add);try{var c=SwarmPanel.class.getDeclaredConstructor(DashboardWebsiteAccess.class);c.setAccessible(true);panel=c.newInstance(access);}catch(NoSuchMethodException old){panel=new SwarmPanel();}catch(Exception e){throw new RuntimeException(e);}}
  protected void init(){fixture=this;int w=Math.min(440,width-20);left=(width-w)/2;panel.init(font,left,8,w,height-16,this::addRenderableWidget);}
  public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float dt){g.fill(0,0,width,height,0xff101820);panel.render(g,font);super.extractRenderState(g,x,y,dt);}
 }
 private void capture(Minecraft mc,String name)throws Exception{
  int w=mc.getWindow().getWidth(),h=mc.getWindow().getHeight();
  int fb=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),pack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),align=glGetInteger(GL_PACK_ALIGNMENT);
  int row=glGetInteger(GL_PACK_ROW_LENGTH),sx=glGetInteger(GL_PACK_SKIP_PIXELS),sy=glGetInteger(GL_PACK_SKIP_ROWS);
  var bytes=MemoryUtil.memAlloc(w*h*4);
  try{
   glBindFramebuffer(GL_READ_FRAMEBUFFER,0);glReadBuffer(GL_BACK);glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
   glPixelStorei(GL_PACK_ALIGNMENT,1);glPixelStorei(GL_PACK_ROW_LENGTH,0);glPixelStorei(GL_PACK_SKIP_PIXELS,0);glPixelStorei(GL_PACK_SKIP_ROWS,0);
   glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,bytes);var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
   for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=(y*w+x)*4;image.setRGB(x,h-1-y,((bytes.get(p)&255)<<16)|((bytes.get(p+1)&255)<<8)|(bytes.get(p+2)&255));}
   ImageIO.write(image,"png",Path.of(name).toFile());
  }finally{MemoryUtil.memFree(bytes);glBindFramebuffer(GL_READ_FRAMEBUFFER,fb);glBindBuffer(GL_PIXEL_PACK_BUFFER,pack);glPixelStorei(GL_PACK_ALIGNMENT,align);glPixelStorei(GL_PACK_ROW_LENGTH,row);glPixelStorei(GL_PACK_SKIP_PIXELS,sx);glPixelStorei(GL_PACK_SKIP_ROWS,sy);}
 }
}
