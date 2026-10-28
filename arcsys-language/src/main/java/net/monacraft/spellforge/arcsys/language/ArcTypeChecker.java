package net.monacraft.spellforge.arcsys.language;

import net.monacraft.spellforge.arcsys.language.ArcAst.*;
import net.monacraft.spellforge.arcsys.language.ArcMagicApiCatalog.Api;
import net.monacraft.spellforge.arcsys.language.ArcMagicApiCatalog.ValueType;
import net.monacraft.spellforge.arcsys.language.SourceModel.*;
import net.monacraft.spellforge.arcsys.language.Token.Type;

import java.util.*;

/** Static semantics for the bounded, magic-specific ArcSys language. */
public final class ArcTypeChecker {
    public enum Kind {
        ANY, NONE, BOOL, INT, FLOAT, NUMBER, STRING, DURATION, DISTANCE, MANA, PERCENT,
        LIST, ENTITY, LOCATION, WORLD, VECTOR, BLOCK, ITEM, OPTION, RESULT, STRUCT, ENUM
    }

    public record ArcType(Kind kind, ArcType element, ArcType error, String nominal) {
        public ArcType(Kind kind, ArcType element) { this(kind, element, null, null); }
        public ArcType(Kind kind, ArcType element, ArcType error) { this(kind, element, error, null); }
        public static ArcType of(Kind kind) { return new ArcType(kind, null, null, null); }
        public static ArcType nominal(Kind kind, String name) { return new ArcType(kind, null, null, name); }
        public static ArcType list(ArcType element) { return new ArcType(Kind.LIST, element, null, null); }
        public static ArcType option(ArcType element) { return new ArcType(Kind.OPTION, element, null, null); }
        public static ArcType result(ArcType ok, ArcType error) { return new ArcType(Kind.RESULT, ok, error, null); }
        boolean numeric() { return kind == Kind.INT || kind == Kind.FLOAT || kind == Kind.NUMBER; }
        String display() {
            if (kind == Kind.RESULT) return "Result<" + typeName(element) + ", " + typeName(error) + ">";
            if (kind == Kind.LIST || kind == Kind.OPTION) return kindName(kind) + "<" + typeName(element) + ">";
            return nominal == null ? kindName(kind) : nominal;
        }
        private static String typeName(ArcType type) { return type == null ? "Any" : type.display(); }
        private static String kindName(Kind value) {
            String lower = value.name().toLowerCase(Locale.ROOT);
            return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }
    }

    private record Symbol(ArcType type, boolean mutable) { }
    private record FunctionSignature(List<ArcType> parameters, ArcType returns, boolean effectful) { }

    private static final ArcType ANY = ArcType.of(Kind.ANY), NONE = ArcType.of(Kind.NONE);
    private static final ArcType BOOL = ArcType.of(Kind.BOOL), INT = ArcType.of(Kind.INT);
    private static final ArcType FLOAT = ArcType.of(Kind.FLOAT), STRING = ArcType.of(Kind.STRING);
    private static final ArcType ENTITY = ArcType.of(Kind.ENTITY), LOCATION = ArcType.of(Kind.LOCATION);
    private static final ArcType WORLD = ArcType.of(Kind.WORLD), MANA = ArcType.of(Kind.MANA);
    private static final Set<String> API_NAMESPACES = ArcMagicApiCatalog.namespaces();

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Deque<Map<String, Symbol>> scopes = new ArrayDeque<>();
    private Map<String, FunctionSignature> functions = Map.of();
    private Map<String, StructDecl> structs = Map.of();
    private Map<String, EnumDecl> enums = Map.of();
    private FunctionDecl currentFunction;

    public List<Diagnostic> check(ArcAst.Program program) {
        diagnostics.clear(); scopes.clear();
        collectTypes(program.types());
        for (SpellDecl spell : program.spells()) check(spell);
        structs = Map.of(); enums = Map.of();
        return List.copyOf(diagnostics);
    }

    private void collectTypes(List<TypeDecl> declarations) {
        Map<String, StructDecl> structMap = new LinkedHashMap<>();
        Map<String, EnumDecl> enumMap = new LinkedHashMap<>();
        for (TypeDecl declaration : declarations) {
            boolean duplicate = structMap.containsKey(declaration.name()) || enumMap.containsKey(declaration.name());
            if (duplicate) error("ARC-T5042", "型名が重複しています: " + declaration.name(), declaration, "型名を変更してください");
            else if (declaration instanceof StructDecl struct) structMap.put(struct.name(), struct);
            else if (declaration instanceof EnumDecl enumeration) enumMap.put(enumeration.name(), enumeration);
        }
        structs = Map.copyOf(structMap); enums = Map.copyOf(enumMap);
        declarations.forEach(declaration -> {
            if (declaration instanceof StructDecl struct) struct.fields().forEach(field -> parseDeclared(field.type(), struct));
            if (declaration instanceof EnumDecl enumeration) enumeration.variants().stream().filter(v -> v.payloadType() != null)
                    .forEach(variant -> parseDeclared(variant.payloadType(), enumeration));
        });
    }

    private void check(SpellDecl spell) {
        Map<String, FunctionSignature> declaredFunctions = new LinkedHashMap<>();
        for (FunctionDecl function : spell.functions()) {
            List<ArcType> parameters = function.parameters().stream()
                    .map(name -> parseDeclared(function.parameterTypes().get(name), function)).toList();
            ArcType returns = function.returnType() == null ? ANY : parseDeclared(function.returnType(), function);
            if (declaredFunctions.putIfAbsent(function.name(), new FunctionSignature(parameters, returns, function.effectful())) != null)
                error("ARC-T5020", "関数名が重複しています: " + function.name(), function, "関数名を変更してください");
        }
        functions = Map.copyOf(declaredFunctions);
        scopes.push(new HashMap<>()); defineRuntimeContext();
        for (VarDecl state : spell.states()) variable(state);
        for (HandlerDecl handler : spell.handlers()) {
            scopes.push(new HashMap<>()); defineHandlerContext(handler); currentFunction = null;
            block(handler.body()); scopes.pop();
        }
        for (FunctionDecl function : spell.functions()) {
            scopes.push(new HashMap<>());
            for (String parameter : function.parameters())
                define(parameter, parseDeclared(function.parameterTypes().get(parameter), function), false);
            currentFunction = function; block(function.body());
            ArcType declaredReturn = parseDeclared(function.returnType(), function);
            if (function.returnType() != null && declaredReturn.kind() != Kind.NONE && !alwaysReturns(function.body()))
                error("ARC-T5041", "関数が値を返さずに終了する可能性があります: " + function.name(), function,
                        "すべての実行経路で " + declaredReturn.display() + " をreturnしてください");
            currentFunction = null; scopes.pop();
        }
        scopes.pop(); functions = Map.of();
    }

    private void defineRuntimeContext() {
        define("caster", ENTITY, false); define("player", ENTITY, false); define("target", ENTITY, false);
        define("target_location", LOCATION, false); define("location", LOCATION, false); define("world", WORLD, false);
        define("trigger_type", STRING, false); define("circuit_id", STRING, false); define("node_id", STRING, false);
        define("execution_id", STRING, false); define("mana_available", MANA, false);
        define("entity_in", ENTITY, false); define("location_in", LOCATION, false);
        define("number_in", FLOAT, false); define("boolean_in", BOOL, false);
    }

    private void defineHandlerContext(HandlerDecl handler) {
        if (handler.argument() instanceof Variable variable && lookup(variable.name()).isEmpty())
            define(variable.name(), handlerArgumentType(handler.trigger()), false);
        else if (handler.argument() != null) infer(handler.argument());
    }

    private ArcType handlerArgumentType(Trigger trigger) {
        return switch (trigger) {
            case PULSE -> ArcType.of(Kind.DURATION);
            case MANA_BELOW -> MANA;
            case SIGNAL, SPELL_END -> STRING;
            default -> ENTITY;
        };
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
                if (iterable.kind() != Kind.LIST && iterable.kind() != Kind.ANY)
                    error("ARC-T5001", "for の対象はListである必要があります", f.iterable(), "Listを返すセンサーAPIを使用してください");
                scopes.push(new HashMap<>()); define(f.variable(), iterable.element() == null ? ANY : iterable.element(), false);
                block(f.body()); scopes.pop();
            }
            case RepeatStmt r -> {
                ArcType count = infer(r.count());
                if (count.kind() != Kind.INT && count.kind() != Kind.ANY)
                    error("ARC-T5002", "repeat回数はIntである必要があります", r.count(), "整数を指定してください");
                block(r.body());
            }
            case DeferStmt d -> block(d.body());
            case ReturnStmt r -> validateReturn(r);
            case BreakStmt ignored -> { }
            case ContinueStmt ignored -> { }
        }
    }

    private void validateReturn(ReturnStmt statement) {
        ArcType actual = statement.value() == null ? NONE : infer(statement.value());
        if (currentFunction == null || currentFunction.returnType() == null) return;
        ArcType expected = parseDeclared(currentFunction.returnType(), currentFunction);
        if (!compatible(expected, actual)) error("ARC-T5021",
                "戻り値は " + expected.display() + " ですが " + actual.display() + " が返されています",
                statement, "関数の戻り値型またはreturn式を修正してください");
    }

    private void variable(VarDecl variable) {
        ArcType inferred = infer(variable.initializer());
        ArcType declared = variable.declaredType() == null ? inferred : parseDeclared(variable.declaredType(), variable);
        if (!compatible(declared, inferred)) error("ARC-T5022",
                "変数 " + variable.name() + " の宣言型 " + declared.display() + " と初期値 " + inferred.display() + " が一致しません",
                variable, "型注釈か初期値を修正してください");
        if (scopes.peek().containsKey(variable.name()))
            error("ARC-T5003", "同じスコープで変数が重複しています: " + variable.name(), variable, "別の名前を使用してください");
        define(variable.name(), declared, variable.mutable());
    }

    private ArcType infer(Expr expression) {
        return switch (expression) {
            case Literal l -> literal(l.value());
            case Variable v -> lookup(v.name()).map(Symbol::type).orElseGet(() -> {
                if (!API_NAMESPACES.contains(v.name()))
                    error("ARC-T5023", "未定義の変数です: " + v.name(), v, "宣言するか、利用可能な実行コンテキスト名を使用してください");
                return ANY;
            });
            case ListExpr l -> list(l);
            case Unary u -> unary(u);
            case Binary b -> binary(b);
            case Assignment a -> assignment(a);
            case Member m -> member(m);
            case Call c -> call(c);
            case MatchExpr m -> match(m);
            case VariantExpr v -> variant(v);
            case TryExpr t -> tryExpression(t);
            case BlockExpr b -> { block(b.body()); yield NONE; }
            case ParallelExpr p -> {
                for (Stmt branch : p.branches()) { scopes.push(new HashMap<>()); statement(branch); scopes.pop(); }
                yield p.mode() == ParallelMode.ALL ? ArcType.list(ArcType.result(ANY, STRING)) : ArcType.result(ANY, STRING);
            }
        };
    }

    private ArcType list(ListExpr list) {
        ArcType element = list.values().isEmpty() ? ANY : infer(list.values().getFirst());
        for (int i = 1; i < list.values().size(); i++) {
            ArcType candidate = infer(list.values().get(i));
            if (!compatible(element, candidate)) error("ARC-T5004", "List内の型が一致しません", list.values().get(i), "同じ型の値だけを格納してください");
        }
        return ArcType.list(element);
    }

    private ArcType unary(Unary unary) {
        ArcType operand = infer(unary.operand());
        if (unary.operator() == Type.BANG) { requireBoolean(operand, unary.operand()); return BOOL; }
        if (!operand.numeric() && operand.kind() != Kind.ANY)
            error("ARC-T5005", "単項マイナスには数値が必要です", unary, "数値式へ変更してください");
        return operand;
    }

    private ArcType assignment(Assignment assignment) {
        ArcType value = infer(assignment.value());
        if (!(assignment.target() instanceof Variable variable)) {
            error("ARC-T5024", "代入先は変数である必要があります", assignment, "値をlet mutまたはstateへ格納してください"); return value;
        }
        Optional<Symbol> target = lookup(variable.name());
        if (target.isEmpty()) { error("ARC-T5023", "未定義の変数です: " + variable.name(), variable, "先に変数を宣言してください"); return value; }
        if (!target.get().mutable()) error("ARC-T5025", "不変変数へ代入できません: " + variable.name(), assignment, "変更する値は let mut で宣言してください");
        if (!compatible(target.get().type(), value)) error("ARC-T5006", "代入する値の型が一致しません", assignment, target.get().type().display() + " の値を代入してください");
        return value;
    }

    private ArcType variant(VariantExpr variant) {
        ArcType value = variant.value() == null ? ANY : infer(variant.value());
        return switch (variant.tag()) {
            case "None" -> ArcType.option(ANY); case "Some" -> ArcType.option(value);
            case "Ok" -> ArcType.result(value, ANY); case "Err" -> ArcType.result(ANY, value); default -> ANY;
        };
    }

    private ArcType tryExpression(TryExpr expression) {
        ArcType value = infer(expression.value());
        if (value.kind() != Kind.RESULT && value.kind() != Kind.ANY)
            error("ARC-T5015", "? 演算子はResultにだけ使用できます", expression, "Resultを返すAPIまたは関数に使用してください");
        if (currentFunction != null && currentFunction.returnType() != null) {
            ArcType returns = parseDeclared(currentFunction.returnType(), currentFunction);
            if (returns.kind() != Kind.RESULT && returns.kind() != Kind.ANY)
                error("ARC-T5026", "? を使う関数の戻り値はResultである必要があります", expression, "戻り値をResult<T, E>にしてください");
        }
        return value.element() == null ? ANY : value.element();
    }

    private ArcType match(MatchExpr match) {
        ArcType subject = infer(match.subject()), result = null; boolean wildcard = false; Set<String> variants = new HashSet<>();
        for (MatchArm arm : match.arms()) {
            wildcard |= arm.pattern() instanceof WildcardPattern;
            if (arm.pattern() instanceof VariantPattern variant) variants.add(variant.tag());
            if (arm.pattern() instanceof EnumPattern enumeration) variants.add(enumeration.name());
            if (arm.pattern() instanceof LiteralPattern pattern && !compatible(subject, literal(pattern.value())))
                error("ARC-T5012", "match対象とパターンの型が一致しません", match, "同じ型のパターンを使用してください");
            if (arm.pattern() instanceof VariantPattern && subject.kind() != Kind.RESULT && subject.kind() != Kind.OPTION
                    && subject.kind() != Kind.ENUM && subject.kind() != Kind.ANY)
                error("ARC-T5016", "バリアントパターンはOption、Resultまたはenumに必要です", match, "対応するバリアントに使用してください");
            scopes.push(new HashMap<>());
            if (arm.pattern() instanceof VariantPattern variant && variant.binding() != null) {
                ArcType binding = variant.tag().equals("Err") ? subject.error() : subject.kind() == Kind.ENUM
                        ? enumPayload(subject.nominal(), variant.tag()) : subject.element();
                define(variant.binding(), binding == null ? ANY : binding, false);
            }
            ArcType armType = infer(arm.value()); scopes.pop();
            if (result == null) result = armType;
            else if (!compatible(result, armType)) error("ARC-T5013", "match各アームの結果型が一致しません", arm.value(), "全アームで同じ型を返してください");
        }
        boolean exhaustive = subject.kind() == Kind.RESULT && variants.containsAll(Set.of("Ok", "Err"))
                || subject.kind() == Kind.OPTION && variants.containsAll(Set.of("Some", "None"))
                || subject.kind() == Kind.ENUM && enumVariants(subject.nominal()).stream()
                        .allMatch(name -> variants.contains(subject.nominal() + "::" + name) || variants.contains(name));
        if (!wildcard && !exhaustive) warning("ARC-TW5014", "matchが全ケースを処理する保証がありません", match, "最後に _ => ... を追加してください");
        return result == null ? ANY : result;
    }

    private ArcType binary(Binary binary) {
        ArcType left = infer(binary.left()), right = infer(binary.right());
        return switch (binary.operator()) {
            case EQUAL_EQUAL, BANG_EQUAL -> { compare(left, right, binary, "比較"); yield BOOL; }
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> { compare(left, right, binary, "大小比較"); yield BOOL; }
            case AND_AND, OR_OR -> { requireBoolean(left, binary.left()); requireBoolean(right, binary.right()); yield BOOL; }
            case PLUS, MINUS, STAR, SLASH, PERCENT -> arithmetic(binary, left, right);
            default -> ANY;
        };
    }

    private void compare(ArcType left, ArcType right, Node node, String operation) {
        if (!compatible(left, right)) error(operation.equals("比較") ? "ARC-T5007" : "ARC-T5008", operation + "する型が一致しません", node, "同じ型・単位を比較してください");
    }

    private ArcType arithmetic(Binary binary, ArcType left, ArcType right) {
        if (!compatible(left, right) && left.kind() != Kind.ANY && right.kind() != Kind.ANY)
            error("ARC-T5009", "異なる単位・型どうしは演算できません", binary, "同じ単位へ揃えてください");
        if (!(left.numeric() || unit(left) || left.kind() == Kind.ANY))
            error("ARC-T5010", "この型は算術演算できません", binary.left(), "数値または単位付き数値を使用してください");
        if ((binary.operator() == Type.STAR || binary.operator() == Type.SLASH) && unit(left) && right.numeric()) return left;
        return left.kind() == Kind.ANY ? right : left;
    }

    private ArcType member(Member member) {
        String qualified = qualified(member);
        if (qualified != null && enumConstant(qualified) != null) return enumConstant(qualified);
        if (qualified != null) {
            Optional<Api> zeroArg = ArcMagicApiCatalog.find(qualified).filter(api -> api.minArgs() == 0);
            if (zeroArg.isPresent()) return fromCatalog(zeroArg.get().returns());
        }
        ArcType receiver = infer(member.receiver());
        if (receiver.kind() == Kind.STRUCT) {
            StructDecl struct = structs.get(receiver.nominal());
            if (struct != null) for (FieldDecl field : struct.fields()) if (field.name().equals(member.name()))
                return parseDeclared(field.type(), struct);
            error("ARC-T5043", receiver.display() + " にfield " + member.name() + " はありません", member,
                    "struct定義のfield名を確認してください");
            return ANY;
        }
        return switch (member.name()) {
            case "hp_ratio", "damage" -> FLOAT;
            case "x", "y", "z" -> receiver.kind() == Kind.LOCATION || receiver.kind() == Kind.VECTOR ? FLOAT : ANY;
            case "count", "length" -> INT;
            case "is_empty", "is_hostile", "is_attackable", "all_success", "is_some", "is_none", "is_ok", "is_err" -> BOOL;
            case "value" -> receiver.kind() == Kind.OPTION || receiver.kind() == Kind.RESULT
                    ? (receiver.element() == null ? ANY : receiver.element()) : ANY;
            case "error" -> receiver.kind() == Kind.RESULT ? (receiver.error() == null ? ANY : receiver.error()) : ANY;
            case "location" -> receiver.kind() == Kind.ENTITY || receiver.kind() == Kind.BLOCK ? LOCATION : ANY;
            case "world" -> receiver.kind() == Kind.LOCATION || receiver.kind() == Kind.ENTITY ? WORLD : ANY;
            default -> ANY;
        };
    }

    private ArcType call(Call call) {
        String name = qualified(call.callee());
        if (name == null) { call.arguments().forEach(argument -> infer(argument.value())); return ANY; }
        if (Set.of("Some", "Ok", "Err").contains(name)) return constructorCall(name, call);
        if (structs.containsKey(name)) return structCall(call, structs.get(name));
        ArcType enumeration = enumConstant(name);
        if (enumeration != null) return enumCall(call, name, enumeration);
        Api api = ArcMagicApiCatalog.find(name).orElse(null);
        if (api != null) return apiCall(call, api);
        FunctionSignature function = functions.get(name);
        if (function != null) return functionCall(call, name, function);
        call.arguments().forEach(argument -> infer(argument.value()));
        return ANY;
    }

    private ArcType constructorCall(String name, Call call) {
        ArcType value = call.arguments().isEmpty() ? ANY : infer(call.arguments().getFirst().value());
        for (int i = 1; i < call.arguments().size(); i++) infer(call.arguments().get(i).value());
        return switch (name) { case "Some" -> ArcType.option(value); case "Ok" -> ArcType.result(value, ANY); default -> ArcType.result(ANY, value); };
    }

    private ArcType structCall(Call call, StructDecl struct) {
        if (call.arguments().size() != struct.fields().size())
            error("ARC-T5044", struct.name() + " は " + struct.fields().size() + " fieldを必要とします", call,
                    struct.name() + "(" + String.join(", ", struct.fields().stream().map(f -> f.name() + ": " + f.type()).toList()) + ")");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < call.arguments().size(); i++) {
            CallArg argument = call.arguments().get(i); FieldDecl field = null;
            if (argument.name() != null) {
                if (!seen.add(argument.name())) error("ARC-T5045", "struct fieldが重複しています: " + argument.name(), call, "fieldを1回だけ指定してください");
                field = struct.fields().stream().filter(value -> value.name().equals(argument.name())).findFirst().orElse(null);
            } else if (i < struct.fields().size()) field = struct.fields().get(i);
            ArcType actual = infer(argument.value());
            if (field == null) error("ARC-T5046", "未知のstruct fieldです: " + argument.name(), call, "struct定義を確認してください");
            else if (!compatible(parseDeclared(field.type(), struct), actual))
                error("ARC-T5047", struct.name() + "." + field.name() + " の型が一致しません", argument.value(), field.type() + " を指定してください");
        }
        return ArcType.nominal(Kind.STRUCT, struct.name());
    }

    private ArcType enumCall(Call call, String qualified, ArcType enumeration) {
        String variantName = qualified.substring(qualified.lastIndexOf('.') + 1);
        EnumVariantDecl variant = enumVariant(enumeration.nominal(), variantName);
        ArcType payload = variant == null || variant.payloadType() == null ? null : parseDeclared(variant.payloadType(), enums.get(enumeration.nominal()));
        int expected = payload == null ? 0 : 1;
        if (call.arguments().size() != expected) error("ARC-T5048", qualified + " は引数を " + expected + " 個必要とします", call, "enum定義を確認してください");
        if (!call.arguments().isEmpty()) {
            ArcType actual = infer(call.arguments().getFirst().value());
            if (payload != null && !compatible(payload, actual)) error("ARC-T5049", qualified + " のpayload型が一致しません", call.arguments().getFirst().value(), payload.display() + " を指定してください");
        }
        return enumeration;
    }

    private ArcType apiCall(Call call, Api api) {
        if (call.arguments().size() < api.minArgs() || call.arguments().size() > api.maxArgs())
            error("ARC-T5027", api.name() + " の引数数が不正です（" + api.minArgs() + "〜" + api.maxArgs() + "個）", call, api.signature());
        Set<String> usedNames = new HashSet<>(); int positional = 0; boolean namedSeen = false;
        for (CallArg argument : call.arguments()) {
            ArcMagicApiCatalog.Parameter parameter = null;
            if (argument.name() != null) {
                namedSeen = true;
                if (!usedNames.add(argument.name())) error("ARC-T5028", "名前付き引数が重複しています: " + argument.name(), call, api.signature());
                parameter = api.parameter(argument.name()).orElse(null);
                if (parameter == null) error("ARC-T5029", "未知の名前付き引数です: " + argument.name(), call, api.signature());
            } else {
                if (namedSeen) error("ARC-T5030", "名前付き引数の後に位置引数は置けません", call, api.signature());
                if (positional < api.parameters().size()) parameter = api.parameters().get(positional);
                positional++;
            }
            ArcType actual = infer(argument.value());
            if (parameter != null) validateArgument(api, parameter, actual, argument.value());
        }
        for (ArcMagicApiCatalog.Parameter parameter : api.parameters())
            if (!parameter.optional() && call.arguments().stream().anyMatch(arg -> arg.name() != null)
                    && !usedNames.contains(parameter.name()) && api.parameters().indexOf(parameter) >= positional)
                error("ARC-T5031", "必須引数がありません: " + parameter.name(), call, api.signature());
        return fromCatalog(api.returns());
    }

    private void validateArgument(Api api, ArcMagicApiCatalog.Parameter parameter, ArcType actual, Node node) {
        ArcType expected = fromCatalog(parameter.type());
        if (compatible(expected, actual)) return;
        if (unit(expected) && actual.numeric()) {
            warning("ARC-TW5032", api.name() + " の " + parameter.name() + " には " + expected.display() + " 単位を付けてください", node, "例: 24b, 5s, 100mp, 35%"); return;
        }
        error("ARC-T5033", api.name() + " の " + parameter.name() + " は " + expected.display() + " ですが " + actual.display() + " が指定されています", node, api.signature());
    }

    private ArcType functionCall(Call call, String name, FunctionSignature function) {
        if (call.arguments().stream().anyMatch(argument -> argument.name() != null))
            error("ARC-T5036", "自作関数では名前付き引数を使用できません: " + name, call, "位置引数を使用してください");
        if (call.arguments().size() != function.parameters().size())
            error("ARC-T5037", name + " は " + function.parameters().size() + " 個の引数を必要とします", call, "引数数を修正してください");
        for (int i = 0; i < call.arguments().size(); i++) {
            ArcType actual = infer(call.arguments().get(i).value());
            if (i < function.parameters().size() && !compatible(function.parameters().get(i), actual))
                error("ARC-T5038", name + " の引数" + (i + 1) + "は " + function.parameters().get(i).display()
                        + " ですが " + actual.display() + " が指定されています", call.arguments().get(i).value(), "引数型を修正してください");
        }
        if (currentFunction != null && !currentFunction.effectful() && function.effectful())
            error("ARC-T5039", "pure fnからeffect fnは呼び出せません: " + name, call, "呼出元をeffect fnへ変更してください");
        return function.returns();
    }

    private ArcType parseDeclared(String source, Node node) {
        if (source == null || source.isBlank()) return ANY;
        String trimmed = source.replace(" ", "");
        if (structs.containsKey(trimmed)) return ArcType.nominal(Kind.STRUCT, trimmed);
        if (enums.containsKey(trimmed)) return ArcType.nominal(Kind.ENUM, trimmed);
        int open = trimmed.indexOf('<');
        if (open > 0 && trimmed.endsWith(">")) {
            String raw = trimmed.substring(0, open).toLowerCase(Locale.ROOT);
            List<String> args = splitTypeArguments(trimmed.substring(open + 1, trimmed.length() - 1));
            if (raw.equals("list") && args.size() == 1) return ArcType.list(parseDeclared(args.getFirst(), node));
            if (raw.equals("option") && args.size() == 1) return ArcType.option(parseDeclared(args.getFirst(), node));
            if (raw.equals("result") && args.size() == 2) return ArcType.result(parseDeclared(args.get(0), node), parseDeclared(args.get(1), node));
        }
        ValueType parsed = ArcMagicApiCatalog.parseType(source);
        if (parsed.kind() == ArcMagicApiCatalog.Kind.ANY && !source.equalsIgnoreCase("Any"))
            error("ARC-T5040", "未知の型です: " + source, node, "ArcSysの組み込み型を使用してください");
        return fromCatalog(parsed);
    }

    private List<String> splitTypeArguments(String source) {
        List<String> result = new ArrayList<>(); int depth = 0, start = 0;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (value == '<') depth++; else if (value == '>') depth--;
            else if (value == ',' && depth == 0) { result.add(source.substring(start, i)); start = i + 1; }
        }
        result.add(source.substring(start)); return result;
    }

    private ArcType enumConstant(String qualified) {
        int split = qualified.lastIndexOf('.'); if (split < 1) return null;
        String type = qualified.substring(0, split), variant = qualified.substring(split + 1);
        EnumDecl declaration = enums.get(type);
        if (declaration == null || declaration.variants().stream().noneMatch(value -> value.name().equals(variant))) return null;
        return ArcType.nominal(Kind.ENUM, type);
    }

    private ArcType enumPayload(String type, String qualifiedVariant) {
        String name = qualifiedVariant.contains("::") ? qualifiedVariant.substring(qualifiedVariant.lastIndexOf("::") + 2)
                : qualifiedVariant.contains(".") ? qualifiedVariant.substring(qualifiedVariant.lastIndexOf('.') + 1) : qualifiedVariant;
        EnumVariantDecl variant = enumVariant(type, name); EnumDecl declaration = enums.get(type);
        return variant == null || variant.payloadType() == null ? ANY : parseDeclared(variant.payloadType(), declaration);
    }

    private EnumVariantDecl enumVariant(String type, String name) {
        EnumDecl declaration = enums.get(type); if (declaration == null) return null;
        return declaration.variants().stream().filter(value -> value.name().equals(name)).findFirst().orElse(null);
    }

    private List<String> enumVariants(String type) {
        EnumDecl declaration = enums.get(type); return declaration == null ? List.of() : declaration.variants().stream().map(EnumVariantDecl::name).toList();
    }

    private boolean alwaysReturns(Block block) {
        if (block.statements().isEmpty()) return false;
        for (Stmt statement : block.statements()) {
            if (statement instanceof ReturnStmt) return true;
            if (statement instanceof Block nested && alwaysReturns(nested)) return true;
            if (statement instanceof IfStmt branch && branch.elseBranch() != null
                    && alwaysReturns(branch.thenBranch()) && alwaysReturns(branch.elseBranch())) return true;
        }
        return false;
    }

    private ArcType fromCatalog(ValueType type) {
        Kind kind;
        try { kind = Kind.valueOf(type.kind().name()); } catch (IllegalArgumentException ignored) { return ANY; }
        return switch (kind) {
            case LIST -> ArcType.list(fromCatalog(type.argument(0)));
            case OPTION -> ArcType.option(fromCatalog(type.argument(0)));
            case RESULT -> ArcType.result(fromCatalog(type.argument(0)), fromCatalog(type.argument(1)));
            default -> ArcType.of(kind);
        };
    }

    private String qualified(Expr expression) {
        if (expression instanceof Variable variable) return variable.name();
        if (expression instanceof Member member) { String parent = qualified(member.receiver()); return parent == null ? null : parent + "." + member.name(); }
        return null;
    }

    private ArcType literal(Object value) {
        if (value == null) return NONE; if (value instanceof Boolean) return BOOL; if (value instanceof Long) return INT;
        if (value instanceof Double) return FLOAT; if (value instanceof String) return STRING;
        if (value instanceof UnitValue unit) return ArcType.of(switch (unit.unit()) {
            case TICKS, DAYS -> Kind.DURATION; case BLOCKS -> Kind.DISTANCE; case MANA -> Kind.MANA; case PERCENT -> Kind.PERCENT;
        });
        return ANY;
    }

    private void requireBoolean(ArcType type, Node node) {
        if (type.kind() != Kind.BOOL && type.kind() != Kind.ANY)
            error("ARC-T5011", "条件式はBoolである必要があります", node, "Boolを返す比較またはAPIを使用してください");
    }
    private boolean compatible(ArcType expected, ArcType actual) {
        if (expected == null || actual == null || expected.kind() == Kind.ANY || actual.kind() == Kind.ANY) return true;
        if (expected.kind() == Kind.NUMBER && actual.numeric() || actual.kind() == Kind.NUMBER && expected.numeric()) return true;
        if (expected.numeric() && actual.numeric()) return true;
        if (expected.kind() != actual.kind()) return false;
        if (expected.kind() == Kind.STRUCT || expected.kind() == Kind.ENUM) return Objects.equals(expected.nominal(), actual.nominal());
        if (expected.kind() == Kind.RESULT) return compatible(expected.element(), actual.element()) && compatible(expected.error(), actual.error());
        if (expected.kind() == Kind.LIST || expected.kind() == Kind.OPTION) return compatible(expected.element(), actual.element());
        return true;
    }
    private boolean unit(ArcType type) { return type.kind() == Kind.DURATION || type.kind() == Kind.DISTANCE || type.kind() == Kind.MANA || type.kind() == Kind.PERCENT; }
    private Optional<Symbol> lookup(String name) { for (Map<String, Symbol> scope : scopes) if (scope.containsKey(name)) return Optional.of(scope.get(name)); return Optional.empty(); }
    private void define(String name, ArcType type, boolean mutable) { scopes.peek().put(name, new Symbol(type, mutable)); }
    private Set<String> normalize(Set<String> values) { return values.stream().map(value -> value.toLowerCase(Locale.ROOT).replace('_', '.')).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    private void error(String code, String message, Node node, String suggestion) { diagnostics.add(new Diagnostic(code, Severity.ERROR, message, node.span(), suggestion)); }
    private void warning(String code, String message, Node node, String suggestion) { diagnostics.add(new Diagnostic(code, Severity.WARNING, message, node.span(), suggestion)); }
}
