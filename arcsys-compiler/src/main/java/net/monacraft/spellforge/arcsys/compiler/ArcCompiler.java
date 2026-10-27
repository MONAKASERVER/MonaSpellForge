package net.monacraft.spellforge.arcsys.compiler;

import net.monacraft.spellforge.arcsys.compiler.ArcBytecode.*;
import net.monacraft.spellforge.arcsys.language.ArcAst.*;
import net.monacraft.spellforge.arcsys.language.ArcParser;
import net.monacraft.spellforge.arcsys.language.ArcTypeChecker;
import net.monacraft.spellforge.arcsys.language.SourceModel.Diagnostic;
import net.monacraft.spellforge.arcsys.language.SourceModel.Severity;
import net.monacraft.spellforge.arcsys.language.Token.Type;

import java.util.*;

import static net.monacraft.spellforge.arcsys.compiler.ArcBytecode.Op.*;

/** Deterministic source-to-ArcVM compiler with a closed host API allowlist. */
public final class ArcCompiler {
    public static final int MAX_INSTRUCTIONS = 4_096;
    private static final Map<String, ApiDefinition> APIS = apiDefinitions();

    public CompileResult compile(String source) {
        var parsed = ArcParser.parse(source);
        List<Diagnostic> diagnostics = new ArrayList<>(parsed.diagnostics());
        if (!parsed.valid() || parsed.program().spells().isEmpty()) return new CompileResult(null, diagnostics);
        diagnostics.addAll(new ArcTypeChecker().check(parsed.program()));
        if (diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR)) return new CompileResult(null, diagnostics);
        if (parsed.program().spells().size() != 1) {
            diagnostics.add(new Diagnostic("ARC-E2001", Severity.ERROR, "1ファイルには術式を1つだけ定義できます",
                parsed.program().span(), "spell 定義を別ファイルへ分割してください"));
            return new CompileResult(null, diagnostics);
        }
        Builder builder = new Builder(diagnostics);
        ArcBytecode.Program program = builder.compile(parsed.program().spells().getFirst());
        return new CompileResult(diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR) ? null : program, diagnostics);
    }

    public record CompileResult(ArcBytecode.Program program, List<Diagnostic> diagnostics) {
        public CompileResult { diagnostics = List.copyOf(diagnostics); }
        public boolean valid() { return program != null && diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR); }
    }

    public record ApiDefinition(String capability, int minArgs, int maxArgs, int fuel) { }
    public static Map<String, ApiDefinition> availableApis() { return APIS; }

    private static final class Builder {
        private final List<Instruction> code = new ArrayList<>();
        private final Map<String, Integer> entries = new TreeMap<>();
        private final Set<String> capabilities = new TreeSet<>();
        private final List<Diagnostic> diagnostics;
        private int synthetic;
        private int fuel;
        private Set<String> stateNames = Set.of();
        private Set<String> functionNames = Set.of();
        private final Deque<LoopContext> loops = new ArrayDeque<>();

        private Builder(List<Diagnostic> diagnostics) { this.diagnostics = diagnostics; }

        ArcBytecode.Program compile(SpellDecl spell) {
            stateNames = spell.states().stream().filter(value -> value.binding() == Binding.STATE).map(VarDecl::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            functionNames = spell.functions().stream().map(FunctionDecl::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (HandlerDecl handler : spell.handlers()) {
                String entry = "on:" + handler.trigger().name().toLowerCase(Locale.ROOT);
                if (entries.putIfAbsent(entry, code.size()) != null) {
                    error("ARC-E2002", "同じイベントハンドラが重複しています: " + entry, handler, "ハンドラを1つに統合してください");
                    continue;
                }
                if (handler.argument() != null) { expression(handler.argument()); emit(STORE, "$event_arg", 0, 0); }
                for (VarDecl state : spell.states()) {
                    expression(state.initializer());
                    if (state.binding() == Binding.STATE) {
                        emit(PUSH_STRING, state.name(), 0, 0);
                        emit(CALL_HOST, "state.get\u0000default,name", 2, 0);
                        capabilities.add("state.read");
                    }
                    emit(STORE, state.name(), 0, 0);
                }
                block(handler.body());
                emit(PUSH_NONE); emit(RETURN);
            }
            for (FunctionDecl function : spell.functions()) {
                String entry = "fn:" + function.name();
                if (entries.putIfAbsent(entry, code.size()) != null) error("ARC-E2003", "関数名が重複しています: " + function.name(), function, "名前を変更してください");
                List<String> parameters = function.parameters();
                for (int index = parameters.size() - 1; index >= 0; index--) emit(STORE, parameters.get(index), 0, 0);
                block(function.body()); emit(PUSH_NONE); emit(RETURN);
            }
            emit(HALT);
            if (code.size() > MAX_INSTRUCTIONS) error("ARC-E2004", "命令数が上限を超えています", spell, "術式を分割してください");
            Set<String> requirements = new TreeSet<>();
            spell.requiredFragments().forEach((id, level) -> requirements.add("fragment:" + id + ":" + level));
            spell.requiredPermissions().forEach(permission -> requirements.add("permission:" + permission));
            return new ArcBytecode.Program(spell.name(), code, entries, capabilities, requirements, Math.max(fuel, code.size()));
        }

        void block(Block block) { for (Stmt statement : block.statements()) statement(statement); }

        void statement(Stmt stmt) {
            switch (stmt) {
                case Block b -> block(b);
                case VarDecl v -> { expression(v.initializer()); emit(STORE, v.name(), 0, 0); }
                case ExprStmt e -> { expression(e.expression()); emit(POP); }
                case IfStmt i -> {
                    expression(i.condition()); int falseJump = jump(JUMP_IF_FALSE);
                    block(i.thenBranch());
                    if (i.elseBranch() != null) { int endJump = jump(JUMP); patch(falseJump); block(i.elseBranch()); patch(endJump); }
                    else patch(falseJump);
                }
                case RepeatStmt r -> compileRepeat(r);
                case ForStmt f -> compileFor(f);
                case ReturnStmt r -> { if (r.value() == null) emit(PUSH_NONE); else expression(r.value()); emit(RETURN); }
                case BreakStmt ignored -> {
                    if (loops.isEmpty()) error("ARC-E2010", "break はループ内だけで使用できます", stmt, "forまたはrepeat内へ移動してください");
                    else loops.peek().breaks.add(jump(JUMP));
                }
                case ContinueStmt ignored -> {
                    if (loops.isEmpty()) error("ARC-E2011", "continue はループ内だけで使用できます", stmt, "forまたはrepeat内へ移動してください");
                    else loops.peek().continues.add(jump(JUMP));
                }
            }
        }

        void compileRepeat(RepeatStmt repeat) {
            String counter = "$repeat" + synthetic++;
            expression(repeat.count()); emit(STORE, counter, 0, 0);
            int start = code.size();
            emit(LOAD, counter, 0, 0); emit(PUSH_LONG, "", 0, 0); emit(GT);
            int exit = jump(JUMP_IF_FALSE);
            LoopContext context = new LoopContext(); loops.push(context);
            block(repeat.body()); loops.pop();
            int continueTarget = code.size(); context.continues.forEach(at -> patchTo(at, continueTarget));
            emit(LOAD, counter, 0, 0); emit(PUSH_LONG, "", 1, 0); emit(SUB); emit(STORE, counter, 0, 0);
            emit(JUMP, "", start, 0); patch(exit); context.breaks.forEach(this::patch);
            fuel += 16;
        }

        void compileFor(ForStmt loop) {
            String iterator = "$iter" + synthetic++;
            expression(loop.iterable()); emit(ITER_INIT); emit(STORE, iterator, 0, 0);
            int start = code.size();
            emit(LOAD, iterator, 0, 0); emit(ITER_HAS_NEXT);
            int exit = jump(JUMP_IF_FALSE);
            emit(LOAD, iterator, 0, 0); emit(ITER_NEXT); emit(STORE, loop.variable(), 0, 0);
            LoopContext context = new LoopContext(); loops.push(context);
            block(loop.body()); loops.pop();
            int continueTarget = code.size(); context.continues.forEach(at -> patchTo(at, continueTarget));
            emit(JUMP, "", start, 0); patch(exit); context.breaks.forEach(this::patch);
            fuel += 32;
        }

        void expression(Expr expr) {
            switch (expr) {
                case Literal l -> literal(l.value());
                case Variable v -> emit(LOAD, v.name(), 0, 0);
                case ListExpr l -> { for (Expr value : l.values()) expression(value); emit(MAKE_LIST, "", l.values().size(), 0); }
                case Unary u -> { expression(u.operand()); emit(u.operator() == Type.MINUS ? NEGATE : NOT); }
                case Binary b -> { expression(b.left()); expression(b.right()); emit(binaryOp(b.operator())); }
                case Assignment a -> {
                    if (a.target() instanceof Variable v) {
                        expression(a.value()); emit(DUP); emit(STORE, v.name(), 0, 0);
                        if (stateNames.contains(v.name())) {
                            emit(DUP); emit(PUSH_STRING, v.name(), 0, 0);
                            emit(CALL_HOST, "state.set\u0000value,name", 2, 0); emit(POP);
                            capabilities.add("state.write");
                        }
                    }
                    else error("ARC-E2020", "プロパティへの代入は許可されていません", a, "ローカル変数へ代入してください");
                }
                case Member m -> { expression(m.receiver()); emit(GET_MEMBER, m.name(), 0, 0); }
                case Call c -> call(c);
                case MatchExpr m -> matchExpression(m);
            }
        }

        void matchExpression(MatchExpr match) {
            String subject = "$match" + synthetic++;
            expression(match.subject()); emit(STORE, subject, 0, 0);
            List<Integer> ends = new ArrayList<>();
            boolean hasWildcard = false;
            for (MatchArm arm : match.arms()) {
                int next = -1;
                if (!(arm.pattern() instanceof WildcardPattern)) {
                    emit(LOAD, subject, 0, 0);
                    if (arm.pattern() instanceof LiteralPattern literal) { literal(literal.value()); emit(EQ); }
                    else if (arm.pattern() instanceof EnumPattern enumeration) { emit(PUSH_STRING, enumeration.name(), 0, 0); emit(EQ); }
                    else if (arm.pattern() instanceof RangePattern range) {
                        emit(DUP); literal(range.start()); emit(GE);
                        emit(LOAD, subject, 0, 0); literal(range.end()); emit(range.inclusive() ? LE : LT); emit(AND);
                    }
                    next = jump(JUMP_IF_FALSE);
                } else hasWildcard = true;
                expression(arm.value()); ends.add(jump(JUMP));
                if (next >= 0) patch(next);
                if (hasWildcard) break;
            }
            if (!hasWildcard) emit(PUSH_NONE);
            ends.forEach(this::patch);
        }

        void call(Call call) {
            String api = qualifiedName(call.callee());
            ApiDefinition definition = api == null ? null : APIS.get(api);
            if (definition == null && api != null && !api.contains(".") && functionNames.contains(api)) {
                if (call.arguments().stream().anyMatch(argument -> argument.name() != null))
                    error("ARC-E2032", "自作関数では名前付き引数を使用できません", call, "位置引数を使用してください");
                for (CallArg argument : call.arguments()) expression(argument.value());
                emit(CALL, api, call.arguments().size(), 0);
                fuel += 4;
                return;
            }
            if (definition == null) {
                error("ARC-E2030", "許可されていないAPI呼び出しです: " + (api == null ? "動的呼び出し" : api), call,
                    "ArcSys StudioのAPI一覧にある関数だけを使用してください");
                emit(PUSH_NONE);
                return;
            }
            int count = call.arguments().size();
            if (count < definition.minArgs() || count > definition.maxArgs()) {
                error("ARC-E2031", api + " の引数数が不正です", call, definition.minArgs() + "〜" + definition.maxArgs() + "個にしてください");
            }
            StringBuilder names = new StringBuilder(api).append('\u0000');
            for (CallArg argument : call.arguments()) {
                expression(argument.value());
                if (names.length() > api.length() + 1) names.append(',');
                names.append(argument.name() == null ? "" : argument.name());
            }
            emit(CALL_HOST, names.toString(), count, 0);
            capabilities.add(definition.capability());
            fuel += definition.fuel();
        }

        String qualifiedName(Expr expr) {
            if (expr instanceof Variable v) return v.name();
            if (expr instanceof Member m) { String parent = qualifiedName(m.receiver()); return parent == null ? null : parent + "." + m.name(); }
            return null;
        }

        void literal(Object value) {
            if (value == null) emit(PUSH_NONE);
            else if (value instanceof Boolean b) emit(PUSH_BOOL, "", b ? 1 : 0, 0);
            else if (value instanceof net.monacraft.spellforge.arcsys.language.SourceModel.UnitValue unit) {
                if (unit.unit() == net.monacraft.spellforge.arcsys.language.SourceModel.Unit.TICKS
                        || unit.unit() == net.monacraft.spellforge.arcsys.language.SourceModel.Unit.DAYS)
                    emit(PUSH_LONG, "", unit.ticks(), 0);
                else emit(PUSH_DOUBLE, unit.unit().name(), 0, unit.value());
            }
            else if (value instanceof Long l) emit(PUSH_LONG, "", l, 0);
            else if (value instanceof Double d) emit(PUSH_DOUBLE, "", 0, d);
            else emit(PUSH_STRING, String.valueOf(value), 0, 0);
        }

        Op binaryOp(Type type) {
            return switch (type) {
                case PLUS -> ADD; case MINUS -> SUB; case STAR -> MUL; case SLASH -> DIV; case PERCENT -> MOD;
                case EQUAL_EQUAL -> EQ; case BANG_EQUAL -> NE; case LESS -> LT; case LESS_EQUAL -> LE;
                case GREATER -> GT; case GREATER_EQUAL -> GE; case AND_AND -> AND; case OR_OR -> OR;
                default -> throw new IllegalStateException("Unsupported binary operator " + type);
            };
        }

        int jump(Op op) { emit(op, "", -1, 0); return code.size() - 1; }
        void patch(int at) { Instruction old = code.get(at); code.set(at, new Instruction(old.op(), old.text(), code.size(), old.decimal())); }
        void patchTo(int at, int target) { Instruction old = code.get(at); code.set(at, new Instruction(old.op(), old.text(), target, old.decimal())); }
        void emit(Op op) { emit(op, "", 0, 0); }
        void emit(Op op, String text, long number, double decimal) { code.add(new Instruction(op, text, number, decimal)); fuel++; }
        void error(String code, String message, net.monacraft.spellforge.arcsys.language.ArcAst.Node node, String suggestion) {
            diagnostics.add(new Diagnostic(code, Severity.ERROR, message, node.span(), suggestion));
        }
        private static final class LoopContext {
            final List<Integer> breaks = new ArrayList<>();
            final List<Integer> continues = new ArrayList<>();
        }
    }

    private static Map<String, ApiDefinition> apiDefinitions() {
        Map<String, ApiDefinition> apis = new TreeMap<>();
        apis.put("mobs.hostile", new ApiDefinition("sense.hostile", 0, 2, 8));
        apis.put("entities.hostile", new ApiDefinition("sense.hostile", 0, 3, 8));
        apis.put("spell.cast", new ApiDefinition("spell.cast", 1, 2, 16));
        apis.put("spell.effect", new ApiDefinition("spell.effect", 2, 4, 12));
        apis.put("world.particle", new ApiDefinition("visual.particle", 2, 4, 8));
        apis.put("particles.spiral", new ApiDefinition("visual.particle", 1, 4, 10));
        apis.put("particles.ring", new ApiDefinition("visual.particle", 1, 4, 8));
        apis.put("particles.sphere", new ApiDefinition("visual.particle", 1, 4, 12));
        apis.put("display.magic_circle", new ApiDefinition("visual.display", 1, 5, 20));
        apis.put("display.item", new ApiDefinition("visual.display", 2, 4, 12));
        apis.put("display.text", new ApiDefinition("visual.display", 2, 4, 12));
        apis.put("virtual_structure.show", new ApiDefinition("visual.structure", 2, 4, 24));
        apis.put("math.clamp", new ApiDefinition("math", 3, 3, 2));
        apis.put("target.nearest", new ApiDefinition("sense.target", 0, 1, 6));
        apis.put("combat.can_attack", new ApiDefinition("combat.inspect", 1, 1, 3));
        apis.put("combat.damage", new ApiDefinition("combat.damage", 2, 3, 16));
        apis.put("combat.apply", new ApiDefinition("combat.effect", 3, 4, 12));
        apis.put("entity.distance", new ApiDefinition("sense.distance", 2, 2, 2));
        apis.put("raycast.entities", new ApiDefinition("sense.raycast", 1, 4, 12));
        apis.put("raycast.blocks", new ApiDefinition("sense.raycast", 1, 4, 12));
        apis.put("blocks.query", new ApiDefinition("sense.block", 1, 3, 8));
        apis.put("items.inspect", new ApiDefinition("sense.item", 1, 1, 4));
        apis.put("mana.current", new ApiDefinition("mana.read", 0, 0, 1));
        apis.put("mana.consume", new ApiDefinition("mana.consume", 1, 1, 4));
        apis.put("pdc.get", new ApiDefinition("storage.read", 1, 2, 3));
        apis.put("pdc.set", new ApiDefinition("storage.write", 2, 3, 5));
        apis.put("time.after", new ApiDefinition("time.schedule", 2, 2, 4));
        apis.put("event.cancel", new ApiDefinition("event.cancel", 0, 0, 2));
        apis.put("skript.emit", new ApiDefinition("operator.skript.bridge", 1, 2, 8));
        apis.put("random.chance", new ApiDefinition("random", 1, 1, 2));
        apis.put("time.wait", new ApiDefinition("time.schedule", 1, 1, 4));
        apis.put("event.emit", new ApiDefinition("event.emit", 1, 1, 4));
        apis.put("state.get", new ApiDefinition("state.read", 2, 2, 2));
        apis.put("state.set", new ApiDefinition("state.write", 2, 2, 3));
        return Collections.unmodifiableMap(apis);
    }
}
