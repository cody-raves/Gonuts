package dev.doughbay.fabric;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import java.util.*;

/** Numbered doughnut and sampled timeline; no interpolation across unknown observations. */
final class CapitalCharts {
 int category;long span=86_400_000L;
 void step(int direction){category=Math.floorMod(category+direction,CapitalBreakdown.LABELS.size());}
 void range(){span=span==3_600_000L?86_400_000L:span==86_400_000L?7*86_400_000L:3_600_000L;}
 String rangeLabel(){return span==3_600_000L?"1 hour":span==86_400_000L?"24 hours":"7 days";}
 void render(GuiGraphicsExtractor g,Font font,SettingsAnalyzer.Report report,int x,int y,int w,int bottom,int mx,int my,Charts.Pending tip){
  var snapshot=report.capital();if(snapshot==null){g.text(font,"Analyze again to capture the capital breakdown",x,y,UiTheme.SECONDARY);return;}
  g.text(font,"OBSERVED CAPITAL — this account, at cost",x,y,UiTheme.GOLD);
  int radius=Math.min(62,Math.max(30,(bottom-y-145)/3)),cx=x+radius+5,cy=y+radius+21;
  double total=snapshot.knownTotal();
  for(int dy=-radius;dy<=radius;dy++){
   int start=-radius,previous=0;
   for(int dx=-radius;dx<=radius+1;dx++){
    int color=0;double dist=dx*dx+dy*dy;
    if(dx<=radius && dist<=radius*radius && dist>=radius*radius*.30){int at=slice(snapshot,Math.atan2(dy,dx));color=at<0?0xFF52697D:CapitalBreakdown.COLORS[at];}
    if(dx==-radius){previous=color;start=dx;}else if(color!=previous){if(previous!=0)g.fill(cx+start,cy+dy,cx+dx,cy+dy+1,previous);start=dx;previous=color;}
   }
  }
  g.text(font,"At cost",cx-font.width("At cost")/2,cy-5,UiTheme.SECONDARY);
  double before=0;
  for(int i=0;i<6;i++){
   Long value=snapshot.value(i);double fraction=value==null||total<=0?0:value/total;
   if(fraction>.035){double angle=(before+fraction/2)*Math.PI*2-Math.PI/2;String number=Integer.toString(i+1);int px=cx+(int)(Math.cos(angle)*radius*.78),py=cy+(int)(Math.sin(angle)*radius*.78);g.text(font,number,px-font.width(number)/2,py-4,0xFF111820);}before+=fraction;
   int lx=x+2*radius+22,ly=y+22+i*16;
   String label=(i+1)+" "+CapitalBreakdown.LABELS.get(i)+": "+(value==null?"unknown":SettingsAnalyzer.money(value));
   g.fill(lx,ly+1,lx+5,ly+7,CapitalBreakdown.COLORS[i]);g.text(font,UiTheme.fit(font,label,w-(lx-x)-12),lx+9,ly,category==i?UiTheme.GOLD:UiTheme.TEXT);
   if(mx>=lx&&mx<=x+w&&my>=ly&&my<=ly+13)tooltip(tip,mx,my,i,snapshot);
  }
  double d=(mx-cx)*(mx-cx)+(my-cy)*(my-cy);
  if(d<=radius*radius&&d>=radius*radius*.30){int hit=slice(snapshot,Math.atan2(my-cy,mx-cx));if(hit>=0)tooltip(tip,mx,my,hit,snapshot);}
  int plotTop=Math.max(cy+radius+22,y+130),plotBottom=Math.min(bottom-43,plotTop+Math.max(60,(bottom-plotTop-60)));
  g.text(font,"SAMPLED HISTORY: "+CapitalBreakdown.LABELS.get(category)+" / "+rangeLabel(),x,plotTop-13,UiTheme.GOLD);
  if(plotBottom<=plotTop+20)return;
  g.fill(x,plotTop,x+w,plotBottom,0xFF101820);g.fill(x,plotBottom-1,x+w,plotBottom,Charts.AXIS);
  long end=snapshot.at(),start=end-span;var points=report.capitalHistory().stream().filter(p->p.at()>=start&&p.at()<=end).toList();
  double max=points.stream().map(p->p.value(category)).filter(Objects::nonNull).mapToDouble(Long::doubleValue).max().orElse(0);
  g.text(font,UiTheme.fit(font,"Top "+SettingsAnalyzer.money(max)+"; snapshots only, gaps stay blank",w),x,plotBottom+3,UiTheme.MUTED);
  var format=java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm");var zone=java.time.ZoneId.systemDefault();
  String from=java.time.Instant.ofEpochMilli(start).atZone(zone).format(format),to=java.time.Instant.ofEpochMilli(end).atZone(zone).format(format);
  g.text(font,from,x,plotBottom+15,UiTheme.MUTED);g.text(font,to,x+w-font.width(to),plotBottom+15,UiTheme.MUTED);
  if(points.size()<2){g.text(font,"History is collecting; at least two snapshots needed",x+5,plotTop+10,UiTheme.SECONDARY);return;}
  for(var point:points){int px=x+(int)((point.at()-start)/(double)span*(w-2));Long value=point.value(category);if(value!=null){int height=max<=0?1:Math.max(1,(int)(value/max*(plotBottom-plotTop-5)));g.fill(px-1,plotBottom-height,px+2,plotBottom,CapitalBreakdown.COLORS[category]);}
   if(Math.abs(mx-px)<=4&&my>=plotTop&&my<=plotBottom)tooltip(tip,mx,my,category,point);
  }
  g.text(font,UiTheme.fit(font,"Partial observations; unknown buckets excluded. Asking prices are not profit.",w),x,bottom-15,UiTheme.SECONDARY);
 }
 private static int slice(CapitalBreakdown.Snapshot snapshot,double angle){
  double total=snapshot.knownTotal();if(total<=0)return -1;double fraction=(angle+Math.PI/2)/(2*Math.PI);if(fraction<0)fraction+=1;double end=0;
  for(int i=0;i<6;i++){Long value=snapshot.value(i);if(value==null||value<=0)continue;end+=value/total;if(fraction<end)return i;}return -1;
 }
 private static void tooltip(Charts.Pending tip,int mx,int my,int index,CapitalBreakdown.Snapshot snapshot){
  Long value=snapshot.value(index);long at=snapshot.at(),source=index==0?snapshot.bankAt():index<3?snapshot.ordersAt():snapshot.stockAt();
  tip.x=mx;tip.y=my;tip.rows=List.of(new Charts.TooltipRow(CapitalBreakdown.COLORS[index],CapitalBreakdown.LABELS.get(index),value==null?"unknown":SettingsAnalyzer.money(value)),new Charts.TooltipRow(0xFF9EB6C9,"Snapshot",java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm"))),new Charts.TooltipRow(0xFF9EB6C9,"Source read",source<=0?"unknown":java.time.Instant.ofEpochMilli(source).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm"))));
 }
}
