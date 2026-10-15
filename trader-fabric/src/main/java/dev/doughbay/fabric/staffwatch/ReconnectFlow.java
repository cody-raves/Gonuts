package dev.doughbay.fabric.staffwatch;
/** State transitions shared by the real client and deterministic lifecycle tests. */
public final class ReconnectFlow {
 public interface Client {
  boolean multiplayer(); boolean connected(); boolean connecting();
  boolean disconnectedScreen(); boolean countdownScreen(); String currentAddress();
  void disconnect(String watcher); void showCountdown(); void connect(String address);
 }
 public enum Phase { IDLE, DISCONNECTING, WAITING, ATTEMPTING }
 private final Client client;
 public final ReconnectSchedule schedule;
 private Phase phase = Phase.IDLE;
 private String sessionAddress;
 public ReconnectFlow(Client client, ReconnectSchedule schedule) { this.client=client; this.schedule=schedule; }
 public Phase phase() { return phase; }
 private String destination(String configured, String fallback) {
  return configured==null || configured.isBlank() ? fallback : configured.trim();
 }
 public boolean alert(String name, String configured, boolean enabled) {
  if (!enabled || (phase!=Phase.IDLE && phase!=Phase.ATTEMPTING) || !client.multiplayer() || !client.connected()) return false;
  String address=destination(configured,client.currentAddress());
  if (address==null || address.isBlank()) return false;
  sessionAddress=address;
  schedule.begin(address,name==null ? "Staff" : name);
  phase=Phase.DISCONNECTING;
  client.disconnect(schedule.watcher());
  return true;
 }
 /** Retains the staff delay even when a region transfer itself lost the connection. */
 public boolean staffCooldown(String name, String configured, boolean enabled) {
  if(client.connected())return alert(name,configured,enabled);
  if(!enabled||schedule.active())return false;
  String address=destination(configured,sessionAddress==null?client.currentAddress():sessionAddress);
  if(address==null||address.isBlank())return false;
  sessionAddress=address;schedule.begin(address,name==null?"Staff":name);phase=Phase.DISCONNECTING;
  return true;
 }
 /** Records manual attempts, including connections that never reach a world. */
 public void connectionStarted(String address, boolean enabled) {
  cancel();
  if (enabled && address!=null && !address.isBlank()) {
   sessionAddress=address;
   phase=Phase.ATTEMPTING;
  }
 }
 public void cancel() { phase=Phase.IDLE; sessionAddress=null; schedule.cancel(); }
 public void tick(boolean enabled) { tick(enabled,""); }
 private void retry(String configured) {
  String address=destination(configured,sessionAddress);
  if (address==null || address.isBlank()) { cancel(); return; }
  sessionAddress=address;
  schedule.beginRetry(address);
  phase=Phase.WAITING;
  client.showCountdown();
 }
 public void tick(boolean enabled, String configured) {
  tick(enabled,configured,false);
 }
 /** An intentional restart hop owns ordinary reconnects, while staff delays retain priority. */
 public void tick(boolean enabled, String configured, boolean externalRecovery) {
  if(externalRecovery&&schedule.watcher()==null){cancel();return;}
  if (!enabled) { cancel(); return; }
  switch (phase) {
   case IDLE -> {
    if (client.connected() && client.multiplayer()) sessionAddress=client.currentAddress();
    else if (client.disconnectedScreen() && sessionAddress!=null) retry(configured);
    else if (!client.connected()) sessionAddress=null;
   }
   case DISCONNECTING -> {
    if (client.connecting()) { cancel(); return; }
    if (client.connected()) return;
    if (!client.disconnectedScreen()) { cancel(); return; }
    phase=Phase.WAITING;
    client.showCountdown();
   }
   case WAITING -> {
    if (client.connected() || client.connecting() || !client.countdownScreen()) { cancel(); return; }
    if (schedule.due()) {
     String address=schedule.address();
     schedule.cancel();
     phase=Phase.ATTEMPTING;
     client.connect(address);
    }
   }
   case ATTEMPTING -> {
    if (client.connected() && client.multiplayer()) {
     sessionAddress=client.currentAddress();
     phase=Phase.IDLE;
    } else if (client.disconnectedScreen()) retry(configured);
    else if (!client.connecting()) cancel();
   }
  }
 }
}
