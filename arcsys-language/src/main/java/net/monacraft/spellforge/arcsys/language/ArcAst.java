package net.monacraft.spellforge.arcsys.language;

import net.monacraft.spellforge.arcsys.language.SourceModel.Span;

import java.util.List;
import java.util.Map;

/** Immutable source AST. It deliberately contains no executable Java hooks. */
public final class ArcAst {
    private ArcAst() { }

    public sealed interface Node permits Program, SpellDecl, FunctionDecl, HandlerDecl, Stmt, Expr {
        Span span();
    }

    public record Program(List<SpellDecl> spells, Span span) implements Node {
        public Program { spells = List.copyOf(spells); }
    }

    public record SpellDecl(String name, Map<String, Integer> requiredFragments, List<String> requiredPermissions,
                            List<VarDecl> states, List<HandlerDecl> handlers,
                            List<FunctionDecl> functions, Span span) implements Node {
        public SpellDecl {
            requiredFragments = Map.copyOf(requiredFragments);
            requiredPermissions = List.copyOf(requiredPermissions);
            states = List.copyOf(states);
            handlers = List.copyOf(handlers);
            functions = List.copyOf(functions);
        }
    }

    public record FunctionDecl(String name, List<String> parameters, Block body, Span span) implements Node {
        public FunctionDecl { parameters = List.copyOf(parameters); }
    }

    public enum Trigger {
        CAST, PULSE, HIT, KILL, DAMAGE_TAKEN, DAMAGE, DEATH, ENTER,
        MANA_BELOW, SPELL_END, SIGNAL, PROJECTILE_IMPACT
    }

    public record HandlerDecl(Trigger trigger, Expr argument, Block body, Span span) implements Node { }

    public sealed interface Stmt extends Node permits Block, VarDecl, IfStmt, ForStmt,
        RepeatStmt, DeferStmt, ReturnStmt, BreakStmt, ContinueStmt, ExprStmt { }

    public record Block(List<Stmt> statements, Span span) implements Stmt {
        public Block { statements = List.copyOf(statements); }
    }

    public enum Binding { LET, CONST, STATE }

    public record VarDecl(Binding binding, String name, Expr initializer, Span span) implements Stmt { }
    public record IfStmt(Expr condition, Block thenBranch, Block elseBranch, Span span) implements Stmt { }
    public record ForStmt(String variable, Expr iterable, Block body, Span span) implements Stmt { }
    public record RepeatStmt(Expr count, Block body, Span span) implements Stmt { }
    public record DeferStmt(Block body, Span span) implements Stmt { }
    public record ReturnStmt(Expr value, Span span) implements Stmt { }
    public record BreakStmt(Span span) implements Stmt { }
    public record ContinueStmt(Span span) implements Stmt { }
    public record ExprStmt(Expr expression, Span span) implements Stmt { }

    public sealed interface Expr extends Node permits Literal, Variable, Unary, Binary,
        Assignment, Member, Call, ListExpr, MatchExpr { }

    public record Literal(Object value, Span span) implements Expr { }
    public record Variable(String name, Span span) implements Expr { }
    public record Unary(Token.Type operator, Expr operand, Span span) implements Expr { }
    public record Binary(Expr left, Token.Type operator, Expr right, Span span) implements Expr { }
    public record Assignment(Expr target, Expr value, Span span) implements Expr { }
    public record Member(Expr receiver, String name, Span span) implements Expr { }
    public record Call(Expr callee, List<CallArg> arguments, Span span) implements Expr {
        public Call { arguments = List.copyOf(arguments); }
    }
    public record CallArg(String name, Expr value, Span span) { }
    public record ListExpr(List<Expr> values, Span span) implements Expr {
        public ListExpr { values = List.copyOf(values); }
    }

    public sealed interface Pattern permits WildcardPattern, LiteralPattern, RangePattern, EnumPattern { Span span(); }
    public record WildcardPattern(Span span) implements Pattern { }
    public record LiteralPattern(Object value, Span span) implements Pattern { }
    public record RangePattern(Object start, Object end, boolean inclusive, Span span) implements Pattern { }
    public record EnumPattern(String name, Span span) implements Pattern { }
    public record MatchArm(Pattern pattern, Expr value, Span span) { }
    public record MatchExpr(Expr subject, List<MatchArm> arms, Span span) implements Expr {
        public MatchExpr { arms = List.copyOf(arms); }
    }
}
