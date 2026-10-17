package dev.doughbay.fabric;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** Sampled local observations; unknown categories and offline gaps stay unknown. */
public final class CapitalHistory {
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final long PERIOD=300_000,KEEP=7*86_400_000L;
 private static volatile CapitalHistory active;
 private final Path file;
 private final List<CapitalBreakdown.Snapshot> points=new ArrayList<>();
 private boolean writable=true;
 private volatile String status="History begins when this feature starts recording";
 CapitalHistory(Path file){this.file=file;load();}
 static void start(Path game){
  Thread thread=new Thread(()->{
   String account="";
   while(true){try{
    String nowAccount=DoughBayClient.account();
    if(!nowAccount.isBlank()){
     if(!nowAccount.equalsIgnoreCase(account)){
      String key=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(nowAccount.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      active=new CapitalHistory(game.resolve("gonuts-capital-history").resolve(key+".json"));account=nowAccount;
     }
     active.record(capture());
    }
   }catch(Exception failure){if(active!=null)active.status="Capital history recording unavailable; existing observations kept";}
    try{Thread.sleep(30_000);}catch(InterruptedException stop){return;}
   }
  },"gonuts-capital-history");thread.setDaemon(true);thread.start();
 }
 static CapitalBreakdown.Snapshot capture(){
  long now=System.currentTimeMillis();var watcher=DoughBayClient.watcher();var market=watcher==null?null:watcher.snapshot();
  var ledger=DoughBayClient.ledgerStats();var stock=ledger==null?null:ledger.capital(DoughBayClient.account());
  long balance=SidebarBalance.current(),balanceAt=SidebarBalance.observedAt();
  if(balance<0&&watcher!=null){var observed=watcher.balanceObservation();if(observed.account().equalsIgnoreCase(DoughBayClient.account())){balance=observed.value();balanceAt=observed.at();}}
  return CapitalBreakdown.combine(now,balance,balanceAt,
    DoughBayClient.automationSessionController().capitalOrders(),stock);
 }
 static List<CapitalBreakdown.Snapshot> points(){var h=active;return h==null?List.of():h.copy();}
 static String status(){var h=active;return h==null?"Capital history has not started yet":h.status;}
 synchronized List<CapitalBreakdown.Snapshot> copy(){return List.copyOf(points);}
 private void load(){
  if(!Files.exists(file))return;
  try{
   if(Files.size(file)>8_000_000)throw new IllegalArgumentException();var root=JSON.readTree(Files.readAllBytes(file));
   if(root.path("v").asInt()!=1||!root.path("points").isArray()||root.path("points").size()>2048)throw new IllegalArgumentException();
   long previous=0;for(var node:root.path("points")){var point=JSON.treeToValue(node,CapitalBreakdown.Snapshot.class);validate(point);if(point.at()<=previous)throw new IllegalArgumentException();previous=point.at();points.add(point);}
  }catch(Exception corrupt){points.clear();writable=false;status="Saved capital history is unreadable; existing file kept and recording disabled";}
 }
 static void validate(CapitalBreakdown.Snapshot point){
  if(point==null||point.at()<=0||point.notes()==null||point.notes().size()>20)throw new IllegalArgumentException();
  for(Long value:point.values())if(value!=null&&value<0)throw new IllegalArgumentException();
  if(point.asking()!=null&&point.asking()<0)throw new IllegalArgumentException();
 }
 synchronized void record(CapitalBreakdown.Snapshot point)throws Exception{
  if(!writable)return;validate(point);
  if(!points.isEmpty()&&point.at()-points.getLast().at()<PERIOD)return;
  points.removeIf(p->p.at()<point.at()-KEEP);while(points.size()>=2048)points.removeFirst();points.add(point);
  Files.createDirectories(file.getParent());var root=JSON.createObjectNode().put("v",1);root.set("points",JSON.valueToTree(points));
  Path temp=Files.createTempFile(file.getParent(),"capital-",".tmp");
  try{Files.write(temp,JSON.writeValueAsBytes(root));Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
  status="Snapshots every five minutes while this client runs; up to seven days. Offline and unknown intervals are not reconstructed.";
 }
}
