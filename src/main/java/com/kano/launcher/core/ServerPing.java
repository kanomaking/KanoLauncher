package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Pings a Minecraft server using the standard Server List Ping protocol (1.7+): TCP handshake →
 * status request → JSON response (MOTD, player counts, version), plus a ping/pong round-trip for
 * latency. No third-party service — speaks straight to the server like the in-game multiplayer list.
 */
public final class ServerPing {

    public record Status(String motd, int online, int max, String version, long latencyMs) {}

    private ServerPing() {}

    /** Address may be {@code host} or {@code host:port} (default port 25565). */
    public static Status ping(String address, int timeoutMs) throws Exception {
        String host = address;
        int port = 25565;
        int colon = address.lastIndexOf(':');
        if (colon >= 0) {
            host = address.substring(0, colon);
            try { port = Integer.parseInt(address.substring(colon + 1).trim()); } catch (NumberFormatException ignore) {}
        }

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());

            // Handshake (next state = 1 / status).
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(hs);
            writeVarInt(h, 0x00);
            writeVarInt(h, 760);          // protocol version — value is irrelevant for status
            writeString(h, host);
            h.writeShort(port);
            writeVarInt(h, 1);
            writePacket(out, hs.toByteArray());

            // Status request (empty packet 0x00).
            ByteArrayOutputStream rq = new ByteArrayOutputStream();
            writeVarInt(new DataOutputStream(rq), 0x00);
            writePacket(out, rq.toByteArray());

            // Status response.
            readVarInt(in);               // packet length (ignored)
            readVarInt(in);               // packet id (0x00)
            int jsonLen = readVarInt(in);
            byte[] jb = in.readNBytes(jsonLen);
            String json = new String(jb, StandardCharsets.UTF_8);

            // Ping/pong for latency (best-effort).
            long t0 = System.nanoTime();
            ByteArrayOutputStream pp = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(pp);
            writeVarInt(p, 0x01);
            p.writeLong(t0);
            writePacket(out, pp.toByteArray());
            long latency = -1;
            try {
                readVarInt(in);
                readVarInt(in);
                in.readLong();
                latency = (System.nanoTime() - t0) / 1_000_000;
            } catch (Exception ignore) {
            }

            JsonObject root = new Gson().fromJson(json, JsonObject.class);
            int online = 0, max = 0;
            String ver = "";
            if (root.has("players") && root.get("players").isJsonObject()) {
                JsonObject pl = root.getAsJsonObject("players");
                if (pl.has("online")) online = pl.get("online").getAsInt();
                if (pl.has("max")) max = pl.get("max").getAsInt();
            }
            if (root.has("version") && root.get("version").isJsonObject()) {
                JsonObject v = root.getAsJsonObject("version");
                if (v.has("name")) ver = v.get("name").getAsString();
            }
            String motd = root.has("description") ? flattenChat(root.get("description")) : "";
            return new Status(motd.trim(), online, max, ver, latency);
        }
    }

    /** MOTD may be a plain string or a chat component (text + extra[]); flatten to plain text. */
    private static String flattenChat(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        StringBuilder sb = new StringBuilder();
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("text")) sb.append(o.get("text").getAsString());
            if (o.has("extra")) for (JsonElement e : o.getAsJsonArray("extra")) sb.append(flattenChat(e));
        } else if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) sb.append(flattenChat(e));
        }
        return sb.toString().replaceAll("§.", ""); // strip color codes
    }

    // ---- protocol primitives ----

    private static void writePacket(DataOutputStream out, byte[] body) throws Exception {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(framed);
        writeVarInt(d, body.length);
        d.write(body);
        out.write(framed.toByteArray());
        out.flush();
    }

    private static void writeString(DataOutputStream out, String s) throws Exception {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws Exception {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws Exception {
        int result = 0, shift = 0, b;
        do {
            b = in.readByte() & 0xFF;
            result |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new RuntimeException("VarInt too big");
        } while ((b & 0x80) != 0);
        return result;
    }
}
