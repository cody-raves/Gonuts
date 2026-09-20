package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The server list ping, spoken by hand.
 *
 * <p>DonutSMP runs many shards behind one address and one auction house, so
 * the tab list on this shard is a fraction of the market. The status ping the
 * multiplayer screen sends returns the whole network's player count, which is
 * the number the auction actually reacts to. This is the vanilla protocol: a
 * handshake with next-state 1, a status request, and a JSON reply.
 */
final class ServerPing {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ServerPing() {
    }

    /** Players online across the network, or -1 when the ping failed. */
    static int players(String host, int port, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(handshake);
            writeVarInt(h, 0x00);          // packet id: handshake
            writeVarInt(h, -1);            // protocol version: any
            writeString(h, host);
            h.writeShort(port);
            writeVarInt(h, 1);             // next state: status
            writePacket(out, handshake.toByteArray());

            ByteArrayOutputStream request = new ByteArrayOutputStream();
            writeVarInt(new DataOutputStream(request), 0x00);   // status request
            writePacket(out, request.toByteArray());

            readVarInt(in);                // packet length
            int id = readVarInt(in);
            if (id != 0x00) return -1;
            int length = readVarInt(in);
            byte[] json = new byte[Math.max(0, length)];
            in.readFully(json);
            JsonNode root = MAPPER.readTree(new String(json, StandardCharsets.UTF_8));
            JsonNode online = root.path("players").path("online");
            return online.isNumber() ? online.asInt() : -1;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static void writePacket(OutputStream out, byte[] body) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(framed);
        writeVarInt(d, body.length);
        d.write(body);
        out.write(framed.toByteArray());
        out.flush();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int v = value;
        while (true) {
            if ((v & ~0x7F) == 0) {
                out.writeByte(v);
                return;
            }
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            byte b = in.readByte();
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) return value;
            position += 7;
            if (position >= 32) throw new IOException("VarInt is too big");
        }
    }
}
