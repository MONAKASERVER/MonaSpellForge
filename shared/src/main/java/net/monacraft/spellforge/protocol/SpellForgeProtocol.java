package net.monacraft.spellforge.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public final class SpellForgeProtocol {
    public static final int VERSION = 1;
    public static final int MAX_PACKET_BYTES = 30_000;

    // Keep new values at the end so v1 clients retain all existing ordinals.
    public enum Type {
        HELLO, HELLO_ACK, OPEN_EDITOR, SNAPSHOT, EDIT_OP, VALIDATE_REQ, VALIDATE_RESULT,
        SAVE_REQ, SAVE_RESULT, CONFLICT, ERROR, PING, EDITOR_DATA,
        CIRCUIT_LIBRARY, CIRCUIT_LIBRARY_REQ, CIRCUIT_OPEN_REQ, CIRCUIT_CREATE_REQ, CIRCUIT_DELETE_REQ,
        CIRCUIT_TOGGLE_REQ,
        ARC_REQUEST, ARC_RESPONSE,
        STOW_NODE_REQ
    }
    public record Frame(Type type, UUID requestId, String sessionToken, UUID circuitId, long baseRevision, String payload) {}

    private SpellForgeProtocol() {}

    public static byte[] encode(Frame frame) {
        Objects.requireNonNull(frame); Objects.requireNonNull(frame.type()); Objects.requireNonNull(frame.requestId());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(VERSION); out.writeByte(frame.type().ordinal());
                out.writeLong(frame.requestId().getMostSignificantBits()); out.writeLong(frame.requestId().getLeastSignificantBits());
                writeString(out, Objects.requireNonNullElse(frame.sessionToken(), ""), 128);
                out.writeBoolean(frame.circuitId() != null);
                if (frame.circuitId() != null) { out.writeLong(frame.circuitId().getMostSignificantBits()); out.writeLong(frame.circuitId().getLeastSignificantBits()); }
                out.writeLong(frame.baseRevision()); writeString(out, Objects.requireNonNullElse(frame.payload(), ""), 24_000);
            }
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_PACKET_BYTES) throw new IllegalArgumentException("Payload too large");
            return result;
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public static Frame decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PACKET_BYTES) throw new IOException("Invalid packet size");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != VERSION) throw new IOException("Unsupported protocol version");
            int ordinal = in.readUnsignedByte();
            if (ordinal >= Type.values().length) throw new IOException("Unknown message type");
            UUID request = new UUID(in.readLong(), in.readLong());
            String token = readString(in, 128);
            UUID circuit = in.readBoolean() ? new UUID(in.readLong(), in.readLong()) : null;
            long revision = in.readLong();
            String payload = readString(in, 24_000);
            if (in.available() != 0) throw new IOException("Trailing packet data");
            return new Frame(Type.values()[ordinal], request, token, circuit, revision, payload);
        }
    }

    private static void writeString(DataOutputStream out, String value, int maxBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IllegalArgumentException("String too large");
        out.writeShort(bytes.length); out.write(bytes);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readUnsignedShort();
        if (length > maxBytes) throw new IOException("String too large");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
}
