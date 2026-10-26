package dev.doughbay.fabric;

import dev.doughbay.fabric.hosted.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;

/** One discoverable home for pairing, contribution and current Swarm results. */
final class SwarmPanel {
    private int x, top, width, bottom, scroll, contentHeight, extra;
    private static final String INTRO="Find markets sooner with other bots' recent observations and fewer repeated discovery scans. Link for remote control and Swarm.";
    private String codeDraft = "", nameDraft = "GoNuts client", note = "";
    private boolean previousLinked;
    private EditBox code, name;
    private Button link, reconnect, retry, unlink, paste, website, copyWebsite, guide;
    private final DashboardWebsiteAccess websiteAccess;
    SwarmPanel() {
        this(new DashboardWebsiteAccess(uri -> Util.getPlatform().openUri(uri),
                value -> Minecraft.getInstance().keyboardHandler.setClipboard(value)));
    }
    SwarmPanel(DashboardWebsiteAccess websiteAccess) { this.websiteAccess = websiteAccess; }
    private final Map<AbstractWidget,Integer> widgets = new IdentityHashMap<>();
    void init(Font font, int x, int top, int width, int bottom, Consumer<AbstractWidget> add) {
        this.x=x; this.top=top; this.width=width; this.bottom=bottom-14; widgets.clear();
        int inner=Math.max(40,width-16), left=x+8;
        extra=Math.max(0,font.split(Component.literal(INTRO),inner).size()*12-24);
        int base=top+extra;
        Consumer<AbstractWidget> attach = widget -> {widgets.put(widget,widget.getY()-top);add.accept(widget);};
        website=CockpitButton.builder(Component.literal("1. Open website & sign in"),b ->
                websiteAccess.open()).bounds(left,base+62,inner,22).build();attach.accept(website);
        copyWebsite=CockpitButton.builder(Component.literal("Copy website address"),b -> websiteAccess.copy())
                .bounds(left,base+90,inner,20).build();attach.accept(copyWebsite);
        int pasteWidth=Math.min(86,inner/3);
        code=new EditBox(font,left,base+170,inner-pasteWidth-6,22,Component.literal("Website pairing code"));
        code.setMaxLength(16);code.setValue(codeDraft);code.setHint(Component.literal("Paste code from website"));
        code.setResponder(v -> codeDraft=v);attach.accept(code);
        paste=CockpitButton.builder(Component.literal("Paste code"),b ->
                code.setValue(Minecraft.getInstance().keyboardHandler.getClipboard()))
                .bounds(left+inner-pasteWidth,base+170,pasteWidth,22).build();attach.accept(paste);
        name=new EditBox(font,left,base+212,inner,22,Component.literal("Bot label"));
        name.setMaxLength(48);name.setValue(nameDraft);name.setResponder(v -> nameDraft=v);attach.accept(name);
        link=CockpitButton.builder(Component.literal("3. Link dashboard & join Swarm"),b -> {
            HostedDashboardClient.INSTANCE.pair(codeDraft,nameDraft);
            codeDraft="";code.setValue("");note="Connecting this bot to your website account...";
        }).bounds(left,base+246,inner,24).build();attach.accept(link);
        int half=(inner-6)/2;
        reconnect=CockpitButton.builder(Component.literal("Reconnect dashboard"),b -> HostedDashboardClient.INSTANCE.reconnect())
                .bounds(left,base+336,half,20).build();attach.accept(reconnect);
        retry=CockpitButton.builder(Component.literal("Retry Swarm connection"),b -> CommunityClient.INSTANCE.enable())
                .bounds(left+half+6,base+336,half,20).build();attach.accept(retry);
        unlink=CockpitButton.builder(Component.literal("Unlink this bot"),b -> {HostedDashboardClient.INSTANCE.unlink();note="Local link removed. Remove its entry on the website too.";})
                .bounds(left,base+364,half,20).build();attach.accept(unlink);
        guide=CockpitButton.builder(Component.literal("Swarm joining guide"),b -> Util.getPlatform().openUri(URI.create("https://gonuts.at/guide/swarm.html")))
                .bounds(left+half+6,base+364,half,20).build();attach.accept(guide);
    }
    private int maxScroll(){return Math.max(0,contentHeight-(bottom-top));}
    boolean scroll(double mx,double my,double delta){
        if(mx<x||mx>x+width||my<top||my>bottom+14)return false;
        scroll=Math.max(0,Math.min(maxScroll(),scroll-(int)(delta*26)));return true;
    }
    private int paragraph(GuiGraphicsExtractor g,Font font,String text,int y,int color){
        for(var line:font.split(Component.literal(text),Math.max(40,width-16))){g.text(font,line,x+8,y,color);y+=12;}
        return y+8;
    }
    void render(GuiGraphicsExtractor g,Font font){
        var dashboard=HostedDashboardClient.INSTANCE.view();var swarm=CommunityClient.INSTANCE.view();
        long now=System.currentTimeMillis();boolean linked=dashboard.linked(),idle=!dashboard.busy();
        if(linked!=previousLinked){scroll=0;previousLinked=linked;}
        link.active=idle&&!linked&&now>=dashboard.retryAt()&&validCode(codeDraft);
        code.active=name.active=paste.active=idle&&!linked;reconnect.active=unlink.active=idle&&linked;
        retry.active=idle&&linked&&(!swarm.path("enabled").asBoolean()||!swarm.path("guidanceEnabled").asBoolean());
        if(linked)note="";
        scroll=Math.min(scroll,maxScroll());int y=top-scroll;
        website.setMessage(Component.literal(linked?"Open your remote dashboard":"1. Open website & sign in"));
        retry.setMessage(Component.literal(swarm.path("needsRenewal").asBoolean()?"Renew Swarm membership":"Retry Swarm connection"));
        widgets.forEach((widget,offset)->{
            if(linked&&(widget==reconnect||widget==retry))offset=extra+174;
            if(linked&&(widget==unlink||widget==guide))offset=extra+202;
            int wy=top+offset-scroll;widget.setY(wy);
            widget.visible=wy>=top&&wy+widget.getHeight()<=bottom
                    &&(!linked||(widget!=code&&widget!=name&&widget!=paste&&widget!=link));
        });
        g.enableScissor(x,top,x+width,bottom);
        g.text(font,UiTheme.fit(font,linked?"YOUR BOT IS LINKED":"LINK YOUR BOT · JOIN THE SWARM",width-16),x+8,y+6,UiTheme.GOLD);
        paragraph(g,font,INTRO,y+24,UiTheme.TEXT);
        int base=y+extra;
        g.text(font,UiTheme.fit(font,websiteAccess.address(),width-16),x+8,base+116,UiTheme.TEXT);
        g.text(font,UiTheme.fit(font,websiteAccess.message(),width-16),x+8,base+130,UiTheme.SECONDARY);
        if(!linked){
            g.text(font,UiTheme.fit(font,"2. Website: Link client, then copy its code (5 min).",width-16),x+8,base+148,UiTheme.TEXT);
            g.text(font,UiTheme.fit(font,"Bot label (so you recognize it online)",width-16),x+8,base+199,UiTheme.SECONDARY);
        }
        g.text(font,UiTheme.fit(font,dashboard.status()+(linked?" · "+dashboard.name():""),width-16),x+8,base+(linked?148:282),dashboard.online()?UiTheme.GOOD:UiTheme.TEXT);
        if(linked)g.text(font,UiTheme.fit(font,swarm.path("needsRenewal").asBoolean()?"Swarm membership needs renewal — use Renew below.":!swarm.path("enabled").asBoolean()?"Swarm disconnected — use Retry Swarm connection.":swarm.path("eligible").asBoolean()?"Swarm connected — contributing":"Swarm joining / waiting for accepted contribution",width-16),x+8,base+162,UiTheme.SECONDARY);
        if(!note.isBlank())g.text(font,UiTheme.fit(font,note,width-16),x+8,base+298,UiTheme.SECONDARY);
        else if(!linked&&!codeDraft.isBlank()&&!validCode(codeDraft))g.text(font,UiTheme.fit(font,"Enter the eight-character code from the website.",width-16),x+8,base+298,UiTheme.SECONDARY);
        if(!linked)g.text(font,UiTheme.fit(font,"Code expired? Make a new one on the website.",width-16),x+8,base+314,UiTheme.MUTED);
        int row=base+(linked?238:404);
        row=paragraph(g,font,"YOUR HIVE & THE SWARM",row,UiTheme.GOLD);
        if(linked&&dashboard.online()){
            for(var entry:SwarmRank.rows(now))row=paragraph(g,font,entry[0]+": "+entry[1],row,UiTheme.TEXT);
            for(var entry:SwarmTotals.rows(now))row=paragraph(g,font,entry[0]+": "+entry[1],row,UiTheme.TEXT);
        }else row=paragraph(g,font,"Link this bot and connect to see your rank and current anonymous Swarm totals.",row,UiTheme.SECONDARY);
        row=paragraph(g,font,"Active means an accepted upload in the last 10 minutes. Rank compares available reported 24h booked profit among eligible Hives; it is an estimate, not a whole-server leaderboard.",row,UiTheme.MUTED);
        row=paragraph(g,font,"CONNECTION & CONTRIBUTION",row,UiTheme.GOLD);
        row=paragraph(g,font,"Swarm: "+swarm.path("status").asText("Waiting for connection"),row,UiTheme.TEXT);
        row=paragraph(g,font,"This session's accepted observations: "+swarm.path("accepted").asLong()+"  ·  Rejected: "+swarm.path("rejected").asLong(),row,UiTheme.SECONDARY);
        long uploaded=swarm.path("lastUpload").asLong();
        row=paragraph(g,font,uploaded<=0?"Waiting for the first accepted upload.":"Last accepted upload: "+Math.max(0,(now-uploaded)/1000)+" seconds ago",row,UiTheme.SECONDARY);
        row=paragraph(g,font,"Shared guidance: "+(!swarm.path("enabled").asBoolean()?"not connected":!swarm.path("guidanceEnabled").asBoolean()?"off — use Retry to join":swarm.path("eligible").asBoolean()?"active":"waiting for accepted contribution"),row,UiTheme.SECONDARY);
        row=paragraph(g,font,"YOUR HIVE - SHARED DATA ACTUALLY USED",row,UiTheme.GOLD);
        for(String line:SharedDataUse.lines(HostedDashboardClient.INSTANCE.hiveDataUse()))row=paragraph(g,font,line,row,UiTheme.SECONDARY);
        row=paragraph(g,font,"BROAD SCANS AVOIDED (SEPARATE)",row,UiTheme.GOLD);
        for(String line:SwarmSavingsTotals.lines(HostedDashboardClient.INSTANCE.hiveReadSavings()))
            row=paragraph(g,font,line,row,UiTheme.SECONDARY);
        row=paragraph(g,font,"WHY JOIN?",row,UiTheme.GOLD);
        row=paragraph(g,font,"Startup history: "+swarm.path("warmup").path("status").asText("Local collection"),row,UiTheme.SECONDARY);
        row=paragraph(g,font,"More eyes on the market. Your bot contributes observations to a shared database and uses other bots' recent prices, order levels and follow activity to find markets worth checking sooner.",row,UiTheme.TEXT);
        row=paragraph(g,font,"Less startup reading. A thin keyless database can use fresh shared completed-sale history when enough distinct observations cover a market. Missing coverage falls back to local collection. Your bot still checks live prices, its own orders and inventory before trading. Profit is not guaranteed.",row,UiTheme.SECONDARY);
        row=paragraph(g,font,"Swarm sharing also sends sanitized mod diagnostics and numeric settings for a rolling 24-hour private support history, linked automatically to reports. This history excludes usernames, chat and secrets. Disconnecting Swarm stops new uploads.",row,UiTheme.MUTED);
        row=paragraph(g,font,"Linking enables remote control and Swarm contribution. Shared observations can include public market-player names and payment parties. Keep the five-minute pairing code private. One bot is enough; local Hive setup is optional.",row,UiTheme.MUTED);
        contentHeight=row-y+8;g.disableScissor();
        if(maxScroll()>0)g.text(font,scroll<maxScroll()?"Scroll for benefits, connection details & Swarm stats":"Scroll up for linking controls",x+8,bottom+3,UiTheme.MUTED);
    }
    static boolean validCode(String value){
        try{DashboardProtocol.code(value);return true;}catch(IllegalArgumentException e){return false;}
    }
}
