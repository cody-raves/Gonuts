package dev.doughbay.fabric;

import java.util.*;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import dev.doughbay.fabric.staffwatch.WatchConfig;
import dev.doughbay.fabric.staffwatch.ReconnectController;
import dev.doughbay.fabric.staffwatch.module.impl.misc.StaffListModule;

/** Embedded in the cockpit, with the same controls and surface colors as the other tabs. */
final class StaffWatchPanel {
 private final List<AbstractWidget> widgets=new ArrayList<>();
 private final Map<AbstractWidget,Integer> offsets=new IdentityHashMap<>();
 private int x,top,w,bottom,scroll,maxScroll;
 private String message="";
 private boolean error;
 void init(Font font,int x,int top,int w,int bottom,Consumer<AbstractWidget> add){
  this.x=x;this.top=top;this.w=w;this.bottom=bottom;widgets.clear();offsets.clear();
  var c=WatchConfig.get();
  int col=(w-24)/2;
  toggle(add,"Module",()->c.enabled,v->c.enabled=v,x+8,40,col);
  toggle(add,"Escape / reconnect",()->c.autoDisconnect,v->c.autoDisconnect=v,x+16+col,40,col);
  toggle(add,"Staff List",()->c.staffList,v->c.staffList=v,x+8,66,col);
  toggle(add,"Anti Vanish",()->c.antiVanish,v->c.antiVanish=v,x+16+col,66,col);
  toggle(add,"Region alerts",()->c.regionAlert,v->c.regionAlert=v,x+8,92,col);
  toggle(add,"Show HUD",()->c.showHud,v->c.showHud=v,x+16+col,92,col);
  toggle(add,"Alert sounds",()->c.alertSounds,v->c.alertSounds=v,x+8,118,col);
  add(add,CockpitButton.builder(Component.literal("Server: "+c.reconnectAddress()),b->{int next=(WatchConfig.SERVER_ADDRESSES.indexOf(c.reconnectAddress())+1)%WatchConfig.SERVER_ADDRESSES.size();c.serverAddress=WatchConfig.SERVER_ADDRESSES.get(next);persist();b.setMessage(Component.literal("Server: "+c.reconnectAddress()));}).bounds(x+8,top+162,w-16,20).build(),162);
  position();
 }
 private void add(Consumer<AbstractWidget> add,AbstractWidget widget,int y){widgets.add(widget);offsets.put(widget,y);add.accept(widget);}
 private void toggle(Consumer<AbstractWidget> add,String name,BooleanSupplier get,Consumer<Boolean> set,int x,int y,int width){
  add(add,CockpitButton.builder(label(name,get.getAsBoolean()),b->{set.accept(!get.getAsBoolean());persist();b.setMessage(label(name,get.getAsBoolean()));}).bounds(x,top+y,width,20).build(),y);
 }
 private Component label(String name,boolean on){return Component.literal(name+": "+(on?"ON":"OFF"));}
 private void persist(){try{WatchConfig.get().save();message="Saved";error=false;}catch(Exception e){message="Could not save settings";error=true;}}
 private void position(){for(var widget:widgets){int y=top+offsets.get(widget)-scroll;widget.setY(y);widget.visible=y>=top&&y+widget.getHeight()<=bottom;}}
 boolean scroll(double mx,double my,double delta){if(mx<x||mx>x+w||my<top||my>bottom)return false;scroll=Math.max(0,Math.min(maxScroll,scroll-(int)(delta*24)));position();return true;}
 void render(GuiGraphicsExtractor g,Font font){
  var c=WatchConfig.get();var rows=StaffListModule.hudRows().rows();
  maxScroll=Math.max(0,340+rows.size()*22-(bottom-top));scroll=Math.min(scroll,maxScroll);position();int y=top-scroll;
  g.enableScissor(x,top,x+w,bottom);
  UiTheme.panel(g,x,y,w,218,UiTheme.SURFACE);
  text(g,font,"STAFF WATCH",x+10,y+10,UiTheme.GOLD);
  text(g,font,"Donut SMP staff panels at top center; warnings appear below.",x+10,y+25,UiTheme.SECONDARY);
  text(g,font,"DONUT SMP — click to choose Main / East / West",x+10,y+148,UiTheme.TEXT);
  text(g,font,"Staff: change region; repeat sighting: hop, leave, wait 5–10 min.",x+10,y+190,UiTheme.MUTED);
  text(g,font,message,x+10,y+204,error?UiTheme.BAD:UiTheme.GOOD);
  UiTheme.panel(g,x,y+226,w,76,UiTheme.INSET);
  text(g,font,c.enabled?"MONITORING ENABLED":"MODULE DISABLED",x+10,y+237,c.enabled?UiTheme.GOOD:UiTheme.MUTED);
  String last=ReconnectController.INSTANCE.lastWatcher();
  text(g,font,last.isEmpty()?"No watcher alerts this session":"Last watcher alert: "+last,x+10,y+255,UiTheme.TEXT);
  var timer=ReconnectController.INSTANCE.schedule;
  text(g,font,timer.active()?"Reconnect in "+((timer.remainingMillis()+999)/1000)+" seconds":Minecraft.getInstance().level==null?"Join Donut SMP to monitor staff":"Donut SMP Staff Watch",x+10,y+273,UiTheme.SECONDARY);
  text(g,font,"Detected staff ("+rows.size()+")",x+10,y+315,UiTheme.GOLD);
  int rowY=y+334;
  if(rows.isEmpty())text(g,font,"No matching staff currently visible to the detector.",x+10,rowY,UiTheme.MUTED);
  for(var row:rows){UiTheme.panel(g,x,rowY-4,w,21,UiTheme.SURFACE);text(g,font,row.name()+"  •  "+row.presence()+ (row.nearby()?"  •  "+row.distanceLabel():row.sameServer()?"  •  same region":""),x+10,rowY,row.alertLevel()>0?UiTheme.BAD:UiTheme.TEXT);rowY+=22;}
  g.disableScissor();
  if(maxScroll>0)g.text(font,"Scroll for more",x+w-82,bottom-9,UiTheme.MUTED);
 }
 private void text(GuiGraphicsExtractor g,Font font,String text,int x,int y,int color){g.text(font,UiTheme.fit(font,text,w-20),x,y,color);}
}
