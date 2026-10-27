package net.monacraft.spellforge.arcsys.language;

import net.monacraft.spellforge.arcsys.language.ArcAst.*;
import net.monacraft.spellforge.arcsys.language.SourceModel.*;
import net.monacraft.spellforge.arcsys.language.Token.Type;

import java.util.*;

import static net.monacraft.spellforge.arcsys.language.Token.Type.*;

/** A recovering recursive-descent parser for the bounded ArcSys source language. */
public final class ArcParser {
    public static final int MAX_PARSE_DEPTH = 96;
    private final List<Token> tokens;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int current;
    private int depth;

    public ArcParser(List<Token> tokens) {
        this.tokens = tokens == null || tokens.isEmpty() ? List.of(eofToken()) : List.copyOf(tokens);
    }

    public static ParseResult parse(String source) {
        ArcLexer.Result lexed = new ArcLexer(source).scan();
        ArcParser parser = new ArcParser(lexed.tokens());
        ParseResult parsed = parser.parseProgram();
        List<Diagnostic> all = new ArrayList<>(lexed.diagnostics());
        all.addAll(parsed.diagnostics());
        return new ParseResult(parsed.program(), all);
    }

    public ParseResult parseProgram() {
        List<SpellDecl> spells = new ArrayList<>();
        List<FunctionDecl> globalFunctions = new ArrayList<>();
        List<VarDecl> globalConstants = new ArrayList<>();
        Token first = peek();
        while (!atEnd()) {
            try {
                while (match(AT_SIGN)) skipAnnotation();
                if (match(MODULE, USE)) skipUntilSemicolon();
                else if (match(CONST)) globalConstants.add(variable(previous()));
                else if (match(PUB, PRIVATE, EFFECT)) {
                    if (match(FN)) globalFunctions.add(function(previous()));
                    else skipTopLevelDeclaration();
                }
                else if (match(FN)) globalFunctions.add(function(previous()));
                else if (check(SPELL)) spells.add(spell());
                else if (match(STRUCT, ENUM, TYPE, TRAIT, IMPL)) skipTopLevelDeclaration();
                else throw error(peek(), "ARC-E1000", "トップレベル宣言が不正です", "module, use, const, fn, struct, enum, type, spell を使用してください");
            } catch (ParseFailure ignored) {
                synchronizeTopLevel();
            }
        }
        if (!globalFunctions.isEmpty() || !globalConstants.isEmpty()) {
            spells = spells.stream().map(spell -> new SpellDecl(spell.name(), spell.requiredFragments(), spell.requiredPermissions(),
                    concat(globalConstants, spell.states()), spell.handlers(), concat(globalFunctions, spell.functions()), spell.span())).toList();
        }
        Span span = spells.isEmpty() ? first.span() : join(first.span(), previous().span());
        return new ParseResult(new Program(spells, span), diagnostics);
    }

    private SpellDecl spell() {
        Token begin = consume(SPELL, "ARC-E1001", "'spell' が必要です");
        Token name;
        if (match(IDENT, STRING)) name = previous();
        else throw error(peek(), "ARC-E1002", "術式名が必要です");
        consume(LEFT_BRACE, "ARC-E1003", "術式本体の '{' が必要です");
        List<HandlerDecl> handlers = new ArrayList<>();
        List<FunctionDecl> functions = new ArrayList<>();
        List<VarDecl> states = new ArrayList<>();
        Map<String, Integer> requiredFragments = new LinkedHashMap<>();
        List<String> requiredPermissions = new ArrayList<>();
        while (!check(RIGHT_BRACE) && !atEnd()) {
            try {
                while (match(AT_SIGN)) skipAnnotation();
                if (match(ON)) handlers.add(handler(previous()));
                else if (match(FN)) functions.add(function(previous()));
                else if (match(STATE)) states.add(variable(previous()));
                else if (match(REQUIRES)) parseRequires(requiredFragments, requiredPermissions);
                else throw error(peek(), "ARC-E1004", "術式直下には on または fn を記述してください", "イベントハンドラか関数へ移動してください");
            } catch (ParseFailure ignored) {
                synchronizeMember();
            }
        }
        Token end = consume(RIGHT_BRACE, "ARC-E1005", "術式本体を閉じる '}' が必要です");
        return new SpellDecl(name.literal() instanceof String value ? value : name.lexeme(), requiredFragments, requiredPermissions,
                states, handlers, functions, join(begin.span(), end.span()));
    }

    private HandlerDecl handler(Token begin) {
        Trigger trigger;
        if (match(CAST)) trigger = Trigger.CAST;
        else if (match(PULSE)) trigger = Trigger.PULSE;
        else if (match(HIT)) trigger = Trigger.HIT;
        else if (match(KILL)) trigger = Trigger.KILL;
        else if (match(DAMAGE_TAKEN)) trigger = Trigger.DAMAGE_TAKEN;
        else if (match(DAMAGE)) trigger = Trigger.DAMAGE;
        else if (match(DEATH)) trigger = Trigger.DEATH;
        else if (match(ENTER)) trigger = Trigger.ENTER;
        else if (match(MANA_BELOW)) trigger = Trigger.MANA_BELOW;
        else if (match(SPELL_END)) trigger = Trigger.SPELL_END;
        else if (match(SIGNAL)) trigger = Trigger.SIGNAL;
        else if (match(PROJECTILE_IMPACT)) trigger = Trigger.PROJECTILE_IMPACT;
        else throw error(peek(), "ARC-E1010", "未知のイベントです", "cast, pulse, hit, kill, damage_taken のいずれかを使用してください");
        Expr argument = null;
        if (match(LEFT_PAREN)) {
            if (!check(RIGHT_PAREN)) {
                List<Expr> arguments = new ArrayList<>();
                do { arguments.add(expression()); } while (match(COMMA));
                argument = arguments.size() == 1 ? arguments.getFirst() : new ListExpr(arguments, join(arguments.getFirst().span(), arguments.getLast().span()));
            }
            consume(RIGHT_PAREN, "ARC-E1011", "イベント引数の ')' が必要です");
        }
        Block body = block();
        return new HandlerDecl(trigger, argument, body, join(begin.span(), body.span()));
    }

    private FunctionDecl function(Token begin) {
        Token name = consume(IDENT, "ARC-E1020", "関数名が必要です");
        consume(LEFT_PAREN, "ARC-E1021", "引数リストの '(' が必要です");
        List<String> parameters = new ArrayList<>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (parameters.size() >= 16) throw error(peek(), "ARC-E1022", "関数引数は16個までです", "関数を分割してください");
                parameters.add(consume(IDENT, "ARC-E1023", "引数名が必要です").lexeme());
                if (match(COLON)) skipTypeReference();
            } while (match(COMMA));
        }
        consume(RIGHT_PAREN, "ARC-E1024", "引数リストの ')' が必要です");
        if (match(ARROW)) skipTypeReferenceBeforeBlock();
        Block body = block();
        return new FunctionDecl(name.lexeme(), parameters, body, join(begin.span(), body.span()));
    }

    private Block block() {
        Token begin = consume(LEFT_BRACE, "ARC-E1030", "ブロックの '{' が必要です");
        enter(begin);
        List<Stmt> statements = new ArrayList<>();
        try {
            while (!check(RIGHT_BRACE) && !atEnd()) {
                try { statements.add(statement()); }
                catch (ParseFailure ignored) { synchronizeStatement(); }
            }
            Token end = consume(RIGHT_BRACE, "ARC-E1031", "ブロックを閉じる '}' が必要です");
            return new Block(statements, join(begin.span(), end.span()));
        } finally { depth--; }
    }

    private Stmt statement() {
        if (match(LET, CONST, STATE)) return variable(previous());
        if (match(IF)) return ifStatement(previous());
        if (match(FOR)) return forStatement(previous());
        if (match(REPEAT)) return repeatStatement(previous());
        if (match(LOOP, FOREACH)) return forStatement(previous());
        if (match(GUARD)) return guardStatement(previous());
        if (match(CHANCE)) return chanceStatement(previous());
        if (match(EVERY)) return everyStatement(previous());
        if (match(AFTER)) return afterStatement(previous());
        if (match(DEFER)) return deferStatement(previous());
        if (match(CAST)) return castStatement(previous());
        if (match(APPLY)) return applyStatement(previous());
        if (match(EMIT)) { Token begin = previous(); match(SIGNAL); return hostStatement(begin, "event.emit"); }
        if (match(WAIT)) return hostStatement(previous(), "time.wait");
        if (match(ATOMIC, TRANSACTION, VISUAL, COMBAT)) return block();
        if (match(RETURN)) return returnStatement(previous());
        if (match(BREAK)) { Token t = previous(); finishStatement(); return new BreakStmt(t.span()); }
        if (match(CONTINUE)) { Token t = previous(); finishStatement(); return new ContinueStmt(t.span()); }
        if (check(LEFT_BRACE)) return block();
        Expr expr = expression();
        Token end = finishStatement();
        return new ExprStmt(expr, join(expr.span(), end.span()));
    }

    private VarDecl variable(Token begin) {
        if (begin.type() == LET) match(MUT);
        Token name = consume(IDENT, "ARC-E1040", "変数名が必要です");
        if (match(COLON)) skipTypeReference();
        consume(EQUAL, "ARC-E1041", "変数の初期値が必要です");
        Expr initializer = expression();
        Persistence persistence = null;
        if (match(PERSIST)) persistence = persistence(previous(), begin.type());
        Token end = finishStatement();
        Binding binding = switch (begin.type()) { case LET -> Binding.LET; case CONST -> Binding.CONST; default -> Binding.STATE; };
        return new VarDecl(binding, name.lexeme(), initializer, persistence, join(begin.span(), end.span()));
    }

    private Persistence persistence(Token begin, Type declarationType) {
        if (declarationType != STATE) throw error(begin, "ARC-E1042", "persistはstateにだけ指定できます");
        consume(LEFT_PAREN, "ARC-E1043", "persistの '(' が必要です");
        Token scope = consumeAny("ARC-E1044", "persist範囲はplayer、spell、sessionのいずれかです", IDENT, SPELL);
        PersistenceScope persistenceScope = switch (scope.lexeme().toLowerCase(Locale.ROOT)) {
            case "player" -> PersistenceScope.PLAYER;
            case "spell" -> PersistenceScope.SPELL;
            case "session" -> PersistenceScope.SESSION;
            default -> throw error(scope, "ARC-E1044", "persist範囲はplayer、spell、sessionのいずれかです");
        };
        long ttl = 0;
        if (match(COMMA)) {
            Token ttlName = consumeName("ARC-E1045", "ttl指定が必要です");
            if (!ttlName.lexeme().equalsIgnoreCase("ttl")) throw error(ttlName, "ARC-E1045", "persistで指定できるのはttlだけです");
            consume(COLON, "ARC-E1046", "ttlの ':' が必要です");
            Token duration = consume(DURATION, "ARC-E1047", "ttlはDurationで指定してください");
            ttl = ((UnitValue) duration.literal()).ticks();
            if (ttl < 1 || ttl > 6_307_200_000L) throw error(duration, "ARC-E1048", "ttlは1tick〜10年の範囲で指定してください");
        }
        consume(RIGHT_PAREN, "ARC-E1049", "persistの ')' が必要です");
        return new Persistence(persistenceScope, ttl);
    }

    private IfStmt ifStatement(Token begin) {
        consume(LEFT_PAREN, "ARC-E1050", "if 条件の '(' が必要です");
        Expr condition = expression();
        consume(RIGHT_PAREN, "ARC-E1051", "if 条件の ')' が必要です");
        Block thenBranch = block();
        Block elseBranch = match(ELSE) ? block() : null;
        return new IfStmt(condition, thenBranch, elseBranch, join(begin.span(), elseBranch == null ? thenBranch.span() : elseBranch.span()));
    }

    private ForStmt forStatement(Token begin) {
        Token variable = consume(IDENT, "ARC-E1060", "for の変数名が必要です");
        consume(IN, "ARC-E1061", "for には in が必要です");
        Expr iterable = expression();
        Block body = block();
        return new ForStmt(variable.lexeme(), iterable, body, join(begin.span(), body.span()));
    }

    private RepeatStmt repeatStatement(Token begin) {
        boolean parenthesized = match(LEFT_PAREN);
        Expr count = expression();
        if (parenthesized) consume(RIGHT_PAREN, "ARC-E1071", "repeat 回数の ')' が必要です");
        Block body = block();
        return new RepeatStmt(count, body, join(begin.span(), body.span()));
    }

    private Stmt guardStatement(Token begin) {
        Expr condition = expression();
        consume(ELSE, "ARC-E1072", "guard には else が必要です");
        Block failure = block();
        Expr inverted = new Unary(BANG, condition, condition.span());
        return new IfStmt(inverted, failure, null, join(begin.span(), failure.span()));
    }

    private Stmt chanceStatement(Token begin) {
        Expr probability = expression();
        Block body = block();
        Expr callee = qualifiedVariable("random", "chance", begin.span());
        Call call = new Call(callee, List.of(new CallArg(null, probability, probability.span())), join(begin.span(), probability.span()));
        return new IfStmt(call, body, null, join(begin.span(), body.span()));
    }

    private Stmt hostStatement(Token begin, String api) {
        Expr value = expression();
        Token end = finishStatement();
        String[] parts = api.split("\\.");
        Expr callee = qualifiedVariable(parts[0], parts[1], begin.span());
        Call call = new Call(callee, List.of(new CallArg(null, value, value.span())), join(begin.span(), value.span()));
        return new ExprStmt(call, join(begin.span(), end.span()));
    }

    private Stmt castStatement(Token begin) {
        Token spellName = consumeAny("ARC-E1073", "castする術式名が必要です", STRING, IDENT);
        consume(AT, "ARC-E1074", "castには at <target> が必要です");
        Expr target = expression(); Token end = finishStatement();
        Expr name = new Literal(spellName.literal() instanceof String value ? value : spellName.lexeme(), spellName.span());
        Call call = hostCall("spell", "cast", begin.span(), List.of(name, target));
        return new ExprStmt(call, join(begin.span(), end.span()));
    }

    private Stmt applyStatement(Token begin) {
        Token effect = consumeAny("ARC-E1075", "applyする効果名が必要です", STRING, IDENT);
        consume(TO, "ARC-E1076", "applyには to <target> が必要です");
        Expr target = expression();
        consume(FOR, "ARC-E1077", "applyには for <duration> が必要です");
        Expr duration = expression(); Token end = finishStatement();
        Expr effectName = new Literal(effect.literal() instanceof String value ? value : effect.lexeme(), effect.span());
        Call call = hostCall("combat", "apply", begin.span(), List.of(effectName, target, duration));
        return new ExprStmt(call, join(begin.span(), end.span()));
    }

    private Stmt everyStatement(Token begin) {
        Expr interval = expression();
        consume(FOR, "ARC-E1078", "everyには for <duration> が必要です");
        Expr duration = expression();
        Block original = block();
        long count = boundedPeriodCount(interval, duration);
        Call waitCall = hostCall("time", "wait", begin.span(), List.of(interval));
        List<Stmt> statements = new ArrayList<>(original.statements());
        statements.add(new ExprStmt(waitCall, waitCall.span()));
        Block body = new Block(statements, original.span());
        return new RepeatStmt(new Literal(count, duration.span()), body, join(begin.span(), original.span()));
    }

    private Stmt afterStatement(Token begin) {
        Expr delay = expression(); Block original = block();
        Call waitCall = hostCall("time", "wait", begin.span(), List.of(delay));
        List<Stmt> statements = new ArrayList<>(); statements.add(new ExprStmt(waitCall, waitCall.span())); statements.addAll(original.statements());
        return new Block(statements, join(begin.span(), original.span()));
    }

    private Stmt deferStatement(Token begin) {
        Block body = block();
        return new DeferStmt(body, join(begin.span(), body.span()));
    }

    private long boundedPeriodCount(Expr interval, Expr duration) {
        if (!(interval instanceof Literal i) || !(duration instanceof Literal d)
                || !(i.value() instanceof UnitValue iu) || !(d.value() instanceof UnitValue du)
                || (iu.unit() != Unit.TICKS && iu.unit() != Unit.DAYS)
                || (du.unit() != Unit.TICKS && du.unit() != Unit.DAYS) || iu.ticks() <= 0)
            throw error(peek(), "ARC-E1079", "everyの間隔と期間は固定Durationで指定してください");
        long count = du.ticks() / iu.ticks();
        if (count < 1 || count > 256) throw error(peek(), "ARC-E1081", "everyの実行回数は1〜256回です");
        return count;
    }

    private Call hostCall(String namespace, String member, Span begin, List<Expr> arguments) {
        Expr callee = qualifiedVariable(namespace, member, begin);
        List<CallArg> args = arguments.stream().map(value -> new CallArg(null, value, value.span())).toList();
        return new Call(callee, args, join(begin, arguments.getLast().span()));
    }

    private Expr qualifiedVariable(String namespace, String member, Span span) {
        Variable root = new Variable(namespace, span);
        return new Member(root, member, span);
    }

    private void skipTypeReference() {
        int genericDepth = 0;
        while (!atEnd()) {
            if (check(LESS)) { genericDepth++; advance(); continue; }
            if (check(GREATER) && genericDepth > 0) { genericDepth--; advance(); continue; }
            if (genericDepth == 0 && (check(EQUAL) || check(COMMA) || check(RIGHT_PAREN))) return;
            advance();
        }
    }

    private void skipTypeReferenceBeforeBlock() {
        int genericDepth = 0;
        while (!atEnd()) {
            if (check(LESS)) { genericDepth++; advance(); continue; }
            if (check(GREATER) && genericDepth > 0) { genericDepth--; advance(); continue; }
            if (genericDepth == 0 && check(LEFT_BRACE)) return;
            advance();
        }
    }

    private void skipAnnotation() {
        if (check(IDENT)) advance();
        skipBalancedCall();
    }

    private void skipUntilSemicolon() {
        while (!atEnd() && !match(SEMICOLON)) advance();
    }

    private void skipTopLevelDeclaration() {
        int braces = 0;
        while (!atEnd()) {
            if (match(LEFT_BRACE)) braces++;
            else if (match(RIGHT_BRACE)) { if (--braces <= 0) return; }
            else if (braces == 0 && match(SEMICOLON)) return;
            else advance();
        }
    }

    private void parseRequires(Map<String, Integer> fragments, List<String> permissions) {
        if (match(FRAGMENTS)) {
            consume(LEFT_BRACE, "ARC-E1150", "requires fragments の '{' が必要です");
            while (!check(RIGHT_BRACE) && !atEnd()) {
                Token id = consume(IDENT, "ARC-E1151", "欠片IDが必要です");
                consume(COLON, "ARC-E1152", "欠片の最低Lvが必要です");
                Token level = consume(INT, "ARC-E1153", "欠片Lvは整数で指定してください");
                int value = Math.toIntExact((Long) level.literal());
                if (value < 1 || value > 255) throw error(level, "ARC-E1154", "欠片Lvは1〜255です");
                fragments.merge(id.lexeme(), value, Math::max);
                match(COMMA, SEMICOLON);
            }
            consume(RIGHT_BRACE, "ARC-E1155", "requires fragments の '}' が必要です");
            return;
        }
        if (match(PERMISSION)) {
            Token permission = consumeAny("ARC-E1156", "権限名が必要です", STRING, IDENT);
            permissions.add(permission.literal() instanceof String value ? value : permission.lexeme());
            match(SEMICOLON);
            return;
        }
        throw error(peek(), "ARC-E1157", "requiresにはfragmentsまたはpermissionが必要です");
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>(first.size() + second.size()); result.addAll(first); result.addAll(second); return result;
    }

    private void skipBalancedCall() {
        if (!match(LEFT_PAREN)) return;
        int nesting = 1;
        while (!atEnd() && nesting > 0) {
            if (match(LEFT_PAREN)) nesting++;
            else if (match(RIGHT_PAREN)) nesting--;
            else advance();
        }
    }

    private ReturnStmt returnStatement(Token begin) {
        Expr value = check(SEMICOLON) || check(RIGHT_BRACE) ? null : expression();
        Token end = finishStatement();
        return new ReturnStmt(value, join(begin.span(), end.span()));
    }

    private Token finishStatement() {
        if (match(SEMICOLON)) return previous();
        if (check(RIGHT_BRACE)) return previous();
        throw error(peek(), "ARC-E1080", "文末の ';' が必要です", "; を追加してください");
    }

    private Expr expression() { return assignment(); }

    private Expr assignment() {
        Expr left = or();
        if (match(EQUAL, PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, SLASH_EQUAL)) {
            Type assignmentOperator = previous().type();
            Expr value = assignment();
            if (!(left instanceof Variable) && !(left instanceof Member))
                throw error(previous(), "ARC-E1100", "代入先が不正です", "変数またはプロパティへ代入してください");
            if (assignmentOperator != EQUAL) {
                Type binary = switch (assignmentOperator) { case PLUS_EQUAL -> PLUS; case MINUS_EQUAL -> MINUS; case STAR_EQUAL -> STAR; default -> SLASH; };
                value = new Binary(left, binary, value, join(left.span(), value.span()));
            }
            return new Assignment(left, value, join(left.span(), value.span()));
        }
        return left;
    }

    private Expr or() { return binary(this::and, OR_OR); }
    private Expr and() { return binary(this::equality, AND_AND); }
    private Expr equality() { return binary(this::comparison, EQUAL_EQUAL, BANG_EQUAL); }
    private Expr comparison() { return binary(this::term, GREATER, GREATER_EQUAL, LESS, LESS_EQUAL); }
    private Expr term() { return binary(this::factor, PLUS, MINUS); }
    private Expr factor() { return binary(this::unary, STAR, SLASH, PERCENT); }

    private Expr binary(ExprSupplier next, Type... operators) {
        Expr expr = next.get();
        while (match(operators)) {
            Token operator = previous();
            Expr right = next.get();
            expr = new Binary(expr, operator.type(), right, join(expr.span(), right.span()));
        }
        return expr;
    }

    private Expr unary() {
        if (match(AWAIT)) {
            Token begin = previous();
            consume(PARALLEL, "ARC-E1101", "awaitの後にはparallelが必要です");
            return parallelExpression(begin, true);
        }
        if (match(BANG, MINUS)) {
            Token operator = previous();
            Expr operand = unary();
            return new Unary(operator.type(), operand, join(operator.span(), operand.span()));
        }
        return call();
    }

    private Expr call() {
        Expr expr = primary();
        while (true) {
            if (match(LEFT_PAREN)) expr = finishCall(expr);
            else if (match(DOT, DOUBLE_COLON)) {
                Token name;
                if (isPropertyName(peek().type())) name = advance();
                else throw error(peek(), "ARC-E1110", "プロパティ名が必要です");
                expr = new Member(expr, name.lexeme(), join(expr.span(), name.span()));
            } else if (match(QUESTION)) {
                expr = new TryExpr(expr, join(expr.span(), previous().span()));
            } else break;
        }
        return expr;
    }

    private Expr finishCall(Expr callee) {
        List<CallArg> args = new ArrayList<>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (args.size() >= 32) throw error(peek(), "ARC-E1120", "呼び出し引数は32個までです", "入力を分割してください");
                Token begin = peek();
                String name = null;
                if (isPropertyName(peek().type()) && checkNext(COLON)) { name = advance().lexeme(); advance(); }
                Expr value = expression();
                args.add(new CallArg(name, value, join(begin.span(), value.span())));
            } while (match(COMMA));
        }
        Token end = consume(RIGHT_PAREN, "ARC-E1121", "呼び出しの ')' が必要です");
        return new Call(callee, args, join(callee.span(), end.span()));
    }

    private Expr primary() {
        if (match(PARALLEL)) return parallelExpression(previous(), false);
        if (match(RACE)) return parallelExpression(previous(), false);
        if (match(MATCH)) return matchExpression(previous());
        if (match(FALSE)) return new Literal(false, previous().span());
        if (match(TRUE)) return new Literal(true, previous().span());
        if (match(NONE)) return new VariantExpr("None", null, previous().span());
        if (match(INT, FLOAT, STRING, DURATION)) return new Literal(previous().literal(), previous().span());
        // "spell" is both a declaration keyword and the safe spell.* host namespace.
        if (isPropertyName(peek().type()) || check(SPELL)) { advance(); return new Variable(previous().lexeme(), previous().span()); }
        if (match(LEFT_BRACKET)) {
            Token begin = previous();
            List<Expr> values = new ArrayList<>();
            if (!check(RIGHT_BRACKET)) do { values.add(expression()); } while (match(COMMA));
            Token end = consume(RIGHT_BRACKET, "ARC-E1130", "リストの ']' が必要です");
            return new ListExpr(values, join(begin.span(), end.span()));
        }
        if (match(LEFT_PAREN)) {
            Expr value = expression();
            consume(RIGHT_PAREN, "ARC-E1131", "式の ')' が必要です");
            return value;
        }
        throw error(peek(), "ARC-E1132", "式が必要です", "値、変数、または関数呼び出しを記述してください");
    }

    private Expr parallelExpression(Token begin, boolean awaited) {
        ParallelMode mode = begin.type() == RACE ? ParallelMode.RACE : ParallelMode.ALL;
        if (begin.type() != RACE && check(IDENT)) {
            if (peek().lexeme().equalsIgnoreCase("all")) { advance(); mode = ParallelMode.ALL; }
            else if (peek().lexeme().equalsIgnoreCase("any")) { advance(); mode = ParallelMode.ANY; }
        }
        consume(LEFT_BRACE, "ARC-E1133", "parallel/race本体の '{' が必要です");
        enter(begin);
        List<Stmt> branches = new ArrayList<>();
        Token end;
        try {
            while (!check(RIGHT_BRACE) && !atEnd()) {
                if (branches.size() >= 16) throw error(peek(), "ARC-E1134", "並列経路は16個までです");
                branches.add(statement());
            }
            end = consume(RIGHT_BRACE, "ARC-E1135", "parallel/race本体を閉じる '}' が必要です");
        } finally { depth--; }
        if (branches.isEmpty()) throw error(end, "ARC-E1136", "並列経路が空です");
        return new ParallelExpr(mode, branches, join(begin.span(), end.span()));
    }

    private Expr matchExpression(Token begin) {
        Expr subject = expression();
        consume(LEFT_BRACE, "ARC-E1140", "match本体の '{' が必要です");
        List<MatchArm> arms = new ArrayList<>();
        while (!check(RIGHT_BRACE) && !atEnd()) {
            Pattern pattern = matchPattern();
            consume(FAT_ARROW, "ARC-E1141", "matchアームの '=>' が必要です");
            Expr value = check(LEFT_BRACE) ? new BlockExpr(block(), previous().span()) : expression();
            arms.add(new MatchArm(pattern, value, join(pattern.span(), value.span())));
            match(COMMA, SEMICOLON);
        }
        Token end = consume(RIGHT_BRACE, "ARC-E1142", "match本体の '}' が必要です");
        if (arms.isEmpty()) throw error(end, "ARC-E1143", "matchには1つ以上のアームが必要です");
        return new MatchExpr(subject, arms, join(begin.span(), end.span()));
    }

    private Pattern matchPattern() {
        Token begin = peek();
        if (check(IDENT) && "_".equals(peek().lexeme())) { advance(); return new WildcardPattern(previous().span()); }
        if (match(NONE)) return new VariantPattern("None", null, previous().span());
        if (match(INT, FLOAT, STRING, DURATION, TRUE, FALSE)) {
            Token first = previous();
            Object value = first.type() == TRUE ? true : first.type() == FALSE ? false : first.literal();
            if (match(RANGE, RANGE_INCLUSIVE)) {
                boolean inclusive = previous().type() == RANGE_INCLUSIVE;
                Token end = consumeAny("ARC-E1144", "範囲パターンの終端値が必要です", INT, FLOAT, DURATION);
                return new RangePattern(value, end.literal(), inclusive, join(first.span(), end.span()));
            }
            return new LiteralPattern(value, first.span());
        }
        if (match(IDENT)) {
            StringBuilder name = new StringBuilder(previous().lexeme());
            Span span = previous().span();
            if (match(LEFT_PAREN)) {
                String binding = null;
                if (!check(RIGHT_PAREN)) binding = consumeName("ARC-E1147", "値を受け取る変数名が必要です").lexeme();
                Token end = consume(RIGHT_PAREN, "ARC-E1148", "バリアントパターンの ')' が必要です");
                return new VariantPattern(name.toString(), binding, join(span, end.span()));
            }
            if (match(DOUBLE_COLON)) { Token part = consume(IDENT, "ARC-E1145", "列挙値が必要です"); name.append("::").append(part.lexeme()); span = join(span, part.span()); }
            return new EnumPattern(name.toString(), span);
        }
        throw error(begin, "ARC-E1146", "matchパターンが不正です");
    }

    private Token consumeAny(String code, String message, Type... types) {
        if (match(types)) return previous();
        throw error(peek(), code, message);
    }

    private Token consumeName(String code, String message) {
        if (isPropertyName(peek().type())) return advance();
        throw error(peek(), code, message);
    }

    private void enter(Token token) {
        depth++;
        if (depth > MAX_PARSE_DEPTH) throw error(token, "ARC-E1099", "構文のネストが深すぎます", "ブロックを分割してください");
    }

    private ParseFailure error(Token token, String code, String message) { return error(token, code, message, "構文を確認してください"); }
    private ParseFailure error(Token token, String code, String message, String suggestion) {
        diagnostics.add(new Diagnostic(code, Severity.ERROR, message, token.span(), suggestion));
        return new ParseFailure();
    }
    private Token consume(Type type, String code, String message) { if (check(type)) return advance(); throw error(peek(), code, message); }
    private boolean match(Type... types) { for (Type type : types) if (check(type)) { advance(); return true; } return false; }
    private boolean check(Type type) { return peek().type() == type; }
    private static boolean isPropertyName(Type type) {
        return switch (type) {
            case IDENT, CAST, WAIT, SIGNAL, EMIT, SPAWN, RAYCAST, APPLY, LIMIT, ORDER, BY, WHERE,
                 ASC, DESC, ENTER, DAMAGE, DEATH, PULSE, HIT, KILL, SPELL_END, PROJECTILE_IMPACT,
                 STATE, TYPE, EFFECT, PERMISSION, FRAGMENTS, WITH, WITHIN, AT, TO -> true;
            default -> false;
        };
    }
    private boolean checkNext(Type type) { return current + 1 < tokens.size() && tokens.get(current + 1).type() == type; }
    private Token advance() { if (!atEnd()) current++; return previous(); }
    private boolean atEnd() { return peek().type() == EOF; }
    private Token peek() { return tokens.get(Math.min(current, tokens.size() - 1)); }
    private Token previous() { return tokens.get(Math.max(0, current - 1)); }

    private void synchronizeTopLevel() { while (!atEnd() && !check(SPELL)) advance(); }
    private void synchronizeMember() { while (!atEnd() && !check(ON) && !check(FN) && !check(RIGHT_BRACE)) advance(); }
    private void synchronizeStatement() {
        while (!atEnd()) {
            if (current > 0 && previous().type() == SEMICOLON) return;
            if (check(RIGHT_BRACE) || check(LET) || check(CONST) || check(STATE) || check(IF) || check(FOR) || check(REPEAT) || check(DEFER) || check(RETURN)) return;
            advance();
        }
    }
    private static Span join(Span start, Span end) { return new Span(start.start(), end.end()); }
    private static Token eofToken() {
        Position p = new Position(0, 1, 1);
        return new Token(EOF, "", null, new Span(p, p));
    }
    @FunctionalInterface private interface ExprSupplier { Expr get(); }
    private static final class ParseFailure extends RuntimeException { }
}
