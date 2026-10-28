package net.monacraft.spellforge.arcsys.compiler;

import net.monacraft.spellforge.arcsys.language.SourceModel.Span;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Closed, deterministic ArcVM bytecode. This is data, never a JVM class file. */
public final class ArcBytecode {
    private ArcBytecode() { }

    public static final int FORMAT_VERSION = 5;

    public enum Op {
        PUSH_NONE, PUSH_BOOL, PUSH_LONG, PUSH_DOUBLE, PUSH_STRING,
        LOAD, STORE, POP, DUP,
        ADD, SUB, MUL, DIV, MOD, NEGATE, NOT,
        EQ, NE, LT, LE, GT, GE, AND, OR,
        GET_MEMBER, MAKE_LIST, MAKE_STRUCT,
        MAKE_VARIANT, IS_VARIANT, UNWRAP_VARIANT, COERCE_RESULT,
        CALL_HOST, CALL,
        SUSPEND, PARALLEL,
        JUMP, JUMP_IF_FALSE,
        ITER_INIT, ITER_HAS_NEXT, ITER_NEXT,
        RETURN, HALT
    }

    /** Runtime algebraic value used by Option and Result. */
    public record Variant(String tag, Object value, boolean hasValue) {
        public Variant {
            if (tag == null || tag.isBlank()) throw new IllegalArgumentException("tag");
        }
        public static Variant empty(String tag) { return new Variant(tag, null, false); }
        public static Variant of(String tag, Object value) { return new Variant(tag, value, true); }
    }

    /** Immutable nominal value for a user-defined struct. */
    public record StructValue(String type, Map<String, Object> fields) {
        public StructValue {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("type");
            fields = Map.copyOf(new java.util.LinkedHashMap<>(fields));
        }
    }

    public record SourceSite(String module, Span span) {
        public SourceSite { module = module == null || module.isBlank() ? "<main>" : module; }
    }

    public record Instruction(Op op, String text, long number, double decimal) {
        public Instruction {
            if (op == null) throw new IllegalArgumentException("op");
            text = text == null ? "" : text;
        }
        public static Instruction simple(Op op) { return new Instruction(op, "", 0, 0); }
        public static Instruction text(Op op, String text) { return new Instruction(op, text, 0, 0); }
        public static Instruction number(Op op, long value) { return new Instruction(op, "", value, 0); }
        public static Instruction decimal(double value) { return new Instruction(Op.PUSH_DOUBLE, "", 0, value); }
    }

    public record Program(String spellName, List<Instruction> instructions,
                          Map<String, Integer> entryPoints, Set<String> capabilities,
                          Set<String> requirements, int estimatedFuel,
                          Map<Integer, SourceSite> sourceMap) {
        public Program {
            instructions = List.copyOf(instructions);
            entryPoints = Map.copyOf(new TreeMap<>(entryPoints));
            capabilities = Set.copyOf(capabilities);
            requirements = Set.copyOf(requirements);
            sourceMap = Map.copyOf(new TreeMap<>(sourceMap));
        }
        public Program(String spellName, List<Instruction> instructions, Map<String, Integer> entryPoints,
                       Set<String> capabilities, Set<String> requirements, int estimatedFuel) {
            this(spellName, instructions, entryPoints, capabilities, requirements, estimatedFuel, Map.of());
        }

        public SourceSite sourceAt(int pc) { return sourceMap.get(pc); }

        public byte[] encode() {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeInt(0x41524335); // ARC5
                out.writeInt(FORMAT_VERSION);
                writeUtf8(out, spellName);
                out.writeInt(entryPoints.size());
                new TreeMap<>(entryPoints).forEach((name, pc) -> {
                    try { writeUtf8(out, name); out.writeInt(pc); }
                    catch (IOException e) { throw new EncodingFailure(e); }
                });
                var caps = capabilities.stream().sorted().toList();
                out.writeInt(caps.size());
                for (String capability : caps) writeUtf8(out, capability);
                var requirementsSorted = requirements.stream().sorted().toList();
                out.writeInt(requirementsSorted.size());
                for (String requirement : requirementsSorted) writeUtf8(out, requirement);
                out.writeInt(estimatedFuel);
                out.writeInt(instructions.size());
                for (Instruction instruction : instructions) {
                    out.writeByte(instruction.op().ordinal());
                    writeUtf8(out, instruction.text());
                    out.writeLong(instruction.number());
                    out.writeDouble(instruction.decimal());
                }
                out.writeInt(sourceMap.size());
                for (var entry : new TreeMap<>(sourceMap).entrySet()) {
                    out.writeInt(entry.getKey()); writeUtf8(out, entry.getValue().module());
                    Span span = entry.getValue().span();
                    out.writeInt(span.start().offset()); out.writeInt(span.start().line()); out.writeInt(span.start().column());
                    out.writeInt(span.end().offset()); out.writeInt(span.end().line()); out.writeInt(span.end().column());
                }
                out.flush();
                return bytes.toByteArray();
            } catch (EncodingFailure failure) {
                throw new IllegalStateException(failure.getCause());
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        public String sha256() {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode())); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    private static void writeUtf8(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static final class EncodingFailure extends RuntimeException {
        private EncodingFailure(Throwable cause) { super(cause); }
    }
}
