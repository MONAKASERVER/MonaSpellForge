package net.monacraft.spellforge.arcsys.language;

import net.monacraft.spellforge.arcsys.language.SourceModel.Span;

public record Token(Type type, String lexeme, Object literal, Span span) {
    public enum Type {
        EOF, IDENT, STRING, INT, FLOAT, DURATION,
        SPELL, MODULE, USE, FN, ON, CAST, PULSE, HIT, KILL, DAMAGE_TAKEN,
        LET, CONST, STATE, IF, ELSE, MATCH, FOR, IN, REPEAT, BREAK, CONTINUE, RETURN,
        TRUE, FALSE, NONE,
        LEFT_PAREN, RIGHT_PAREN, LEFT_BRACE, RIGHT_BRACE, LEFT_BRACKET, RIGHT_BRACKET,
        COMMA, DOT, COLON, SEMICOLON, ARROW,
        PLUS, MINUS, STAR, SLASH, PERCENT, BANG, BANG_EQUAL, EQUAL, EQUAL_EQUAL,
        GREATER, GREATER_EQUAL, LESS, LESS_EQUAL, AND_AND, OR_OR
    }
}
