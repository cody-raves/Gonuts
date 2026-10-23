package dev.doughbay.fabric;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Deny-by-default diagnostic excerpt: regenerate recognized events; never copy message text. */
public final class BugReportLog {
    public static final int MAX_BYTES = 24576;
    private static final Pattern TIME = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})\\]");
    private static final Pattern STATE = Pattern.compile("DoughBay automation \\[(SCANNING|BID_DESK|PREPARING_LIST|LISTING|MONITORING|COOLDOWN|PAUSED|STOPPED|IDLE|BUYING|REPRICING)\\]");
    private static final Pattern FRAME = Pattern.compile("^\\s*at (?:[\\w.]+//)?(dev\\.doughbay\\.[A-Za-z0-9_.$]+)\\(([A-Za-z0-9_$]+\\.java):(\\d{1,6})\\)\\s*$");
    private static final Pattern SQLITE = Pattern.compile("\\b(SQLITE_(?:BUSY_SNAPSHOT|BUSY|LOCKED|ERROR|CONSTRAINT|CORRUPT|IOERR|FULL|READONLY))\\b");
    private static final Set<String> ERRORS = Set.of("SQLiteException", "IllegalStateException", "IllegalArgumentException", "NullPointerException", "IOException", "SocketException", "TimeoutException", "ConnectException");
    private static final java.util.Map<String,String> PAUSE_REASONS = java.util.Map.ofEntries(
            java.util.Map.entry("Timed out reconciling the sale notice with active listings", "SALE_RECONCILIATION_TIMEOUT"),
            java.util.Map.entry("Timed out reconciling the new active listing", "LISTING_RECONCILIATION_TIMEOUT"),
            java.util.Map.entry("Recovered purchase", "RECOVERED_PURCHASE_HOLD"),
            java.util.Map.entry("Multiple distinct exact sale notices", "SALE_NOTICE_AMBIGUOUS"),
            java.util.Map.entry("Post-sale exact coverage is internally ambiguous", "SALE_COVERAGE_AMBIGUOUS"),
            java.util.Map.entry("Post-sale own-listing state is ambiguous", "OWN_LISTING_AMBIGUOUS"),
            java.util.Map.entry("A different exact own listing appeared after the sale notice", "OWN_LISTING_CHANGED"));
    public static final String UNAVAILABLE = "Filtered GoNuts diagnostic excerpt; messages, identities and locations omitted.\nSample: diagnostic log unavailable on this client.";
    private BugReportLog() { }
    /** Every report gets filtered evidence or a fixed availability marker, never raw exception/path text. */
    public static String readForReport(Path path) {
        return readForReport(path,ReportTopic.GENERAL);
    }
    public static String readForReport(Path path,ReportTopic topic) {
        try { return read(path,topic); }
        catch (IOException | RuntimeException e) { return UNAVAILABLE; }
    }
    public static String read(Path path) throws IOException {
        return read(path,ReportTopic.GENERAL);
    }
    private static String read(Path path,ReportTopic topic) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long end = file.length(), start = Math.max(0, end - 262144);
            file.seek(start); byte[] bytes = new byte[(int)(end-start)]; file.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (start > 0) {int line = text.indexOf('\n'); text = line < 0 ? "" : text.substring(line+1);}
            return sanitize(text,topic);
        }
    }
    public static String sanitize(String text) {
        return sanitize(text,ReportTopic.GENERAL);
    }
    public static String sanitize(String text,ReportTopic topic) {
        var lines = new ArrayDeque<String>();
        var focused = new ArrayDeque<String>();
        String previousState = null;
        for (String raw : text.split("\\R")) {
            if (raw.contains("[CHAT]") || raw.contains("Authorization") || raw.contains("Bearer ")) continue;
            var frame = FRAME.matcher(raw);
            String out = null;
            if (frame.matches()) {
                String className = frame.group(1).substring(0, frame.group(1).lastIndexOf('.'));
                try {
                    Class<?> type = Class.forName(className, false, BugReportLog.class.getClassLoader());
                    String method = frame.group(1).substring(frame.group(1).lastIndexOf('.')+1);
                    String source = className.substring(className.lastIndexOf('.')+1).split("\\$")[0] + ".java";
                    if (!source.equals(frame.group(2)) || Arrays.stream(type.getDeclaredMethods()).noneMatch(m -> m.getName().equals(method))) continue;
                } catch (ClassNotFoundException | LinkageError e) {continue;}
                // Only compiled GoNuts frames are useful; source filenames and line numbers carry no report data.
                out = "  at " + frame.group(1) + "(" + frame.group(2) + ":" + frame.group(3) + ")";
            } else {
                var time = TIME.matcher(raw);
                String prefix = time.find() ? "[" + time.group(1) + "] " : "";
                var state = STATE.matcher(raw);
                String action=ActionTrace.sanitizedLogLine(raw);
                String decision=DecisionTrace.fromLog(raw);
                if(decision!=null)out=decision;
                else if(action!=null)out=prefix+action;
                else if (state.find()) {
                    String current = state.group(1);
                    String reason = "";
                    if (current.equals("PAUSED") || current.equals("STOPPED")) {
                        reason = " reason=" + PAUSE_REASONS.entrySet().stream()
                                .filter(e -> detailStartsWith(raw, state.end(), e.getKey()))
                                .map(java.util.Map.Entry::getValue).findFirst().orElse("UNCLASSIFIED");
                    }
                    String identity = current + reason;
                    if (!identity.equals(previousState)) out = prefix + "automation state=" + identity;
                    previousState = identity;
                }
                else if (raw.contains("DoughBay bid desk: bid placed:")) out = prefix + "order placement completed";
                else if (raw.contains("DoughBay automation: recovery remains blocked after")) out = prefix + "recovery prolonged hold; exposure retained";
                else if (raw.contains("DoughBay bid desk:") && raw.contains("did not complete: ABORTED")) out = prefix + "order action did not complete";
                else if (raw.contains("Client disconnected with reason:") || raw.contains("GoNuts connection lost:")) out = prefix + "connection ended (reason omitted)";
                else if (raw.contains("DoughBay Evasion:") && raw.contains("escaping")) out = prefix + "evasion escape triggered (identity/location omitted)";
                else if (raw.contains("DoughBay restart:") && raw.contains("limbo")) out = prefix + "restart recovery reported limbo";
                else {
                    for (String error : ERRORS) if (Pattern.compile("\\b" + error + "\\b").matcher(raw).find()) {
                        out = prefix + "exception type=" + error;
                        var sql = SQLITE.matcher(raw); if (sql.find()) out += " code=" + sql.group(1);
                        break;
                    }
                }
            }
            if (out != null && out.length() <= 500 && (lines.isEmpty() || !lines.peekLast().equals(out))) {
                var target=topic!=ReportTopic.GENERAL&&topic.relevant(out)?focused:lines;
                if(target.isEmpty()||!target.peekLast().equals(out))target.add(out);
                while (target.size() > 100 || String.join("\n", target).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES - 160) target.removeFirst();
            }
        }
        if(topic!=ReportTopic.GENERAL){
            var selected=new ArrayDeque<String>();
            var candidates=new java.util.ArrayList<>(focused);java.util.Collections.reverse(candidates);
            var other=new java.util.ArrayList<>(lines);java.util.Collections.reverse(other);candidates.addAll(other);
            int bytes=0;
            for(String line:candidates){int size=line.getBytes(StandardCharsets.UTF_8).length+1;if(selected.size()<100&&bytes+size<=MAX_BYTES-160){selected.add(line);bytes+=size;}}
            lines=selected;
        }
        return "Filtered GoNuts diagnostic excerpt; messages, identities and locations omitted.\nSample: last 256 KiB, up to 100 recognized events/frames.\n" + String.join("\n", lines);
    }
    private static boolean detailStartsWith(String raw, int offset, String expected) {
        String detail = raw.substring(offset).stripLeading();
        if (detail.startsWith(":")) detail = detail.substring(1).stripLeading();
        return detail.startsWith(expected);
    }
}
