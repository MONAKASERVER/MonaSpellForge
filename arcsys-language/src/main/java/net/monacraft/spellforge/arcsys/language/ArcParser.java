package net.monacraft.spellforge.arcsys.language;

import net.monacraft.spellforge.arcsys.language.ArcAst.*;
import net.monacraft.spellforge.arcsys.language.SourceModel.*;
import net.monacraft.spellforge.arcsys.language.Token.Type;

import java.util.ArrayList;
import java.util.List;

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
        Token first = peek();
        while (!atEnd()) {
            try {
                spells.add(spell());
            } catch (ParseFailure ignored) {
                synchronizeTopLevel();
            }
        }
        Span span = spells.isEmpty() ? first.span() : join(first.span(), previous().span());
        return new ParseResult(new Program(spells, span), diagnostics);
    }

    private SpellDecl spell() {
        Token begin = consume(SPELL, "ARC-E1001", "'spell' が必要です");
        Token name = consume(IDENT, "ARC-E1002", "術式名が必要です");
        consume(LEFT_BRACE, "ARC-E1003", "術式本体の '{' が必要です");
        List<HandlerDecl> handlers = new ArrayList<>();
        List<FunctionDecl> functions = new ArrayList<>();
        while (!check(RIGHT_BRACE) && !atEnd()) {
            try {
                if (match(ON)) handlers.add(handler(previous()));
                else if (match(FN)) functions.add(function(previous()));
                else throw error(peek(), "ARC-E1004", "術式直下には on または fn を記述してください", "イベントハンドラか関数へ移動してください");
            } catch (ParseFailure ignored) {
                synchronizeMember();
            }
        }
        Token end = consume(RIGHT_BRACE, "ARC-E1005", "術式本体を閉じる '}' が必要です");
        return new SpellDecl(name.lexeme(), handlers, functions, join(begin.span(), end.span()));
    }

    private HandlerDecl handler(Token begin) {
        Trigger trigger;
        if (match(CAST)) trigger = Trigger.CAST;
        else if (match(PULSE)) trigger = Trigger.PULSE;
        else if (match(HIT)) trigger = Trigger.HIT;
        else if (match(KILL)) trigger = Trigger.KILL;
        else if (match(DAMAGE_TAKEN)) trigger = Trigger.DAMAGE_TAKEN;
        else throw error(peek(), "ARC-E1010", "未知のイベントです", "cast, pulse, hit, kill, damage_taken のいずれかを使用してください");
        Expr argument = null;
        if (match(LEFT_PAREN)) {
            if (!check(RIGHT_PAREN)) argument = expression();
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
            } while (match(COMMA));
        }
        consume(RIGHT_PAREN, "ARC-E1024", "引数リストの ')' が必要です");
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
        if (match(RETURN)) return returnStatement(previous());
        if (match(BREAK)) { Token t = previous(); finishStatement(); return new BreakStmt(t.span()); }
        if (match(CONTINUE)) { Token t = previous(); finishStatement(); return new ContinueStmt(t.span()); }
        if (check(LEFT_BRACE)) return block();
        Expr expr = expression();
        Token end = finishStatement();
        return new ExprStmt(expr, join(expr.span(), end.span()));
    }

    private VarDecl variable(Token begin) {
        Token name = consume(IDENT, "ARC-E1040", "変数名が必要です");
        consume(EQUAL, "ARC-E1041", "変数の初期値が必要です");
        Expr initializer = expression();
        Token end = finishStatement();
        Binding binding = switch (begin.type()) { case LET -> Binding.LET; case CONST -> Binding.CONST; default -> Binding.STATE; };
        return new VarDecl(binding, name.lexeme(), initializer, join(begin.span(), end.span()));
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
        consume(LEFT_PAREN, "ARC-E1070", "repeat 回数の '(' が必要です");
        Expr count = expression();
        consume(RIGHT_PAREN, "ARC-E1071", "repeat 回数の ')' が必要です");
        Block body = block();
        return new RepeatStmt(count, body, join(begin.span(), body.span()));
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
        if (match(EQUAL)) {
            Expr value = assignment();
            if (!(left instanceof Variable) && !(left instanceof Member))
                throw error(previous(), "ARC-E1100", "代入先が不正です", "変数またはプロパティへ代入してください");
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
            else if (match(DOT)) {
                Token name;
                if (match(IDENT, CAST)) name = previous();
                else throw error(peek(), "ARC-E1110", "プロパティ名が必要です");
                expr = new Member(expr, name.lexeme(), join(expr.span(), name.span()));
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
                if (check(IDENT) && checkNext(COLON)) { name = advance().lexeme(); advance(); }
                Expr value = expression();
                args.add(new CallArg(name, value, join(begin.span(), value.span())));
            } while (match(COMMA));
        }
        Token end = consume(RIGHT_PAREN, "ARC-E1121", "呼び出しの ')' が必要です");
        return new Call(callee, args, join(callee.span(), end.span()));
    }

    private Expr primary() {
        if (match(FALSE)) return new Literal(false, previous().span());
        if (match(TRUE)) return new Literal(true, previous().span());
        if (match(NONE)) return new Literal(null, previous().span());
        if (match(INT, FLOAT, STRING, DURATION)) return new Literal(previous().literal(), previous().span());
        // "spell" is both a declaration keyword and the safe spell.* host namespace.
        if (match(IDENT, SPELL)) return new Variable(previous().lexeme(), previous().span());
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
            if (check(RIGHT_BRACE) || check(LET) || check(CONST) || check(STATE) || check(IF) || check(FOR) || check(REPEAT) || check(RETURN)) return;
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
