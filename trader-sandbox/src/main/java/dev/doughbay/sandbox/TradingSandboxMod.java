package dev.doughbay.sandbox;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.dialog.*;
import net.minecraft.server.dialog.action.*;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.*;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import java.nio.file.Path;
import java.util.*;
import java.util.function.IntConsumer;

/** Server-side menus in an explicitly activated single-player world. No remote client hooks. */
public final class TradingSandboxMod implements ModInitializer {
    private final Map<UUID,SandboxLedger> accounts=new HashMap<>();
    private final Map<UUID,Draft> drafts=new HashMap<>();private int ticks;
    private static final class Draft {String item;int count;long unit;}
    @Override public void onInitialize(){
        CommandRegistrationCallback.EVENT.register((dispatcher,lookup,environment)->{
            for(String root:List.of("sandbox","orders","ah","balance","bal","follow")){
                final String channel=root;
                dispatcher.register(Commands.literal(root).requires(TradingSandboxMod::singlePlayerCommand)
                        .executes(c->route(c.getSource().getPlayerOrException(),channel,""))
                        .then(Commands.argument("text",StringArgumentType.greedyString())
                                .executes(c->route(c.getSource().getPlayerOrException(),channel,StringArgumentType.getString(c,"text")))));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(!server.isSingleplayer()||++ticks%40!=0)return;
            for(ServerPlayer p:server.getPlayerList().getPlayers()){
                SandboxLedger m=accounts.get(p.getUUID());if(m==null)continue;
                if(!m.paused())for(var e:m.advance())emit(p,m,e);
                scoreboard(p,m);save(p,m);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{
            for(ServerPlayer p:server.getPlayerList().getPlayers()){var m=accounts.get(p.getUUID());if(m!=null)save(p,m);}
            accounts.clear();drafts.clear();
        });
    }
    static boolean singlePlayerCommand(net.minecraft.commands.CommandSourceStack source){
        // Minecraft26.2 checks command restrictions against a server-less validation source when joining.
        // Actual execution still requires a real single-player server in route().
        return source.getServer()==null||source.getServer().isSingleplayer();
    }
    private int route(ServerPlayer p,String channel,String text){
        try{
            if(!p.level().getServer().isSingleplayer())throw new IllegalStateException("Single-player test worlds only");
            if(channel.equals("sandbox")&&(text.isBlank()||text.equals("help"))){
                message(p,"[SANDBOX / FAKE MONEY] /sandbox start | pause | resume | step | scenario steady/partials/stalled | seed-full | status. /orders, /ah, /balance and /follow use the synthetic venue.");return 1;
            }
            if(channel.equals("sandbox")&&text.equals("start")){
                SandboxLedger m=accounts.get(p.getUUID());if(m==null){m=SandboxLedger.load(path(p));m.seedMarket();accounts.put(p.getUUID(),m);}
                save(p,m);scoreboard(p,m);message(p,"[SANDBOX] Fake venue ready. Separate test-world balance; no server accounts or credentials are used.");return 1;
            }
            SandboxLedger m=accounts.get(p.getUUID());if(m==null)throw new IllegalStateException("Run /sandbox start in this test world first");
            switch(channel){
                case "orders"->{if(text.isBlank())ordersHome(p,m);else publicOrders(p,m,text,0);}
                case "ah"->{if(text.startsWith("sell "))listHand(p,m,Long.parseLong(text.substring(5).replace(",","")));else if(text.equals("me")||text.equals("selling"))ownListings(p,m,0);else market(p,m,text,0);}
                case "balance","bal"->message(p,"[SANDBOX] Balance: $"+coins(m.balance()));
                case "follow"->{boolean on=m.toggleFollow(text.strip());message(p,on?"You are now following "+text.strip():"You are no longer following "+text.strip());save(p,m);scoreboard(p,m);}
                case "sandbox"->{
                    if(text.equals("pause"))m.paused(true);
                    else if(text.equals("resume"))m.paused(false);
                    else if(text.equals("step"))for(var e:m.advance())emit(p,m,e);
                    else if(text.startsWith("scenario "))m.scenario(text.substring(9).strip());
                    else if(text.equals("seed-full")){m.seedFullOrders();ownOrders(p,m,0);}
                    else if(text.startsWith("ui ")){wizard(p,m,text.substring(3));return 1;}
                    else if(!text.equals("status"))throw new IllegalArgumentException("Unknown sandbox action; /sandbox help");
                    save(p,m);scoreboard(p,m);message(p,"[SANDBOX] Scenario="+m.scenario()+", step="+m.step()+", paused="+m.paused()+", balance=$"+coins(m.balance())+", auctions="+m.occupied()+"/"+m.capacity());
                }
                default->throw new IllegalArgumentException("Unknown venue command");
            }
            return 1;
        }catch(Exception e){message(p,"[SANDBOX] "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));return 0;}
    }
    private Path path(ServerPlayer p){return p.level().getServer().getWorldPath(LevelResource.ROOT).resolve("gonuts-sandbox").resolve(p.getUUID()+".json");}
    private void save(ServerPlayer p,SandboxLedger m){try{m.save(path(p));}catch(Exception e){m.paused(true);message(p,"[SANDBOX] Save failed; simulation paused. Keep this test world for recovery.");}}
    private static void message(ServerPlayer p,String text){p.sendSystemMessage(Component.literal(text));}
    private static String coins(long n){return String.format(Locale.ROOT,"%,d",n);}
    private static Item item(String id){return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));}
    private static String name(String id){return new ItemStack(item(id)).getHoverName().getString();}
    private static ItemStack icon(Item item,int count,String title,String... lore){
        ItemStack s=new ItemStack(item,count);if(title!=null)s.set(DataComponents.CUSTOM_NAME,Component.literal(title));
        s.set(DataComponents.LORE,new ItemLore(Arrays.stream(lore).map(Component::literal).map(c->(Component)c).toList()));return s;
    }
    private static void put(SimpleContainer grid,int slot,Item item,String title,String...lore){grid.setItem(slot,icon(item,1,title,lore));}
    private void menu(ServerPlayer p,String title,SimpleContainer grid,IntConsumer action){
        p.openMenu(new SimpleMenuProvider((id,inventory,player)->new ChestMenu(MenuType.GENERIC_9x6,id,inventory,grid,6){
            @Override public boolean stillValid(Player player){return player==p;}
            @Override public ItemStack quickMoveStack(Player player,int slot){return ItemStack.EMPTY;}
            @Override public void clicked(int slot,int button,ContainerInput type,Player player){
                if(player!=p||slot<0||slot>=54||button!=0||!(type==ContainerInput.PICKUP||type==ContainerInput.QUICK_MOVE))return;
                try{action.accept(slot);}catch(RuntimeException e){message(p,"[SANDBOX] "+e.getMessage());}
            }
        },Component.literal(title)));
    }
    private static void controls(SimpleContainer g,int page,boolean next,String capacity){
        if(page>0)put(g,45,Items.ARROW,"Previous Page");
        put(g,49,Items.CHEST,"Your Items",capacity);if(next)put(g,53,Items.ARROW,"Next Page");else put(g,53,Items.BARRIER,"Close");
    }
    private void ordersHome(ServerPlayer p,SandboxLedger m){var g=new SimpleContainer(54);put(g,22,Items.CHEST,"Your Orders");put(g,49,Items.COMPASS,"Search Orders");menu(p,"Orders",g,s->{if(s==22)ownOrders(p,m,0);if(s==49)searchDialog(p);});}
    private void ownOrders(ServerPlayer p,SandboxLedger m,int page){
        List<SandboxLedger.Order> rows=m.orders();int from=page*45;if(from>=rows.size()&&page>0){ownOrders(p,m,page-1);return;}
        var g=new SimpleContainer(54);Map<Integer,Long> ids=new HashMap<>();
        for(int i=from;i<Math.min(rows.size(),from+45);i++){var o=rows.get(i);int slot=i-from;ids.put(slot,o.id);
            g.setItem(slot,icon(item(o.item),1,null,coins(o.requested)+" requested","$"+coins(o.unit)+" each",coins(o.delivered)+"/"+coins(o.requested)+" delivered",o.cancelled?"Order Canceled":"6d 23h 59m until order expires"));}
        controls(g,page,from+45<rows.size(),"Order slots: "+rows.stream().filter(o->o.remaining()>0||o.owed()>0).count()+"/"+m.orderCapacity());put(g,48,Items.EMERALD,"New Order");
        menu(p,"Your Orders",g,s->{if(ids.containsKey(s))editOrder(p,m,ids.get(s));else if(s==48){drafts.put(p.getUUID(),new Draft());searchDialog(p);}else if(s==45&&page>0)ownOrders(p,m,page-1);else if(s==53&&from+45<rows.size())ownOrders(p,m,page+1);});
    }
    private void editOrder(ServerPlayer p,SandboxLedger m,long id){var o=m.order(id);var g=new SimpleContainer(54);
        put(g,11,Items.CHEST,"Collect Items","Delivered: "+o.delivered,"Awaiting collection: "+o.owed());put(g,15,Items.BARRIER,"Cancel Order");
        menu(p,"Edit Order",g,s->{if(s==11)collectMenu(p,m,id);if(s==15)cancelMenu(p,m,id);});}
    private void collectMenu(ServerPlayer p,SandboxLedger m,long id){var o=m.order(id);var g=new SimpleContainer(54);Map<Integer,Integer> quantities=new HashMap<>();int left=o.owed();
        for(int i=0;i<45&&left>0;i++){int n=Math.min(left,item(o.item).getDefaultMaxStackSize());g.setItem(i,new ItemStack(item(o.item),n));quantities.put(i,n);left-=n;}
        put(g,49,Items.ARROW,"Back");menu(p,"Orders -> Collect Items",g,s->{
            if(s==49){editOrder(p,m,id);return;}if(!quantities.containsKey(s))return;
            int n=Math.min(quantities.get(s),m.order(id).owed());if(n<=0)return;
            ItemStack physical=new ItemStack(item(o.item),n);p.getInventory().add(physical);int moved=n-physical.getCount();
            if(moved>0){m.collect(id,moved);save(p,m);}collectMenu(p,m,id);
        });}
    private void cancelMenu(ServerPlayer p,SandboxLedger m,long id){var g=new SimpleContainer(54);put(g,11,item("minecraft:lime_dye"),"Confirm");put(g,15,item("minecraft:red_dye"),"Cancel");
        menu(p,"Cancel Order",g,s->{if(s==11){m.cancel(id);save(p,m);scoreboard(p,m);message(p,"Your order was canceled");ownOrders(p,m,0);}else if(s==15)editOrder(p,m,id);});}
    private void publicOrders(ServerPlayer p,SandboxLedger m,String search,int page){
        var matches=SandboxLedger.PRICES.keySet().stream().filter(id->matches(id,search)).sorted().toList();var g=new SimpleContainer(54);
        for(int i=0;i<matches.size()&&i<45;i++){String id=matches.get(i);g.setItem(i,icon(item(id),1,null,"192 requested","$"+coins(SandboxLedger.PRICES.get(id)*9/10)+" each","0/192 delivered","Player: SandboxTraderA"));}
        put(g,49,Items.CHEST,"Your Orders");menu(p,"Orders Search",g,s->{if(s==49)ownOrders(p,m,0);});
    }
    private static boolean matches(String id,String search){String q=search.toLowerCase(Locale.ROOT).replace('_',' ').strip();return q.isBlank()||id.replace('_',' ').contains(q)||name(id).toLowerCase(Locale.ROOT).contains(q);}
    private void market(ServerPlayer p,SandboxLedger m,String search,int page){
        m.seedMarket();List<SandboxLedger.Listing> rows=m.listings().stream().filter(l->!l.sold&&!l.seller.equals("SELF")&&(SandboxLedger.ACTORS.contains(search)?l.seller.equals(search):matches(l.item,search))).sorted(Comparator.comparingLong(l->l.price)).toList();
        var g=new SimpleContainer(54);Map<Integer,Long> ids=new HashMap<>();int from=page*45;
        for(int i=from;i<Math.min(rows.size(),from+45);i++){var l=rows.get(i);ids.put(i-from,l.id);g.setItem(i-from,auctionIcon(l,p));}
        controls(g,page,from+45<rows.size(),"Auction slots: "+m.occupied()+"/"+m.capacity());
        menu(p,"Auction House ("+(page+1)+")",g,s->{if(ids.containsKey(s))purchaseMenu(p,m,ids.get(s));else if(s==49)ownListings(p,m,0);else if(s==45&&page>0)market(p,m,search,page-1);else if(s==53&&from+45<rows.size())market(p,m,search,page+1);});
    }
    private static ItemStack auctionIcon(SandboxLedger.Listing l,ServerPlayer p){return icon(item(l.item),l.count,null,"Price: $"+coins(l.price),"Seller: "+(l.seller.equals("SELF")?p.getGameProfile().name():l.seller),"Expires in: 23h 59m");}
    private void purchaseMenu(ServerPlayer p,SandboxLedger m,long id){var l=m.listing(id);var g=new SimpleContainer(54);g.setItem(13,auctionIcon(l,p));put(g,11,item("minecraft:lime_dye"),"Confirm");put(g,15,item("minecraft:red_dye"),"Cancel");
        menu(p,"Confirm Purchase",g,s->{if(s==15){p.closeContainer();return;}if(s!=11)return;
            if(l.sold){message(p,"This item was already bought");p.closeContainer();return;}
            if(room(p,l.item)<l.count){message(p,"[SANDBOX] Inventory full");return;}
            if(m.buy(id)){p.getInventory().add(new ItemStack(item(l.item),l.count));save(p,m);scoreboard(p,m);message(p,"You bought "+l.count+" "+name(l.item)+" for $ "+coins(l.price));}p.closeContainer();});}
    private void ownListings(ServerPlayer p,SandboxLedger m,int page){List<SandboxLedger.Listing> rows=m.listings().stream().filter(l->l.seller.equals("SELF")&&!l.sold).toList();var g=new SimpleContainer(54);Map<Integer,Long> ids=new HashMap<>();int from=page*45;
        for(int i=0;i<45&&from+i<m.capacity();i++){if(from+i<rows.size()){var l=rows.get(from+i);ids.put(i,l.id);g.setItem(i,auctionIcon(l,p));}else put(g,i,item("minecraft:gray_stained_glass_pane"),"Available Slot","Click to list an item");}
        controls(g,page,from+45<m.capacity(),"Auction slots: "+m.occupied()+"/"+m.capacity());
        menu(p,"Your Items",g,s->{if(ids.containsKey(s)){var l=m.listing(ids.get(s));if(l.sold||room(p,l.item)<l.count)return;int n=m.withdraw(l.id);if(n>0){p.getInventory().add(new ItemStack(item(l.item),n));save(p,m);}ownListings(p,m,page);}else if(s==45&&page>0)ownListings(p,m,page-1);else if(s==53&&from+45<m.capacity())ownListings(p,m,page+1);});}
    private void listHand(ServerPlayer p,SandboxLedger m,long price){ItemStack held=p.getMainHandItem();if(held.isEmpty())throw new IllegalStateException("Hold a collected sandbox stack");String id=BuiltInRegistries.ITEM.getKey(held.getItem()).toString();int count=held.getCount();m.list(id,count,price);p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,ItemStack.EMPTY);save(p,m);scoreboard(p,m);message(p,"You listed "+count+" "+name(id)+" for $ "+coins(price));}
    private static int room(ServerPlayer p,String id){ItemStack sample=new ItemStack(item(id));int room=0;for(ItemStack stack:p.getInventory().getNonEquipmentItems()){if(stack.isEmpty())room+=sample.getMaxStackSize();else if(ItemStack.isSameItemSameComponents(stack,sample))room+=Math.max(0,stack.getMaxStackSize()-stack.getCount());}return room;}
    private void searchDialog(ServerPlayer p){dialog(p,"Choose Item",List.of(),List.of(input("query","Search item", "")),List.of(dynamic("Search","sandbox ui search $(query)")));}
    private void wizard(ServerPlayer p,SandboxLedger m,String text){
        Draft d=drafts.computeIfAbsent(p.getUUID(),k->new Draft());
        if(text.startsWith("search ")){String q=text.substring(7).strip();var rows=SandboxLedger.PRICES.keySet().stream().filter(id->matches(id,q)).sorted().map(id->button(name(id),"sandbox ui pick "+id)).toList();dialog(p,"Choose Item ("+rows.size()+" results)",List.of(),List.of(input("query","Search item",q)),rows.isEmpty()?List.of(dynamic("Search","sandbox ui search $(query)")):rows);}
        else if(text.startsWith("pick ")){d.item=text.substring(5);if(!SandboxLedger.PRICES.containsKey(d.item))throw new IllegalArgumentException("Unknown sandbox item");dialog(p,"How many?",List.of(),List.of(input("amount","Amount","192")),List.of(dynamic("Next","sandbox ui amount $(amount)")));}
        else if(text.startsWith("amount ")){d.count=Integer.parseInt(text.substring(7).strip());if(d.count<1||d.count>4096)throw new IllegalArgumentException("Use1..4096 items");dialog(p,"Price per item",List.of(),List.of(input("price","Price", "100")),List.of(dynamic("Review Order","sandbox ui price $(price)")));}
        else if(text.startsWith("price ")){d.unit=Long.parseLong(text.substring(6).replace(",","").strip());if(d.item==null||d.count<=0||d.unit<=0)throw new IllegalArgumentException("Incomplete order");dialog(p,"Review Order",List.of("Item: "+name(d.item),"Amount: "+d.count,"Price: $ "+coins(d.unit)+" each"),List.of(),List.of(button("Create Order","sandbox ui create")));}
        else if(text.equals("create")){var o=m.place(d.item,d.count,d.unit);drafts.remove(p.getUUID());save(p,m);scoreboard(p,m);message(p,"You ordered "+o.requested+" "+name(o.item));ownOrders(p,m,0);}
        else throw new IllegalArgumentException("Unknown order dialog action");
    }
    private static Input input(String key,String label,String initial){return new Input(key,new TextInput(240,Component.literal(label),true,initial,80,Optional.empty()));}
    private static ActionButton button(String label,String command){return new ActionButton(new CommonButtonData(Component.literal(label),220),Optional.of(new StaticAction(new ClickEvent.RunCommand(command))));}
    private static ActionButton dynamic(String label,String template){return new ActionButton(new CommonButtonData(Component.literal(label),220),Optional.of(new CommandTemplate(ParsedTemplate.CODEC.parse(JsonOps.INSTANCE,new JsonPrimitive(template)).getOrThrow())));}
    private static void dialog(ServerPlayer p,String title,List<String> lines,List<Input> inputs,List<ActionButton> buttons){
        p.closeContainer();var bodies=lines.stream().map(s->(net.minecraft.server.dialog.body.DialogBody)new PlainMessage(Component.literal(s),300)).toList();
        var common=new CommonDialogData(Component.literal(title),Optional.of(Component.literal("SANDBOX — FAKE MONEY")),true,false,DialogAction.WAIT_FOR_RESPONSE,bodies,inputs);
        p.openDialog(Holder.direct(new MultiActionDialog(common,buttons,Optional.empty(),1)));
    }
    private void emit(ServerPlayer p,SandboxLedger m,SandboxLedger.Event e){
        if(e.kind().equals("DELIVERY")){message(p,e.actor()+" delivered you "+e.count()+" "+name(e.item()));if(e.completed())message(p,"Your order is complete!");}
        if(e.kind().equals("SALE"))message(p,e.actor()+" bought your "+e.count()+" "+name(e.item())+" for $ "+coins(e.coins()));
        if(e.kind().equals("LISTED")&&m.following().contains(e.actor()))message(p,e.actor()+" listed "+e.count()+" "+name(e.item())+" for $ "+coins(e.coins()));
    }
    private static void scoreboard(ServerPlayer p,SandboxLedger m){Scoreboard b=p.level().getScoreboard();Objective o=b.getObjective("gonuts_sandbox");if(o==null)o=b.addObjective("gonuts_sandbox",ObjectiveCriteria.DUMMY,Component.literal("GoNuts TEST — FAKE MONEY"),ObjectiveCriteria.RenderType.INTEGER,false,null);b.setDisplayObjective(DisplaySlot.SIDEBAR,o);
        List<String> lines=List.of("Player: "+p.getGameProfile().name(),"Balance: $"+coins(m.balance()),"Orders: "+m.orders().stream().filter(x->x.remaining()>0||x.owed()>0).count()+"/"+m.orderCapacity(),"Auctions: "+m.occupied()+"/"+m.capacity(),"Following: "+m.following().size(),"Scenario: "+m.scenario(),"Step: "+m.step());
        for(int i=0;i<lines.size();i++){var score=b.getOrCreatePlayerScore(ScoreHolder.forNameOnly("sandbox_line_"+i),o);score.set(lines.size()-i);score.display(Component.literal(lines.get(i)));}
    }
}
