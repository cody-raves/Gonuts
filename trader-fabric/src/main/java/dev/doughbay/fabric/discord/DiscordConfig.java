package dev.doughbay.fabric.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The bot that lives inside the mod: its token, where its one message sits,
 * and who may press its buttons. Kept in {@code discord.json} next to the
 * other config; the token is never logged or drawn.
 */
public record DiscordConfig(String token, String guildId, String channelId, String operatorId, String messageId) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static DiscordConfig empty() {
        return new DiscordConfig("", "", "", "", "");
    }

    public boolean complete() {
        return !token.isBlank() && !channelId.isBlank() && !operatorId.isBlank();
    }

    public DiscordConfig withMessageId(String id) {
        return new DiscordConfig(token, guildId, channelId, operatorId, id == null ? "" : id);
    }

    public static Path file(Path directory) {
        return directory.resolve("discord.json");
    }

    public static DiscordConfig load(Path directory) {
        Path f = file(directory);
        if (!Files.exists(f)) return empty();
        try {
            JsonNode n = MAPPER.readTree(Files.readString(f));
            return new DiscordConfig(text(n, "token"), text(n, "guildId"), text(n, "channelId"),
                    text(n, "operatorId"), text(n, "messageId"));
        } catch (IOException | RuntimeException e) {
            return empty();
        }
    }

    public void save(Path directory) throws IOException {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("token", token);
        n.put("guildId", guildId);
        n.put("channelId", channelId);
        n.put("operatorId", operatorId);
        n.put("messageId", messageId);
        Files.createDirectories(directory);
        Files.writeString(file(directory), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(n));
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? "" : v.asText("").strip();
    }
}
