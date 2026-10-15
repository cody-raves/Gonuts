package dev.doughbay.fabric.staffwatch;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;import net.minecraft.client.gui.screens.*;import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
public final class ReconnectController {
 public static final ReconnectController INSTANCE=new ReconnectController();
 public final ReconnectSchedule schedule=new ReconnectSchedule(System::nanoTime,ThreadLocalRandom.current());
 private ServerData target;
 private boolean automaticAttempt;
 private boolean donutSession;
 private boolean cancelledByUser;
 public boolean controlsConnection(){if(regionEscape.flow.ownsConnection())return true;if(dev.doughbay.fabric.DoughBayClient.restartOwnsConnection()&&schedule.watcher()==null)return false;var c=WatchConfig.get();return c.enabled&&c.autoDisconnect&&donutSession&&(cancelledByUser||schedule.active()||flow.phase()!=ReconnectFlow.Phase.IDLE||Minecraft.getInstance().gui.screen() instanceof DisconnectedScreen);}
 private String lastWatcher="";
 public String lastWatcher(){return lastWatcher;}
 private final ReconnectFlow flow=new ReconnectFlow(new ReconnectFlow.Client(){
  private Minecraft mc(){return Minecraft.getInstance();}
  public boolean multiplayer(){return !mc().hasSingleplayerServer()&&mc().getCurrentServer()!=null&&DonutServer.matches(mc().getCurrentServer().ip);}
  public boolean connected(){return mc().level!=null&&mc().getConnection()!=null;}
  public boolean connecting(){return mc().gui.screen() instanceof ConnectScreen || mc().gui.screen() instanceof LevelLoadingScreen || mc().gui.screen() instanceof ProgressScreen;}
  public boolean disconnectedScreen(){return mc().gui.screen() instanceof DisconnectedScreen;}
  public boolean countdownScreen(){return mc().gui.screen() instanceof ReconnectScreen;}
  public String currentAddress(){var server=mc().getCurrentServer();return server==null?null:server.ip;}
  public void disconnect(String watcher){mc().getConnection().getConnection().disconnect(Component.literal("Staff Watch: "+watcher+" triggered a watcher alert. Reconnecting in 5-10 minutes."));}
  public void showCountdown(){mc().gui.setScreen(new ReconnectScreen());}
  public void connect(String address){var server=target;if(server==null||!address.equals(server.ip))server=new ServerData("Staff Watch",address,ServerData.Type.OTHER);automaticAttempt=true;try{ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()),mc(),ServerAddress.parseString(address),server,false,null);}finally{automaticAttempt=false;}}
 },schedule);
 private final StaffRegionEscapeController regionEscape = new StaffRegionEscapeController(this::disconnectNow);
 private ReconnectController(){}
 public void alert(String watcher){
  var config=WatchConfig.get();
  if(schedule.active()||flow.phase()==ReconnectFlow.Phase.DISCONNECTING||flow.phase()==ReconnectFlow.Phase.WAITING)return;
  var mc=Minecraft.getInstance();
  if(mc.getCurrentServer()==null||!DonutServer.matches(mc.getCurrentServer().ip)||mc.hasSingleplayerServer())return;
  if(regionEscape.flow.alert(watcher,config.enabled&&config.autoDisconnect)){
   lastWatcher=watcher;
   dev.doughbay.fabric.DoughBayClient.yieldRestartToStaffWatch();
  }
 }
 private void disconnectNow(String watcher){
  var c=WatchConfig.get();var current=Minecraft.getInstance().getCurrentServer();
  if(c.enabled&&watcher!=null&&!watcher.isBlank())lastWatcher=watcher;
  if(flow.staffCooldown(watcher,c.reconnectAddress(),c.enabled&&c.autoDisconnect)){
   lastWatcher=watcher==null?"Staff":watcher;
   try{StaffWatchClient.detected(lastWatcher);}catch(RuntimeException e){System.err.println("[GoNuts Staff Watch] Alert observer failed: "+e.getMessage());}
   String address=schedule.address();
   target=current!=null&&address.equals(current.ip)?current:new ServerData("Staff Watch",address,ServerData.Type.OTHER);
  }
 }
 public void connectionStarted(ServerData server){
  regionEscape.cancel();
  target=server;cancelledByUser=false;donutSession=DonutServer.matches(server.ip);
  if(!automaticAttempt)flow.connectionStarted(WatchConfig.get().reconnectAddress(),donutSession&&WatchConfig.get().enabled&&WatchConfig.get().autoDisconnect);
 }
 public void cancel(){regionEscape.cancel();flow.cancel();target=null;cancelledByUser=true;}
 public void tick(){
  // Detector callbacks finish before world teardown, avoiding invalidating their iterators.
  if(flow.phase()==ReconnectFlow.Phase.DISCONNECTING){
   var listener=Minecraft.getInstance().getConnection();
   if(listener!=null&&!listener.getConnection().isConnected())listener.getConnection().handleDisconnection();
  }
  var mc=Minecraft.getInstance();
  if(mc.level!=null&&!mc.hasSingleplayerServer()&&mc.getCurrentServer()!=null){target=mc.getCurrentServer();cancelledByUser=false;donutSession=DonutServer.matches(target.ip);}
  regionEscape.tick(donutSession&&WatchConfig.get().enabled&&WatchConfig.get().autoDisconnect);
  flow.tick(donutSession&&WatchConfig.get().enabled&&WatchConfig.get().autoDisconnect,WatchConfig.get().reconnectAddress(),dev.doughbay.fabric.DoughBayClient.restartOwnsConnection());
 }
}
