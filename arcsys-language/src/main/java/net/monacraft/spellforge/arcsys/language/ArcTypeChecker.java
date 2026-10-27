package net.monacraft.spellforge.arcsys.language;

import net.monacraft.spellforge.arcsys.language.ArcAst.*;
import net.monacraft.spellforge.arcsys.language.SourceModel.*;
import net.monacraft.spellforge.arcsys.language.Token.Type;

import java.util.*;

/** Local static type inference with dimension-aware Minecraft unit types. */
public final class ArcTypeChecker {
    public enum Kind { ANY, NONE, BOOL, INT, FLOAT, STRING, DURATION, DISTANCE, MANA, PERCENT, LIST, ENTITY, OPTION, RESULT }
    public record ArcType(Kind kind, ArcType element) {
        public static ArcType of(Kind kind) { return new ArcType(kind, null); }
        public static ArcType list(ArcType element) { return new ArcType(Kind.LIST, element); }
        boolean numeric() { return kind == Kind.INT || kind == Kind.FLOAT; }
    }

    private static final ArcType ANY = ArcType.of(Kind.ANY);
    private static final ArcType BOOL = ArcType.of(Kind.BOOL);
    private static final ArcType INT = ArcType.of(Kind.INT);
    private static final ArcType FLOAT = ArcType.of(Kind.FLOAT);
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Deque<Map<String, ArcType>> scopes = new ArrayDeque<>();

    public List<Diagnostic> check(ArcAst.Program program) {
        diagnostics.clear(); scopes.clear();
        for (SpellDecl spell : program.spells()) check(spell);
        return List.copyOf(diagnostics);
    }

    private void check(SpellDecl spell) {
        scopes.push(new HashMap<>());
        for (VarDecl state : spell.states()) variable(state);
        for (HandlerDecl handler : spell.handlers()) {
            scopes.push(new HashMap<>()); block(handler.body()); scopes.pop();
        }
        for (FunctionDecl function : spell.functions()) {
            scopes.push(new HashMap<>()); function.parameters().forEach(p -> define(p, ANY)); block(function.body()); scopes.pop();
        }
        scopes.pop();
    }

    private void block(Block block) {
        scopes.push(new HashMap<>());
        for (Stmt statement : block.statements()) statement(statement);
        scopes.pop();
    }

    private void statement(Stmt statement) {
        switch (statement) {
            case Block b -> block(b);
            case VarDecl v -> variable(v);
            case ExprStmt e -> infer(e.expression());
            case IfStmt i -> { requireBoolean(infer(i.condition()), i.condition()); block(i.thenBranch()); if (i.elseBranch() != null) block(i.elseBranch()); }
            case ForStmt f -> {
                ArcType iterable = infer(f.iterable());
                if (iterable.kind() != Kind.LIST && iterable.kind() != Kind.ANY) error("ARC-T5001", "for の対象はListである必要があります", f.iterable());
                scopes.push(new HashMap<>()); define(f.variable(), iterable.element() == null ? ANY : iterable.element()); block(f.body()); scopes.pop();
            }
            case RepeatStmt r -> { ArcType count = infer(r.count()); if (count.kind() != Kind.INT && count.kind() != Kind.ANY) error("ARC-T5002", "repeat回数はIntである必要があります", r.count()); block(r.body()); }
            case DeferStmt d -> block(d.body());
            case ReturnStmt r -> { if (r.value() != null) infer(r.value()); }
            case BreakStmt ignored -> { }
            case ContinueStmt ignored -> { }
        }
    }

    private void variable(VarDecl variable) {
        ArcType type = infer(variable.initializer());
        if (scopes.peek().containsKey(variable.name())) error("ARC-T5003", "同じスコープで変数が重複しています: " + variable.name(), variable);
        define(variable.name(), type);
    }

    private ArcType infer(Expr expression) {
        return switch (expression) {
            case Literal l -> literal(l.value());
            case Variable v -> lookup(v.name()).orElse(ANY);
            case ListExpr l -> {
                ArcType element = l.values().isEmpty() ? ANY : infer(l.values().getFirst());
                for (Expr value : l.values()) if (!compatible(element, infer(value))) error("ARC-T5004", "List内の型が一致しません", value);
                yield ArcType.list(element);
            }
            case Unary u -> {
                ArcType operand = infer(u.operand());
                if (u.operator() == Type.BANG) { requireBoolean(operand, u.operand()); yield BOOL; }
                if (!operand.numeric() && operand.kind() != Kind.ANY) error("ARC-T5005", "単項マイナスには数値が必要です", u);
                yield operand;
            }
            case Binary b -> binary(b);
            case Assignment a -> {
                ArcType value = infer(a.value());
                if (a.target() instanceof Variable variable) {
                    ArcType target = lookup(variable.name()).orElse(ANY);
                    if (!compatible(target, value)) error("ARC-T5006", "代入する値の型が一致しません", a);
                }
                yield value;
            }
            case Member m -> member(m);
            case Call c -> call(c);
            case MatchExpr m -> match(m);
            case VariantExpr v -> variant(v);
            case TryExpr t -> tryExpression(t);
            case BlockExpr b -> { block(b.body()); yield ArcType.of(Kind.NONE); }
        };
    }

    private ArcType variant(VariantExpr variant) {
        ArcType value = variant.value() == null ? ANY : infer(variant.value());
        return switch (variant.tag()) {
            case "None", "Some" -> new ArcType(Kind.OPTION, value);
            case "Ok", "Err" -> new ArcType(Kind.RESULT, value);
            default -> ANY;
        };
    }

    private ArcType tryExpression(TryExpr expression) {
        ArcType value = infer(expression.value());
        if (value.kind() != Kind.RESULT && value.kind() != Kind.ANY)
            error("ARC-T5015", "? 演算子はResultにだけ使用できます", expression);
        return value.element() == null ? ANY : value.element();
    }

    private ArcType match(MatchExpr match) {
        ArcType subject = infer(match.subject());
        ArcType result = null;
        boolean wildcard = false;
        Set<String> variants = new HashSet<>();
        for (MatchArm arm : match.arms()) {
            wildcard |= arm.pattern() instanceof WildcardPattern;
            if (arm.pattern() instanceof VariantPattern variant) variants.add(variant.tag());
            if (arm.pattern() instanceof LiteralPattern literal && !compatible(subject, literal(literal.value())))
                error("ARC-T5012", "match対象とパターンの型が一致しません", match);
            if (arm.pattern() instanceof VariantPattern variant && subject.kind() != Kind.RESULT
                    && subject.kind() != Kind.OPTION && subject.kind() != Kind.ANY)
                error("ARC-T5016", "バリアントパターンはOptionまたはResultに必要です", match);
            scopes.push(new HashMap<>());
            if (arm.pattern() instanceof VariantPattern variant && variant.binding() != null)
                define(variant.binding(), subject.element() == null ? ANY : subject.element());
            ArcType armType = infer(arm.value());
            scopes.pop();
            if (result == null) result = armType;
            else if (!compatible(result, armType)) error("ARC-T5013", "match各アームの結果型が一致しません", arm.value());
        }
        boolean exhaustive = subject.kind() == Kind.RESULT && variants.containsAll(Set.of("Ok", "Err"))
                || subject.kind() == Kind.OPTION && variants.containsAll(Set.of("Some", "None"));
        if (!wildcard && !exhaustive) diagnostics.add(new Diagnostic("ARC-TW5014", Severity.WARNING, "matchが全ケースを処理する保証がありません", match.span(), "最後に _ => ... を追加してください"));
        return result == null ? ANY : result;
    }

    private ArcType binary(Binary binary) {
        ArcType left = infer(binary.left()), right = infer(binary.right());
        return switch (binary.operator()) {
            case EQUAL_EQUAL, BANG_EQUAL -> { if (!compatible(left, right)) error("ARC-T5007", "比較する型が一致しません", binary); yield BOOL; }
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> { if (!compatible(left, right)) error("ARC-T5008", "大小比較する型が一致しません", binary); yield BOOL; }
            case AND_AND, OR_OR -> { requireBoolean(left, binary.left()); requireBoolean(right, binary.right()); yield BOOL; }
            case PLUS, MINUS, STAR, SLASH, PERCENT -> {
                if (!compatible(left, right) && left.kind() != Kind.ANY && right.kind() != Kind.ANY)
                    error("ARC-T5009", "異なる単位・型どうしは演算できません", binary);
                if (!(left.numeric() || left.kind() == Kind.DURATION || left.kind() == Kind.DISTANCE || left.kind() == Kind.MANA || left.kind() == Kind.ANY))
                    error("ARC-T5010", "この型は算術演算できません", binary.left());
                yield left.kind() == Kind.ANY ? right : left;
            }
            default -> ANY;
        };
    }

    private ArcType member(Member member) {
        infer(member.receiver());
        return switch (member.name()) {
            case "hp_ratio", "damage", "x", "y", "z" -> FLOAT;
            case "count", "length" -> INT;
            case "is_empty", "is_hostile", "is_attackable", "all_success" -> BOOL;
            default -> ANY;
        };
    }

    private ArcType call(Call call) {
        call.arguments().forEach(arg -> infer(arg.value()));
        String name = qualified(call.callee());
        if (name == null) return ANY;
        if (name.equals("Some")) return new ArcType(Kind.OPTION, call.arguments().isEmpty() ? ANY : infer(call.arguments().getFirst().value()));
        if (name.equals("Ok") || name.equals("Err")) return new ArcType(Kind.RESULT, call.arguments().isEmpty() ? ANY : infer(call.arguments().getFirst().value()));
        return switch (name) {
            case "mobs.hostile" -> ArcType.list(ArcType.of(Kind.ENTITY));
            case "target.nearest" -> ArcType.of(Kind.ENTITY);
            case "random.chance" -> BOOL;
            case "state.get" -> call.arguments().isEmpty() ? ANY : infer(call.arguments().getFirst().value());
            case "spell.cast", "spell.effect", "state.set", "event.emit", "time.wait", "world.particle" -> ArcType.of(Kind.RESULT);
            case "math.clamp" -> call.arguments().isEmpty() ? ANY : infer(call.arguments().getFirst().value());
            default -> ANY;
        };
    }

    private String qualified(Expr expression) {
        if (expression instanceof Variable variable) return variable.name();
        if (expression instanceof Member member) { String parent = qualified(member.receiver()); return parent == null ? null : parent + "." + member.name(); }
        return null;
    }

    private ArcType literal(Object value) {
        if (value == null) return ArcType.of(Kind.NONE);
        if (value instanceof Boolean) return BOOL;
        if (value instanceof Long) return INT;
        if (value instanceof Double) return FLOAT;
        if (value instanceof String) return ArcType.of(Kind.STRING);
        if (value instanceof UnitValue unit) return ArcType.of(switch (unit.unit()) {
            case TICKS, DAYS -> Kind.DURATION; case BLOCKS -> Kind.DISTANCE; case MANA -> Kind.MANA; case PERCENT -> Kind.PERCENT;
        });
        return ANY;
    }

    private void requireBoolean(ArcType type, Node node) { if (type.kind() != Kind.BOOL && type.kind() != Kind.ANY) error("ARC-T5011", "条件式はBoolである必要があります", node); }
    private boolean compatible(ArcType left, ArcType right) {
        if (left.kind() == Kind.ANY || right.kind() == Kind.ANY) return true;
        if (left.numeric() && right.numeric()) return true;
        return Objects.equals(left, right);
    }
    private Optional<ArcType> lookup(String name) { for (Map<String, ArcType> scope : scopes) if (scope.containsKey(name)) return Optional.of(scope.get(name)); return Optional.empty(); }
    private void define(String name, ArcType type) { scopes.peek().put(name, type); }
    private void error(String code, String message, Node node) { diagnostics.add(new Diagnostic(code, Severity.ERROR, message, node.span(), "型と単位を確認してください")); }
}
