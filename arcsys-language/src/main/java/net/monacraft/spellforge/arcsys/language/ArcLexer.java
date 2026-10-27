package net.monacraft.spellforge.arcsys.language;

import net.monacraft.spellforge.arcsys.language.SourceModel.*;
import net.monacraft.spellforge.arcsys.language.Token.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ArcLexer {
    public static final int MAX_SOURCE_BYTES = 65_536;
    public static final int MAX_TOKEN_LENGTH = 2_048;
    private static final Map<String, Type> KEYWORDS = keywords();

    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int start, current, line = 1, column = 1, startLine = 1, startColumn = 1;

    public ArcLexer(String source) {
        this.source = source == null ? "" : source;
    }

    public Result scan() {
        if (source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            diagnostics.add(error("ARC-E0001", "ソースが上限65536バイトを超えています", spanAt(0, 0, 1, 1), "コードを分割してください"));
            return new Result(List.of(eof()), diagnostics);
        }
        while (!atEnd()) {
            start = current; startLine = line; startColumn = column;
            scanToken();
        }
        tokens.add(eof());
        return new Result(tokens, diagnostics);
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case '(' -> add(Type.LEFT_PAREN); case ')' -> add(Type.RIGHT_PAREN);
            case '{' -> add(Type.LEFT_BRACE); case '}' -> add(Type.RIGHT_BRACE);
            case '[' -> add(Type.LEFT_BRACKET); case ']' -> add(Type.RIGHT_BRACKET);
            case ',' -> add(Type.COMMA);
            case '.' -> {
                if (match('.')) add(match('=') ? Type.RANGE_INCLUSIVE : Type.RANGE);
                else add(Type.DOT);
            }
            case ':' -> add(match(':') ? Type.DOUBLE_COLON : Type.COLON); case ';' -> add(Type.SEMICOLON);
            case '+' -> add(match('=') ? Type.PLUS_EQUAL : Type.PLUS);
            case '*' -> add(match('=') ? Type.STAR_EQUAL : Type.STAR);
            case '%' -> add(Type.PERCENT);
            case '-' -> add(match('>') ? Type.ARROW : match('=') ? Type.MINUS_EQUAL : Type.MINUS);
            case '!' -> add(match('=') ? Type.BANG_EQUAL : Type.BANG);
            case '=' -> add(match('=') ? Type.EQUAL_EQUAL : match('>') ? Type.FAT_ARROW : Type.EQUAL);
            case '<' -> add(match('=') ? Type.LESS_EQUAL : Type.LESS);
            case '>' -> add(match('=') ? Type.GREATER_EQUAL : Type.GREATER);
            case '&' -> { if (match('&')) add(Type.AND_AND); else invalid("'&&'を使用してください"); }
            case '|' -> { if (match('|')) add(Type.OR_OR); else invalid("'||'を使用してください"); }
            case '?' -> add(Type.QUESTION); case '@' -> add(Type.AT_SIGN);
            case '/' -> {
                if (match('/')) while (!atEnd() && peek() != '\n') advance();
                else if (match('*')) blockComment();
                else add(match('=') ? Type.SLASH_EQUAL : Type.SLASH);
            }
            case ' ', '\r', '\t' -> { }
            case '\n' -> { line++; column = 1; }
            case '"' -> string();
            default -> {
                if (Character.isDigit(c)) number();
                else if (isIdentifierStart(c)) identifier();
                else invalid("使用できない文字です");
            }
        }
    }

    private void blockComment() {
        int depth = 1;
        while (!atEnd() && depth > 0) {
            if (peek() == '/' && peekNext() == '*') { advance(); advance(); depth++; }
            else if (peek() == '*' && peekNext() == '/') { advance(); advance(); depth--; }
            else { char c = advance(); if (c == '\n') { line++; column = 1; } }
            if (depth > 16) { invalid("コメントのネストが深すぎます"); return; }
        }
        if (depth != 0) diagnostics.add(error("ARC-E0003", "ブロックコメントが閉じられていません", span(), "*/ を追加してください"));
    }

    private void string() {
        StringBuilder value = new StringBuilder();
        while (!atEnd() && peek() != '"') {
            char c = advance();
            if (c == '\n') { line++; column = 1; }
            if (c == '\\' && !atEnd()) {
                char escaped = advance();
                value.append(switch (escaped) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; case '"' -> '"'; case '\\' -> '\\'; default -> escaped; });
            } else value.append(c);
            if (current - start > MAX_TOKEN_LENGTH) { invalid("文字列が長すぎます"); return; }
        }
        if (atEnd()) { diagnostics.add(error("ARC-E0004", "文字列が閉じられていません", span(), "\" を追加してください")); return; }
        advance(); add(Type.STRING, value.toString());
    }

    private void number() {
        while (Character.isDigit(peek())) advance();
        boolean floating = false;
        if (peek() == '.' && Character.isDigit(peekNext())) { floating = true; advance(); while (Character.isDigit(peek())) advance(); }
        String number = source.substring(start, current);
        if (peek() == 't' || peek() == 's' || peek() == 'b' || peek() == 'd' || peek() == '%'
                || (peek() == 'm' && peekNext() == 'p')) {
            char unit = advance();
            if (unit == 'm') advance();
            double amount = Double.parseDouble(number);
            SourceModel.Unit kind = switch (unit) {
                case 't' -> SourceModel.Unit.TICKS;
                case 's' -> SourceModel.Unit.TICKS;
                case 'b' -> SourceModel.Unit.BLOCKS;
                case 'm' -> SourceModel.Unit.MANA;
                case '%' -> SourceModel.Unit.PERCENT;
                case 'd' -> SourceModel.Unit.DAYS;
                default -> throw new IllegalStateException();
            };
            if (unit == 's') amount *= 20.0;
            add(Type.DURATION, new SourceModel.UnitValue(amount, kind));
            return;
        }
        try {
            if (floating) add(Type.FLOAT, Double.parseDouble(number));
            else add(Type.INT, Long.parseLong(number));
        }
        catch (NumberFormatException error) { invalid("数値が範囲外です"); }
    }

    private void identifier() {
        while (isIdentifierPart(peek())) {
            advance();
            if (current - start > 256) { invalid("識別子が長すぎます"); return; }
        }
        String text = source.substring(start, current);
        add(KEYWORDS.getOrDefault(text, Type.IDENT));
    }

    private boolean atEnd() { return current >= source.length(); }
    private char advance() { char result = source.charAt(current++); column++; return result; }
    private boolean match(char expected) { if (atEnd() || source.charAt(current) != expected) return false; current++; column++; return true; }
    private char peek() { return atEnd() ? '\0' : source.charAt(current); }
    private char peekNext() { return current + 1 >= source.length() ? '\0' : source.charAt(current + 1); }
    private static boolean isIdentifierStart(char c) { return c == '_' || Character.isLetter(c); }
    private static boolean isIdentifierPart(char c) { return c == '_' || Character.isLetterOrDigit(c); }
    private void add(Type type) { add(type, null); }
    private void add(Type type, Object literal) { tokens.add(new Token(type, source.substring(start, current), literal, span())); }
    private void invalid(String message) { diagnostics.add(error("ARC-E0002", message, span(), "ArcSysの有効な字句へ修正してください")); }
    private Span span() { return spanAt(start, current, startLine, startColumn); }
    private Span spanAt(int from, int to, int atLine, int atColumn) { return new Span(new Position(from, atLine, atColumn), new Position(to, line, column)); }
    private Token eof() { Position p = new Position(current, line, column); return new Token(Type.EOF, "", null, new Span(p, p)); }
    private static Diagnostic error(String code, String message, Span span, String suggestion) { return new Diagnostic(code, Severity.ERROR, message, span, suggestion); }

    private static Map<String, Type> keywords() {
        Map<String, Type> values = new HashMap<>();
        values.put("spell", Type.SPELL); values.put("module", Type.MODULE); values.put("use", Type.USE); values.put("fn", Type.FN);
        values.put("on", Type.ON); values.put("cast", Type.CAST); values.put("pulse", Type.PULSE); values.put("hit", Type.HIT);
        values.put("kill", Type.KILL); values.put("damage_taken", Type.DAMAGE_TAKEN); values.put("damage", Type.DAMAGE); values.put("death", Type.DEATH);
        values.put("enter", Type.ENTER); values.put("mana_below", Type.MANA_BELOW); values.put("spell_end", Type.SPELL_END);
        values.put("signal", Type.SIGNAL); values.put("projectile_impact", Type.PROJECTILE_IMPACT);
        values.put("let", Type.LET); values.put("const", Type.CONST); values.put("mut", Type.MUT);
        values.put("state", Type.STATE); values.put("if", Type.IF); values.put("else", Type.ELSE); values.put("match", Type.MATCH);
        values.put("for", Type.FOR); values.put("in", Type.IN); values.put("repeat", Type.REPEAT); values.put("break", Type.BREAK);
        values.put("continue", Type.CONTINUE); values.put("return", Type.RETURN); values.put("guard", Type.GUARD); values.put("defer", Type.DEFER);
        values.put("emit", Type.EMIT); values.put("chance", Type.CHANCE); values.put("wait", Type.WAIT); values.put("every", Type.EVERY);
        values.put("loop", Type.LOOP); values.put("foreach", Type.FOREACH); values.put("parallel", Type.PARALLEL); values.put("await", Type.AWAIT);
        values.put("after", Type.AFTER); values.put("atomic", Type.ATOMIC); values.put("transaction", Type.TRANSACTION);
        values.put("visual", Type.VISUAL); values.put("combat", Type.COMBAT); values.put("pub", Type.PUB); values.put("private", Type.PRIVATE);
        values.put("struct", Type.STRUCT); values.put("enum", Type.ENUM); values.put("type", Type.TYPE); values.put("trait", Type.TRAIT);
        values.put("impl", Type.IMPL); values.put("effect", Type.EFFECT); values.put("uses", Type.USES); values.put("requires", Type.REQUIRES);
        values.put("fragments", Type.FRAGMENTS); values.put("permission", Type.PERMISSION); values.put("ensures", Type.ENSURES);
        values.put("persist", Type.PERSIST); values.put("select", Type.SELECT); values.put("where", Type.WHERE); values.put("order", Type.ORDER);
        values.put("by", Type.BY); values.put("limit", Type.LIMIT); values.put("asc", Type.ASC); values.put("desc", Type.DESC);
        values.put("apply", Type.APPLY); values.put("spawn", Type.SPAWN); values.put("raycast", Type.RAYCAST); values.put("with", Type.WITH);
        values.put("within", Type.WITHIN); values.put("at", Type.AT); values.put("to", Type.TO);
        values.put("true", Type.TRUE); values.put("false", Type.FALSE);
        values.put("none", Type.NONE); return Map.copyOf(values);
    }

    public record Result(List<Token> tokens, List<Diagnostic> diagnostics) {
        public Result { tokens = List.copyOf(tokens); diagnostics = List.copyOf(diagnostics); }
    }
}
