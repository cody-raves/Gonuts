package dev.doughbay.sandbox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Deterministic fake venue. No clocks, network, live account files or Minecraft state. */
public final class SandboxLedger {
    public static final List<String> ACTORS=List.of("SandboxTraderA","SandboxTraderB","SandboxTraderC");
    public static final Map<String,Long> PRICES=Map.of("minecraft:stone",100L,"minecraft:diamond",1000L,
            "minecraft:obsidian",1200L,"minecraft:experience_bottle",2500L,"minecraft:end_crystal",30000L,
            "minecraft:beacon",350000L,"minecraft:ender_chest",4000L,"minecraft:diamond_block",10000L);
    public static final class Order {
        public long id,unit; public String item; public int requested,delivered,collected; public boolean cancelled;
        public int owed(){return delivered-collected;} public int remaining(){return cancelled?0:requested-delivered;}
    }
    public static final class Listing {
        public long id,price;public String item,seller;public int count;public boolean sold;
    }
    public record Event(String kind,String actor,String item,int count,long coins,boolean completed){}
    private int schema=1;private long balance=1_000_000_000L,next=1,step;
    private int capacity=90,orderCapacity=90,feeBps=200;
    private boolean paused;private String scenario="steady";
    private ArrayList<Order> orders=new ArrayList<>();private ArrayList<Listing> listings=new ArrayList<>();
    private LinkedHashMap<String,Integer> stock=new LinkedHashMap<>();private LinkedHashSet<String> following=new LinkedHashSet<>();
    public long balance(){return balance;} public long step(){return step;} public int capacity(){return capacity;}
    public int orderCapacity(){return orderCapacity;}public String scenario(){return scenario;}public boolean paused(){return paused;}
    public void paused(boolean value){paused=value;}
    public List<Order> orders(){return List.copyOf(orders);}public List<Listing> listings(){return List.copyOf(listings);}
    public Set<String> following(){return Set.copyOf(following);}public int stock(String item){return stock.getOrDefault(item,0);}
    public int occupied(){return (int)listings.stream().filter(l->l.seller.equals("SELF")&&!l.sold).count();}
    public long locked(){return orders.stream().mapToLong(o->o.unit*o.remaining()).sum();}
    public Order order(long id){return orders.stream().filter(o->o.id==id).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown order"));}
    public Listing listing(long id){return listings.stream().filter(l->l.id==id).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown listing"));}
    public Order place(String item,int count,long unit){
        item(item);if(count<1||count>4096||unit<1)throw new IllegalArgumentException("Quantity1..4096 and positive exact price required");
        long cost=Math.multiplyExact(count,unit);
        if(orders.stream().filter(o->o.remaining()>0||o.owed()>0).count()>=orderCapacity)throw new IllegalStateException("Your orders are full");
        if(cost>balance)throw new IllegalStateException("Not enough fake money");
        Order o=new Order();o.id=next++;o.item=item;o.unit=unit;o.requested=count;balance-=cost;orders.add(o);return o;
    }
    public int deliver(long id,int count){Order o=order(id);if(count<0)throw new IllegalArgumentException("Negative delivery");int n=Math.min(count,o.remaining());o.delivered+=n;return n;}
    public int collect(long id,int maximum){Order o=order(id);if(maximum<0)throw new IllegalArgumentException("Negative collection");int n=Math.min(maximum,o.owed());o.collected+=n;stock.merge(o.item,n,Math::addExact);return n;}
    public long cancel(long id){Order o=order(id);long refund=Math.multiplyExact(o.unit,o.remaining());balance=Math.addExact(balance,refund);o.cancelled=true;return refund;}
    public Listing list(String item,int count,long total){
        item(item);if(count<1||count>64||total<1)throw new IllegalArgumentException("Invalid listing");
        if(occupied()>=capacity)throw new IllegalStateException("You have too many listed items");
        if(stock(item)<count)throw new IllegalStateException("Stock must be collected or bought first");
        Listing l=offer(item,count,total,"SELF");stock.put(item,stock(item)-count);return l;
    }
    private Listing offer(String item,int count,long price,String seller){Listing l=new Listing();l.id=next++;l.item=item;l.count=count;l.price=price;l.seller=seller;listings.add(l);return l;}
    public boolean buy(long id){Listing l=listing(id);if(l.sold||l.seller.equals("SELF"))return false;if(l.price>balance)throw new IllegalStateException("Not enough fake money");long updated=Math.subtractExact(balance,l.price);int units=Math.addExact(stock(l.item),l.count);balance=updated;stock.put(l.item,units);l.sold=true;return true;}
    public long sell(long id){Listing l=listing(id);if(l.sold||!l.seller.equals("SELF"))return 0;long proceeds=l.price-Math.multiplyExact(l.price,feeBps)/10000;long updated=Math.addExact(balance,proceeds);l.sold=true;balance=updated;return proceeds;}
    public int withdraw(long id){Listing l=listing(id);if(l.sold||!l.seller.equals("SELF"))return 0;int updated=Math.addExact(stock(l.item),l.count);l.sold=true;stock.put(l.item,updated);return l.count;}
    public boolean toggleFollow(String actor){if(!ACTORS.contains(actor))throw new IllegalArgumentException("No such synthetic player");if(following.remove(actor))return false;following.add(actor);return true;}
    public void scenario(String value){if(!Set.of("steady","partials","stalled").contains(value))throw new IllegalArgumentException("Choose steady, partials or stalled");scenario=value;}
    public List<Event> advance(){
        step++;ArrayList<Event> events=new ArrayList<>();String actor=ACTORS.get((int)(step%ACTORS.size()));
        if(!scenario.equals("stalled"))for(Order o:orders)if(o.remaining()>0){int n=deliver(o.id,scenario.equals("partials")?7:64);events.add(new Event("DELIVERY",actor,o.item,n,0,o.remaining()==0));break;}
        if(!scenario.equals("stalled"))for(Listing l:listings)if(l.seller.equals("SELF")&&!l.sold&&l.price<=PRICES.get(l.item)*l.count*12/10){long net=sell(l.id);events.add(new Event("SALE",actor,l.item,l.count,net,false));break;}
        if(step%3==0){String item=PRICES.keySet().stream().sorted().toList().get((int)((step/3)%PRICES.size()));Listing l=offer(item,64,PRICES.get(item)*64,actor);events.add(new Event("LISTED",actor,item,l.count,l.price,false));}
        return List.copyOf(events);
    }
    public void seedMarket(){if(listings.stream().noneMatch(l->!l.seller.equals("SELF")&&!l.sold))for(String item:PRICES.keySet().stream().sorted().toList())for(int i=0;i<3;i++)offer(item,64,PRICES.get(item)*64*(95+i*5)/100,ACTORS.get(i));}
    public void seedFullOrders(){if(!orders.isEmpty())throw new IllegalStateException("Use a new sandbox world for this fixture");for(int i=0;i<48;i++){Order o=place("minecraft:stone",192,100+i);deliver(o.id,192);} }
    private static void item(String item){if(!PRICES.containsKey(item))throw new IllegalArgumentException("Item not in sandbox catalog");}
    public void save(Path path)throws IOException{Files.createDirectories(path.getParent());Path tmp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(tmp,new GsonBuilder().setPrettyPrinting().create().toJson(this));try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}}
    public static SandboxLedger load(Path path)throws IOException{
        if(!Files.exists(path))return new SandboxLedger();
        try{SandboxLedger m=new Gson().fromJson(Files.readString(path),SandboxLedger.class);m.validate();return m;}catch(RuntimeException e){throw new IOException("Invalid sandbox save; refusing to reset fake positions",e);}
    }
    private void validate(){
        if(schema!=1||balance<0||next<1||capacity!=90||orderCapacity!=90||feeBps!=200||orders==null||listings==null||stock==null||following==null)throw new IllegalStateException("Invalid sandbox state");
        Set<Long> ids=new HashSet<>();long greatest=0;for(Order o:orders){item(o.item);if(o.id<1||!ids.add(o.id)||o.unit<1||o.requested<1||o.requested>4096||o.delivered<0||o.delivered>o.requested||o.collected<0||o.collected>o.delivered)throw new IllegalStateException("Invalid saved order");Math.multiplyExact(o.requested,o.unit);greatest=Math.max(greatest,o.id);}
        for(Listing l:listings){item(l.item);if(l.id<1||!ids.add(l.id)||l.count<1||l.count>64||l.price<1||!l.seller.equals("SELF")&&!ACTORS.contains(l.seller))throw new IllegalStateException("Invalid saved listing");greatest=Math.max(greatest,l.id);}
        if(next<=greatest||stock.values().stream().anyMatch(n->n<0)||!ACTORS.containsAll(following))throw new IllegalStateException("Invalid saved counters");scenario(scenario);
    }
}
