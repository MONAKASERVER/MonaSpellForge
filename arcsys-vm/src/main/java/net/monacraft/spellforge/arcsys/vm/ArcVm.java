package net.monacraft.spellforge.arcsys.vm;

import net.monacraft.spellforge.arcsys.compiler.ArcBytecode.*;

import java.util.*;

/** Fuel-bounded ArcVM. The VM can only communicate through the supplied closed Host interface. */
public final class ArcVm {
    public static final int MAX_STACK = 256;
    public static final int MAX_LIST = 256;
    public static final int MAX_STRING = 2_048;
    public static final int MAX_SUSPENSIONS = 256;
    public static final long MAX_WAIT_TICKS = 1_728_000L;

    public record Limits(int fuel, int maxEffects) {
        public Limits {
            if (fuel < 1 || fuel > 1_000_000) throw new IllegalArgumentException("fuel");
            if (maxEffects < 0 || maxEffects > 1_024) throw new IllegalArgumentException("maxEffects");
        }
        public static Limits production() { return new Limits(512, 64); }
    }

    public record EffectRequest(String type, Map<String, Object> parameters) {
        public EffectRequest { parameters = Map.copyOf(parameters); }
    }

    public interface Host {
        Object invoke(String api, List<Object> arguments, List<String> argumentNames, Execution execution);
        Object member(Object receiver, String name);
    }

    public static final class Execution {
        private final int maxEffects;
        private final List<EffectRequest> effects = new ArrayList<>();
        private Execution(int maxEffects) { this(maxEffects, List.of()); }
        private Execution(int maxEffects, List<EffectRequest> existing) {
            this.maxEffects = maxEffects;
            if (existing.size() > maxEffects) throw new Trap("ARC-R3024", "effect budget changed while suspended");
            effects.addAll(existing);
        }
        public void request(EffectRequest effect) {
            if (effects.size() >= maxEffects) throw new Trap("ARC-R3008", "effect budget exhausted");
            effects.add(Objects.requireNonNull(effect));
        }
        public List<EffectRequest> effects() { return List.copyOf(effects); }
    }

    public record Result(Status status, Object value, int fuelUsed, List<EffectRequest> effects,
                         String code, String message, Continuation continuation, long resumeAfterTicks) {
        public boolean resumable() { return status == Status.SUSPENDED && continuation != null; }
    }
    public enum Status { COMPLETED, SUSPENDED, TRAPPED }

    /** Opaque, in-memory ArcVM state. It cannot be constructed or modified by scripts. */
    public static final class Continuation {
        private final String programHash;
        private final int pc;
        private final List<Object> stack;
        private final Map<String, Object> variables;
        private final List<CallFrame> calls;
        private final List<EffectRequest> effects;
        private final int fuelRemaining;
        private final int initialFuel;
        private final int suspensionCount;

        private Continuation(Program program, int pc, Deque<Object> stack, Map<String, Object> variables,
                             Deque<CallFrame> calls, List<EffectRequest> effects, int fuelRemaining,
                             int initialFuel, int suspensionCount) {
            this.programHash = program.sha256(); this.pc = pc; this.stack = List.copyOf(stack);
            this.variables = Map.copyOf(variables);
            this.calls = calls.stream().map(frame -> new CallFrame(frame.returnPc(), Map.copyOf(frame.variables()))).toList();
            this.effects = List.copyOf(effects); this.fuelRemaining = fuelRemaining;
            this.initialFuel = initialFuel; this.suspensionCount = suspensionCount;
        }

        public int suspensionCount() { return suspensionCount; }
    }

    public Result run(Program program, String entry, Map<String, Object> inputs, Limits limits, Host host) {
        Objects.requireNonNull(program); Objects.requireNonNull(entry); Objects.requireNonNull(host);
        Integer start = program.entryPoints().get(entry);
        if (start == null) return trapped(0, List.of(), "ARC-R3001", "entry not found");
        List<ArcVerifier.Issue> verification = new ArcVerifier().verify(program);
        if (!verification.isEmpty())
            return trapped(0, List.of(), verification.getFirst().code(), verification.getFirst().message());
        return execute(program, host, limits, start, new ArrayDeque<>(),
                new HashMap<>(inputs == null ? Map.of() : inputs), new ArrayDeque<>(),
                new Execution(limits.maxEffects()), limits.fuel(), limits.fuel(), 0);
    }

    public Result resume(Program program, Continuation continuation, Limits limits, Host host) {
        Objects.requireNonNull(program); Objects.requireNonNull(continuation); Objects.requireNonNull(limits); Objects.requireNonNull(host);
        if (!program.sha256().equals(continuation.programHash))
            return trapped(continuation.initialFuel - continuation.fuelRemaining, continuation.effects,
                    "ARC-R3022", "program changed while suspended");
        List<ArcVerifier.Issue> verification = new ArcVerifier().verify(program);
        if (!verification.isEmpty()) return trapped(continuation.initialFuel - continuation.fuelRemaining,
                continuation.effects, verification.getFirst().code(), verification.getFirst().message());
        return execute(program, host, limits, continuation.pc, new ArrayDeque<>(continuation.stack),
                new HashMap<>(continuation.variables), new ArrayDeque<>(continuation.calls),
                new Execution(limits.maxEffects(), continuation.effects), continuation.fuelRemaining,
                continuation.initialFuel, continuation.suspensionCount);
    }

    private Result execute(Program program, Host host, Limits limits, int start, Deque<Object> stack,
                           Map<String, Object> initialVariables, Deque<CallFrame> calls, Execution execution,
                           int startingFuel, int initialFuel, int suspensionCount) {
        List<Instruction> code = program.instructions();
        Map<String, Object> variables = initialVariables;
        int pc = start;
        int fuel = startingFuel;
        try {
            while (pc >= 0 && pc < code.size()) {
                if (fuel-- <= 0) throw new Trap("ARC-R3002", "fuel exhausted");
                Instruction instruction = code.get(pc++);
                switch (instruction.op()) {
                    case PUSH_NONE -> push(stack, None.VALUE);
                    case PUSH_BOOL -> push(stack, instruction.number() != 0);
                    case PUSH_LONG -> push(stack, instruction.number());
                    case PUSH_DOUBLE -> push(stack, instruction.decimal());
                    case PUSH_STRING -> push(stack, checkedString(instruction.text()));
                    case LOAD -> push(stack, variables.getOrDefault(instruction.text(), None.VALUE));
                    case STORE -> variables.put(instruction.text(), pop(stack));
                    case POP -> pop(stack);
                    case DUP -> { Object value = pop(stack); push(stack, value); push(stack, value); }
                    case ADD, SUB, MUL, DIV, MOD, EQ, NE, LT, LE, GT, GE, AND, OR -> binary(stack, instruction.op());
                    case NEGATE -> push(stack, -number(pop(stack)).doubleValue());
                    case NOT -> push(stack, !truthy(pop(stack)));
                    case GET_MEMBER -> {
                        Object receiver = unwrap(pop(stack));
                        if (receiver instanceof Variant variant) {
                            Object value = switch (instruction.text()) {
                                case "tag" -> variant.tag();
                                case "value" -> variant.hasValue() ? variant.value() : null;
                                case "is_ok" -> variant.tag().equals("Ok");
                                case "is_err" -> variant.tag().equals("Err");
                                case "is_some" -> variant.tag().equals("Some");
                                case "is_none" -> variant.tag().equals("None");
                                default -> throw new Trap("ARC-R3020", "unknown variant member");
                            };
                            push(stack, safe(value));
                        } else push(stack, safe(host.member(receiver, instruction.text())));
                    }
                    case MAKE_LIST -> {
                        int count = Math.toIntExact(instruction.number());
                        if (count < 0 || count > MAX_LIST || count > stack.size()) throw new Trap("ARC-R3003", "invalid list size");
                        List<Object> values = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) values.add(0, unwrap(pop(stack)));
                        push(stack, List.copyOf(values));
                    }
                    case MAKE_VARIANT -> {
                        if (instruction.number() == 0) push(stack, Variant.empty(instruction.text()));
                        else if (instruction.number() == 1) push(stack, Variant.of(instruction.text(), unwrap(pop(stack))));
                        else throw new Trap("ARC-R3019", "invalid variant arity");
                    }
                    case IS_VARIANT -> {
                        Object value = unwrap(pop(stack));
                        push(stack, value instanceof Variant variant && variant.tag().equals(instruction.text()));
                    }
                    case UNWRAP_VARIANT -> {
                        Object value = unwrap(pop(stack));
                        if (!(value instanceof Variant variant) || !variant.tag().equals(instruction.text()) || !variant.hasValue())
                            throw new Trap("ARC-R3021", "variant does not contain the requested value");
                        push(stack, safe(variant.value()));
                    }
                    case COERCE_RESULT -> {
                        Object value = unwrap(pop(stack));
                        push(stack, value instanceof Variant ? value : Variant.of("Ok", value));
                    }
                    case CALL_HOST -> invokeHost(stack, instruction, host, execution);
                    case CALL -> {
                        if (calls.size() >= 32) throw new Trap("ARC-R3017", "call depth exhausted");
                        Integer target = program.entryPoints().get("fn:" + instruction.text());
                        if (target == null) throw new Trap("ARC-R3018", "function not found");
                        calls.push(new CallFrame(pc, variables));
                        variables = new HashMap<>(variables);
                        pc = target;
                    }
                    case SUSPEND -> {
                        long ticks = durationTicks(pop(stack));
                        if (ticks < 1 || ticks > MAX_WAIT_TICKS) throw new Trap("ARC-R3023", "wait duration out of bounds");
                        if (suspensionCount >= MAX_SUSPENSIONS) throw new Trap("ARC-R3025", "suspension budget exhausted");
                        push(stack, Variant.of("Ok", null));
                        Continuation continuation = new Continuation(program, pc, stack, variables, calls,
                                execution.effects(), fuel, initialFuel, suspensionCount + 1);
                        return new Result(Status.SUSPENDED, null, initialFuel - fuel, execution.effects(),
                                null, null, continuation, ticks);
                    }
                    case JUMP -> pc = checkedJump(instruction.number(), code.size());
                    case JUMP_IF_FALSE -> { if (!truthy(pop(stack))) pc = checkedJump(instruction.number(), code.size()); }
                    case ITER_INIT -> {
                        Object value = unwrap(pop(stack));
                        if (!(value instanceof Iterable<?> iterable)) throw new Trap("ARC-R3004", "value is not iterable");
                        push(stack, new Cursor(iterable.iterator()));
                    }
                    case ITER_HAS_NEXT -> { Cursor cursor = cursor(pop(stack)); push(stack, cursor.iterator.hasNext()); }
                    case ITER_NEXT -> { Cursor cursor = cursor(pop(stack)); push(stack, safe(cursor.iterator.next())); }
                    case RETURN -> {
                        Object value = stack.isEmpty() ? null : unwrap(pop(stack));
                        if (!calls.isEmpty()) {
                            CallFrame frame = calls.pop(); variables = frame.variables(); pc = frame.returnPc(); push(stack, safe(value));
                        } else return completed(value, initialFuel - fuel, execution.effects());
                    }
                    case HALT -> { return completed(null, initialFuel - fuel, execution.effects()); }
                }
            }
            throw new Trap("ARC-R3005", "program counter escaped code");
        } catch (Trap trap) {
            return trapped(initialFuel - Math.max(fuel, 0), execution.effects(), trap.code, trap.getMessage());
        } catch (RuntimeException failure) {
            return trapped(initialFuel - Math.max(fuel, 0), execution.effects(), "ARC-R3099", "safe runtime trap");
        }
    }

    private static Result completed(Object value, int fuelUsed, List<EffectRequest> effects) {
        return new Result(Status.COMPLETED, value, fuelUsed, effects, null, null, null, 0);
    }
    private static Result trapped(int fuelUsed, List<EffectRequest> effects, String code, String message) {
        return new Result(Status.TRAPPED, null, fuelUsed, effects, code, message, null, 0);
    }

    private static void invokeHost(Deque<Object> stack, Instruction instruction, Host host, Execution execution) {
        int count = Math.toIntExact(instruction.number());
        if (count < 0 || count > 32 || count > stack.size()) throw new Trap("ARC-R3006", "invalid host arguments");
        int separator = instruction.text().indexOf('\u0000');
        if (separator < 0) throw new Trap("ARC-R3007", "invalid host descriptor");
        String api = instruction.text().substring(0, separator);
        String suffix = instruction.text().substring(separator + 1);
        List<String> names = suffix.isEmpty() ? Collections.nCopies(count, "") : Arrays.asList(suffix.split(",", -1));
        if (names.size() != count) throw new Trap("ARC-R3007", "invalid host descriptor");
        List<Object> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) args.add(0, unwrap(pop(stack)));
        push(stack, safe(host.invoke(api, List.copyOf(args), List.copyOf(names), execution)));
    }

    private static void binary(Deque<Object> stack, Op op) {
        Object right = unwrap(pop(stack)); Object left = unwrap(pop(stack));
        switch (op) {
            case EQ -> push(stack, Objects.equals(left, right));
            case NE -> push(stack, !Objects.equals(left, right));
            case AND -> push(stack, truthy(left) && truthy(right));
            case OR -> push(stack, truthy(left) || truthy(right));
            case ADD -> {
                if (left instanceof String || right instanceof String) push(stack, checkedString(String.valueOf(left) + right));
                else push(stack, number(left).doubleValue() + number(right).doubleValue());
            }
            case SUB -> push(stack, number(left).doubleValue() - number(right).doubleValue());
            case MUL -> push(stack, number(left).doubleValue() * number(right).doubleValue());
            case DIV -> { double divisor = number(right).doubleValue(); if (divisor == 0) throw new Trap("ARC-R3010", "division by zero"); push(stack, number(left).doubleValue() / divisor); }
            case MOD -> { double divisor = number(right).doubleValue(); if (divisor == 0) throw new Trap("ARC-R3010", "division by zero"); push(stack, number(left).doubleValue() % divisor); }
            case LT, LE, GT, GE -> {
                double a = number(left).doubleValue(), b = number(right).doubleValue();
                push(stack, switch (op) { case LT -> a < b; case LE -> a <= b; case GT -> a > b; default -> a >= b; });
            }
            default -> throw new Trap("ARC-R3098", "invalid binary opcode");
        }
    }

    private static Number number(Object value) { if (value instanceof Number n) return n; throw new Trap("ARC-R3011", "number required"); }
    private static long durationTicks(Object value) {
        value = unwrap(value);
        if (value instanceof Long ticks) return ticks;
        if (value instanceof Integer ticks) return ticks.longValue();
        if (value instanceof Double ticks && Double.isFinite(ticks) && ticks == Math.rint(ticks)) return ticks.longValue();
        throw new Trap("ARC-R3026", "wait duration must be whole ticks");
    }
    private static boolean truthy(Object value) { value = unwrap(value); return value instanceof Boolean b ? b : value != null; }
    private static Object safe(Object value) { return value == null ? None.VALUE : value; }
    private static Object unwrap(Object value) { return value == None.VALUE ? null : value; }
    private static String checkedString(String value) { if (value.length() > MAX_STRING) throw new Trap("ARC-R3012", "string too long"); return value; }
    private static void push(Deque<Object> stack, Object value) { if (stack.size() >= MAX_STACK) throw new Trap("ARC-R3013", "stack overflow"); stack.push(safe(value)); }
    private static Object pop(Deque<Object> stack) { if (stack.isEmpty()) throw new Trap("ARC-R3014", "stack underflow"); return stack.pop(); }
    private static int checkedJump(long target, int size) { if (target < 0 || target >= size) throw new Trap("ARC-R3015", "invalid jump"); return Math.toIntExact(target); }
    private static Cursor cursor(Object value) { if (value instanceof Cursor c) return c; throw new Trap("ARC-R3016", "iterator required"); }
    private record Cursor(Iterator<?> iterator) { }
    private record CallFrame(int returnPc, Map<String, Object> variables) { }
    private enum None { VALUE }
    private static final class Trap extends RuntimeException {
        private final String code;
        private Trap(String code, String message) { super(message); this.code = code; }
    }
}
