package net.monacraft.spellforge.arcsys.compiler;

import net.monacraft.spellforge.arcsys.compiler.ArcBytecode.*;
import net.monacraft.spellforge.arcsys.language.ArcAst.*;
import net.monacraft.spellforge.arcsys.language.ArcParser;
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

        private Builder(List<Diagnostic> diagnostics) { this.diagnostics = diagnostics; }

        ArcBytecode.Program compile(SpellDecl spell) {
            for (HandlerDecl handler : spell.handlers()) {
                String entry = "on:" + handler.trigger().name().toLowerCase(Locale.ROOT);
                if (entries.putIfAbsent(entry, code.size()) != null) {
                    error("ARC-E2002", "同じイベントハンドラが重複しています: " + entry, handler, "ハンドラを1つに統合してください");
                    continue;
                }
                if (handler.argument() != null) { expression(handler.argument()); emit(STORE, "$event_arg", 0, 0); }
                block(handler.body());
                emit(PUSH_NONE); emit(RETURN);
            }
            for (FunctionDecl function : spell.functions()) {
                String entry = "fn:" + function.name();
                if (entries.putIfAbsent(entry, code.size()) != null) error("ARC-E2003", "関数名が重複しています: " + function.name(), function, "名前を変更してください");
                block(function.body()); emit(PUSH_NONE); emit(RETURN);
            }
            emit(HALT);
            if (code.size() > MAX_INSTRUCTIONS) error("ARC-E2004", "命令数が上限を超えています", spell, "術式を分割してください");
            return new ArcBytecode.Program(spell.name(), code, entries, capabilities, Math.max(fuel, code.size()));
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
                case BreakStmt ignored -> error("ARC-E2010", "break は現在のArcVMプロファイルでは使用できません", stmt, "条件付き repeat を使用してください");
                case ContinueStmt ignored -> error("ARC-E2011", "continue は現在のArcVMプロファイルでは使用できません", stmt, "if で処理を囲んでください");
            }
        }

        void compileRepeat(RepeatStmt repeat) {
            String counter = "$repeat" + synthetic++;
            expression(repeat.count()); emit(STORE, counter, 0, 0);
            int start = code.size();
            emit(LOAD, counter, 0, 0); emit(PUSH_LONG, "", 0, 0); emit(GT);
            int exit = jump(JUMP_IF_FALSE);
            block(repeat.body());
            emit(LOAD, counter, 0, 0); emit(PUSH_LONG, "", 1, 0); emit(SUB); emit(STORE, counter, 0, 0);
            emit(JUMP, "", start, 0); patch(exit);
            fuel += 16;
        }

        void compileFor(ForStmt loop) {
            String iterator = "$iter" + synthetic++;
            expression(loop.iterable()); emit(ITER_INIT); emit(STORE, iterator, 0, 0);
            int start = code.size();
            emit(LOAD, iterator, 0, 0); emit(ITER_HAS_NEXT);
            int exit = jump(JUMP_IF_FALSE);
            emit(LOAD, iterator, 0, 0); emit(ITER_NEXT); emit(STORE, loop.variable(), 0, 0);
            block(loop.body()); emit(JUMP, "", start, 0); patch(exit);
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
                    if (a.target() instanceof Variable v) { expression(a.value()); emit(DUP); emit(STORE, v.name(), 0, 0); }
                    else error("ARC-E2020", "プロパティへの代入は許可されていません", a, "ローカル変数へ代入してください");
                }
                case Member m -> { expression(m.receiver()); emit(GET_MEMBER, m.name(), 0, 0); }
                case Call c -> call(c);
            }
        }

        void call(Call call) {
            String api = qualifiedName(call.callee());
            ApiDefinition definition = api == null ? null : APIS.get(api);
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
        void emit(Op op) { emit(op, "", 0, 0); }
        void emit(Op op, String text, long number, double decimal) { code.add(new Instruction(op, text, number, decimal)); fuel++; }
        void error(String code, String message, net.monacraft.spellforge.arcsys.language.ArcAst.Node node, String suggestion) {
            diagnostics.add(new Diagnostic(code, Severity.ERROR, message, node.span(), suggestion));
        }
    }

    private static Map<String, ApiDefinition> apiDefinitions() {
        Map<String, ApiDefinition> apis = new TreeMap<>();
        apis.put("mobs.hostile", new ApiDefinition("sense.hostile", 0, 2, 8));
        apis.put("spell.cast", new ApiDefinition("spell.cast", 1, 2, 16));
        apis.put("spell.effect", new ApiDefinition("spell.effect", 2, 4, 12));
        apis.put("world.particle", new ApiDefinition("visual.particle", 2, 4, 8));
        apis.put("math.clamp", new ApiDefinition("math", 3, 3, 2));
        apis.put("target.nearest", new ApiDefinition("sense.target", 0, 1, 6));
        return Collections.unmodifiableMap(apis);
    }
}
