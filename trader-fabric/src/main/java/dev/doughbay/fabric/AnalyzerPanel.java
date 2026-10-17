package dev.doughbay.fabric;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

final class AnalyzerPanel {
    private int x,y,w,bottom,index,scroll,maxScroll;
    private String message="";
    private boolean showCapital;
    private final CapitalCharts capitalCharts=new CapitalCharts();
    boolean scroll(double mx,double my,double delta){if(mx<x||mx>x+w||my<y+24||my>bottom-30)return false;scroll=Math.max(0,Math.min(maxScroll,scroll-(int)Math.signum(delta)*24));return true;}
    void init(int x,int y,int w,int bottom,Consumer<AbstractWidget> add,Runnable rebuild,Screen parent){
        this.x=x;this.y=y;this.w=w;this.bottom=bottom;
        add.accept(CockpitButton.builder(Component.literal("Analyze local data"),b->SettingsAnalyzer.refresh()).bounds(x,y,132,20).build());
        add.accept(CockpitButton.builder(Component.literal("Previous"),b->{if(showCapital){capitalCharts.step(-1);return;}index=Math.max(0,index-1);scroll=0;}).bounds(x+136,y,72,20).build());
        add.accept(CockpitButton.builder(Component.literal("Next"),b->{if(showCapital){capitalCharts.step(1);return;}var r=SettingsAnalyzer.report();if(r!=null)index=Math.min(r.findings().size(),index+1);scroll=0;}).bounds(x+212,y,55,20).build());
        add.accept(CockpitButton.builder(Component.literal(showCapital?"Back to findings":"Capital charts"),b->{showCapital=!showCapital;rebuild.run();}).bounds(x+273,y,103,20).build());
        if(showCapital)add.accept(CockpitButton.builder(Component.literal(capitalCharts.rangeLabel()),b->{capitalCharts.range();rebuild.run();}).bounds(x+w-72,bottom-23,72,20).build());
        add.accept(CockpitButton.builder(Component.literal("Save suggested preset"),b->{var r=SettingsAnalyzer.report();if(r!=null&&(!r.numbers().isEmpty()||!r.lists().isEmpty()))Minecraft.getInstance().gui.setScreen(new PresetsScreen(parent,r.proposedCode()));else message="No supported settings change to save; review the findings.";}).bounds(x,bottom-23,164,20).build());
        add.accept(CockpitButton.builder(Component.literal("Copy report"),b->{var r=SettingsAnalyzer.report();if(r!=null)try{Minecraft.getInstance().keyboardHandler.setClipboard(new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(r));}catch(Exception ignored){}}).bounds(x+170,bottom-23,100,20).build());
    }
    void render(GuiGraphicsExtractor g,Font font,int mx,int my,Charts.Pending tooltip){
        g.text(font,UiTheme.fit(font,message.isEmpty()?SettingsAnalyzer.status():message,w),x,y+25,UiTheme.GOLD);
        var r=SettingsAnalyzer.report();if(r==null)return;
        if(showCapital){capitalCharts.render(g,font,r,x,y+44,w,bottom-34,mx,my,tooltip);return;}
        index=Math.min(index,r.findings().size());
        List<String> lines=new ArrayList<>();
        if(index==0){lines.add("BASELINE — use Next to read each finding");lines.addAll(r.baseline());}
        else{var f=r.findings().get(index-1);lines.add(index+" / "+r.findings().size()+" — "+f.title());lines.add("Evidence: "+f.evidence());lines.add("Suggested experiment: "+f.suggestion());lines.add("Estimate: "+f.estimate());}
        var wrapped=new ArrayList<net.minecraft.util.FormattedCharSequence>();
        for(String line:lines){wrapped.addAll(font.split(Component.literal(line),w-8));wrapped.add(net.minecraft.util.FormattedCharSequence.EMPTY);}
        int top=y+42,lineHeight=font.lineHeight+3,available=bottom-32-top;
        maxScroll=Math.max(0,wrapped.size()*lineHeight-available);scroll=Math.min(scroll,maxScroll);
        g.enableScissor(x,top,x+w,bottom-32);
        int py=top-scroll;for(var line:wrapped){g.text(font,line,x,py,UiTheme.TEXT);py+=lineHeight;}
        g.disableScissor();
        if(maxScroll>0)g.text(font,"Scroll for more",x+w-95,bottom-32,UiTheme.MUTED);
    }
}
