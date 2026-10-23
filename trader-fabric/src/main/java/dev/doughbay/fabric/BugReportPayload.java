package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/** Immutable, reviewed body; retries reuse this body and its report identifier. */
public record BugReportPayload(String build, String minecraftVersion, String description,
                               String expected, Map<String, Object> diagnostics, String settingsCode) {
    public BugReportPayload(String build, String minecraftVersion, String description, String expected, Map<String,Object> diagnostics) {
        this(build,minecraftVersion,description,expected,diagnostics,SettingsCode.exportForReport());
    }
    BugReportPayload withoutSettings() { return new BugReportPayload(build,minecraftVersion,description,expected,diagnostics, ""); }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KEYS = Set.of("state", "sqlite_errors", "uncompleted_desk_actions", "disconnect_notices", "evasion_notices", "recovered_purchase_pauses");
    private static final Set<String> STATES = Set.of("SCANNING", "BID_DESK", "PREPARING_LIST", "LISTING", "MONITORING", "COOLDOWN", "PAUSED", "STOPPED", "IDLE", "BUYING", "UNKNOWN");
    public BugReportPayload {
        SettingsCode.validateReportCode(settingsCode);
        diagnostics = Map.copyOf(diagnostics);
        if (build == null || !build.matches("[0-9A-Za-z.+_-]{1,40}") || minecraftVersion == null || !minecraftVersion.matches("[0-9A-Za-z.+_-]{1,40}"))
            throw new IllegalArgumentException("Invalid build metadata");
        validateText(description, 10, 40, "Describe the problem in at least 10 characters");
        validateText(expected, 1, 30, "Describe the expected behavior");
        if (!diagnostics.keySet().equals(KEYS) || !STATES.contains(diagnostics.get("state"))) throw new IllegalArgumentException("Invalid diagnostic fields");
        for (String key : KEYS) if (!key.equals("state")) {
            Object value = diagnostics.get(key);
            if (!(value instanceof Integer || value instanceof Long) || ((Number)value).longValue() < 0 || ((Number)value).longValue() > 1_000_000_000L)
                throw new IllegalArgumentException("Invalid diagnostic counter");
        }
    }
    private static void validateText(String text, int min, int maxLines, String hint) {
        if (text == null || text.codePointCount(0, text.length()) < min || text.codePointCount(0, text.length()) > 1500 || text.split("\\n", -1).length > maxLines)
            throw new IllegalArgumentException(hint + " (maximum 1500 characters)");
        var logLine = java.util.regex.Pattern.compile("(?m)^.*(?:\\[\\d{2}:\\d{2}:\\d{2}\\]|\\d{4}-\\d{2}-\\d{2}T\\d{2}:|at [\\w.$]+\\(|Caused by:|Exception in thread|\\[main/|\\[Render thread/).*$");
        if (logLine.matcher(text).results().count() >= 3) throw new IllegalArgumentException("Remove pasted log lines; diagnostics are collected separately");
    }
    public byte[] json() {
        return json("");
    }
    /** Proposed schema 3. Sender must require grouped automatic-report capabilities first. */
    public byte[] json(String diagnosticLog, AutomaticReportMetadata automatic) {
        if (automatic == null) return json(diagnosticLog);
        var computed = AutomaticReportMetadata.of(build,minecraftVersion,
            new AutomaticReportDetector.Candidate(automatic.kind(),automatic.signature()));
        if (!computed.equals(automatic) || !description.equals(automatic.description()) || !expected.equals(automatic.expected()))
            throw new IllegalArgumentException("Automatic report must use canonical public metadata and text");
        try {
            var body = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(json(diagnosticLog));
            body.put("schemaVersion",3);
            var grouping=body.putObject("automatic");
            grouping.put("kind",automatic.kind().name());grouping.put("signature",automatic.signature().name());
            grouping.put("fingerprint",automatic.fingerprint());
            byte[] bytes=JSON.writeValueAsBytes(body);
            if(bytes.length>65536)throw new IllegalArgumentException("Automatic report exceeds receiver size limit");
            return bytes;
        } catch(java.io.IOException e){throw new IllegalStateException("Could not prepare automatic report",e);}
    }
    public byte[] json(String diagnosticLog) {
        try {
            var body = JSON.createObjectNode();
            if (settingsCode != null && !settingsCode.isEmpty()) body.put("settingsCode",settingsCode);
            body.put("schemaVersion", 1); body.put("build", build); body.put("minecraftVersion", minecraftVersion);
            body.put("description", description); body.put("expected", expected); body.set("diagnostics", JSON.valueToTree(diagnostics));
            if (diagnosticLog != null && !diagnosticLog.isEmpty()) {
                if (diagnosticLog.getBytes(StandardCharsets.UTF_8).length > BugReportLog.MAX_BYTES) throw new IllegalArgumentException("Filtered log exceeds 24 KiB");
                if (diagnosticLog.split("\n", -1).length > 200 || diagnosticLog.lines().anyMatch(line -> line.length() > 500)) throw new IllegalArgumentException("Filtered log exceeds receiver line limits");
                body.put("schemaVersion", 2); body.put("diagnosticLog", diagnosticLog);
            }
            byte[] bytes = JSON.writeValueAsBytes(body);
            int limit = diagnosticLog == null || diagnosticLog.isEmpty() ? 16384 : 65536;
            if (bytes.length > limit) throw new IllegalArgumentException("Report exceeds the receiver's size limit");
            return bytes;
        } catch (java.io.IOException e) { throw new IllegalStateException("Could not prepare report"); }
    }
    public String preview() {
        return "GoNuts bug report\nBuild: " + build + "\nMinecraft: " + minecraftVersion + "\n\nWhat happened:\n" + description
            + "\n\nExpected:\n" + expected + "\n\n" + BugReportDiagnostics.format(diagnostics)
            + (settingsCode == null || settingsCode.isEmpty() ? "" : "\n\nSanitized numeric Settings code (no text, names, webhooks, IDs or tokens):\n" + settingsCode);
    }
}
