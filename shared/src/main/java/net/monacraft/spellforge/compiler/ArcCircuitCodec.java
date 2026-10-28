package net.monacraft.spellforge.compiler;

import net.monacraft.spellforge.model.SpellModel.Circuit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless and deterministic node-circuit transport embedded in valid .arc source. */
public final class ArcCircuitCodec {
    public static final int SCHEMA = 2;
    public static final String MARKER = "// @msf-circuit-v2 ";
    private static final int MAX_PAYLOAD = 256 * 1024;

    public record PortableNode(String key, String fragmentId, int level, Map<String, String> parameters,
                               double x, double y) {
        public PortableNode { parameters = Map.copyOf(parameters); }
    }
    public record PortableEdge(String source, String sourcePort, String target, String targetPort) { }
    public record PortableCircuit(String name, List<PortableNode> nodes, List<PortableEdge> edges) {
        public PortableCircuit { nodes = List.copyOf(nodes); edges = List.copyOf(edges); }
    }

    private ArcCircuitCodec() { }

    public static String toArcSource(Circuit circuit) {
        List<PortableNode> nodes = circuit.nodes().stream().map(node -> new PortableNode(node.nodeId().toString(),
                node.fragmentId(), node.level(), node.parameters(), node.uiX(), node.uiY())).toList();
        List<PortableEdge> edges = circuit.edges().stream().map(edge -> new PortableEdge(edge.sourceNodeId().toString(),
                edge.sourcePortId(), edge.targetNodeId().toString(), edge.targetPortId())).toList();
        byte[] payload = encode(new PortableCircuit(circuit.name(), nodes, edges));
        String marker = Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "." + hex(sha256(payload));
        String spellName = circuit.name().replaceAll("[^A-Za-z0-9_]", "_");
        if (spellName.isBlank() || Character.isDigit(spellName.charAt(0))) spellName = "Circuit_" + spellName;
        return "// MonaSpellForge reversible node-circuit v2\n" + MARKER + marker + "\n"
                + "// Nodes: " + nodes.size() + " / Edges: " + edges.size() + "\n"
                + "spell " + spellName + " {\n  on cast {\n    return true;\n  }\n}\n";
    }

    public static PortableCircuit fromArcSource(String source) {
        String line = source.lines().filter(value -> value.startsWith(MARKER)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("missing circuit marker"));
        String token = line.substring(MARKER.length()).trim();
        int separator = token.lastIndexOf('.');
        if (separator < 1) throw new IllegalArgumentException("missing checksum");
        byte[] payload;
        try { payload = Base64.getUrlDecoder().decode(token.substring(0, separator)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("invalid base64 payload"); }
        if (payload.length > MAX_PAYLOAD || !constantTimeEquals(hex(sha256(payload)), token.substring(separator + 1)))
            throw new IllegalArgumentException("circuit checksum mismatch");
        return decode(payload);
    }

    private static byte[] encode(PortableCircuit circuit) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(SCHEMA); write(out, circuit.name()); out.writeInt(circuit.nodes().size());
            for (PortableNode node : circuit.nodes()) {
                write(out, node.key()); write(out, node.fragmentId()); out.writeInt(node.level());
                List<Map.Entry<String, String>> parameters = node.parameters().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList();
                out.writeInt(parameters.size());
                for (var parameter : parameters) { write(out, parameter.getKey()); write(out, parameter.getValue()); }
                out.writeDouble(node.x()); out.writeDouble(node.y());
            }
            out.writeInt(circuit.edges().size());
            for (PortableEdge edge : circuit.edges()) {
                write(out, edge.source()); write(out, edge.sourcePort()); write(out, edge.target()); write(out, edge.targetPort());
            }
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static PortableCircuit decode(byte[] payload) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != SCHEMA) throw new IllegalArgumentException("unsupported circuit schema");
            String name = read(in); int nodeCount = bounded(in.readInt(), CircuitCompiler.MAX_NODES, "nodes");
            List<PortableNode> nodes = new ArrayList<>(nodeCount);
            for (int i = 0; i < nodeCount; i++) {
                String key = read(in), fragment = read(in); int level = bounded(in.readInt(), 255, "level");
                int parameterCount = bounded(in.readInt(), 64, "parameters");
                Map<String, String> parameters = new LinkedHashMap<>();
                for (int p = 0; p < parameterCount; p++) parameters.put(read(in), read(in));
                double x = in.readDouble(), y = in.readDouble();
                if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("invalid coordinate");
                nodes.add(new PortableNode(key, fragment, level, parameters, x, y));
            }
            int edgeCount = bounded(in.readInt(), CircuitCompiler.MAX_EDGES, "edges");
            List<PortableEdge> edges = new ArrayList<>(edgeCount);
            for (int i = 0; i < edgeCount; i++) edges.add(new PortableEdge(read(in), read(in), read(in), read(in)));
            if (in.available() != 0) throw new IllegalArgumentException("trailing circuit data");
            return new PortableCircuit(name, nodes, edges);
        } catch (IOException error) { throw new IllegalArgumentException("truncated circuit payload", error); }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16_384) throw new IllegalArgumentException("circuit text too long");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String read(DataInputStream in) throws IOException {
        int length = bounded(in.readInt(), 16_384, "text");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new java.io.EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static int bounded(int value, int maximum, String name) {
        if (value < 0 || value > maximum) throw new IllegalArgumentException("invalid " + name);
        return value;
    }
    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] value) { return java.util.HexFormat.of().formatHex(value); }
    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
}
