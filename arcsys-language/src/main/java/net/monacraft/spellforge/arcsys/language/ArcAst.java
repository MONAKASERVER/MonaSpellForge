package net.monacraft.spellforge.arcsys.language;

import net.monacraft.spellforge.arcsys.language.SourceModel.Span;

import java.util.List;
import java.util.Map;

/** Immutable source AST. It deliberately contains no executable Java hooks. */
public final class ArcAst {
    private ArcAst() { }

    public sealed interface Node permits Program, ModuleDecl, UseDecl, TypeDecl, SpellDecl, FunctionDecl, HandlerDecl, Stmt, Expr {
        Span span();
    }

    public record Program(ModuleDecl module, List<UseDecl> imports, List<TypeDecl> types,
                          List<VarDecl> globalConstants, List<FunctionDecl> globalFunctions,
                          List<SpellDecl> spells, Span span) implements Node {
        public Program {
            imports = List.copyOf(imports); types = List.copyOf(types);
            globalConstants = List.copyOf(globalConstants); globalFunctions = List.copyOf(globalFunctions);
            spells = List.copyOf(spells);
        }
        public Program(ModuleDecl module, List<UseDecl> imports, List<TypeDecl> types, List<SpellDecl> spells, Span span) {
            this(module, imports, types, List.of(), List.of(), spells, span);
        }
        public Program(List<SpellDecl> spells, Span span) { this(null, List.of(), List.of(), List.of(), List.of(), spells, span); }
    }

    public record ModuleDecl(String name, Span span) implements Node { }
    public record UseDecl(String module, String alias, Span span) implements Node { }

    public sealed interface TypeDecl extends Node permits StructDecl, EnumDecl { String name(); }
    public record FieldDecl(String name, String type, Span span) { }
    public record StructDecl(String name, List<FieldDecl> fields, Span span) implements TypeDecl {
        public StructDecl { fields = List.copyOf(fields); }
    }
    public record EnumVariantDecl(String name, String payloadType, Span span) { }
    public record EnumDecl(String name, List<EnumVariantDecl> variants, Span span) implements TypeDecl {
        public EnumDecl { variants = List.copyOf(variants); }
    }

    public record SpellDecl(String name, Map<String, Integer> requiredFragments, List<String> requiredPermissions,
                            List<VarDecl> states, List<HandlerDecl> handlers,
                            List<FunctionDecl> functions, List<Expr> preconditions, List<Expr> postconditions,
                            Span span) implements Node {
        public SpellDecl {
            requiredFragments = Map.copyOf(requiredFragments);
            requiredPermissions = List.copyOf(requiredPermissions);
            states = List.copyOf(states);
            handlers = List.copyOf(handlers);
            functions = List.copyOf(functions);
            preconditions = List.copyOf(preconditions);
            postconditions = List.copyOf(postconditions);
        }
        public SpellDecl(String name, Map<String, Integer> requiredFragments, List<String> requiredPermissions,
                         List<VarDecl> states, List<HandlerDecl> handlers, List<FunctionDecl> functions, Span span) {
            this(name, requiredFragments, requiredPermissions, states, handlers, functions, List.of(), List.of(), span);
        }
    }

    public record FunctionDecl(String name, List<String> parameters, Map<String, String> parameterTypes,
                               String returnType, boolean effectful,
                               java.util.Set<String> declaredEffects, Block body, Span span) implements Node {
        public FunctionDecl {
            parameters = List.copyOf(parameters); parameterTypes = Map.copyOf(parameterTypes);
            declaredEffects = java.util.Set.copyOf(declaredEffects);
        }
        public FunctionDecl(String name, List<String> parameters, boolean effectful,
                            java.util.Set<String> declaredEffects, Block body, Span span) {
            this(name, parameters, Map.of(), null, effectful, declaredEffects, body, span);
        }
        public FunctionDecl(String name, List<String> parameters, Block body, Span span) {
            this(name, parameters, Map.of(), null, false, java.util.Set.of(), body, span);
        }
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
    public enum PersistenceScope { PLAYER, SPELL, SESSION }
    public record Persistence(PersistenceScope scope, long ttlTicks) {
        public Persistence {
            if (scope == null || ttlTicks < 0) throw new IllegalArgumentException("persistence");
        }
    }

    public record VarDecl(Binding binding, String name, String declaredType, boolean mutable,
                          Expr initializer, Persistence persistence, Span span) implements Stmt {
        public VarDecl(Binding binding, String name, Expr initializer, Persistence persistence, Span span) {
            this(binding, name, null, binding == Binding.STATE, initializer, persistence, span);
        }
        public VarDecl(Binding binding, String name, Expr initializer, Span span) {
            this(binding, name, null, binding == Binding.STATE, initializer, null, span);
        }
    }
    public record IfStmt(Expr condition, Block thenBranch, Block elseBranch, Span span) implements Stmt { }
    public record ForStmt(String variable, Expr iterable, Block body, Span span) implements Stmt { }
    public record RepeatStmt(Expr count, Block body, Span span) implements Stmt { }
    public record DeferStmt(Block body, Span span) implements Stmt { }
    public record ReturnStmt(Expr value, Span span) implements Stmt { }
    public record BreakStmt(Span span) implements Stmt { }
    public record ContinueStmt(Span span) implements Stmt { }
    public record ExprStmt(Expr expression, Span span) implements Stmt { }

    public sealed interface Expr extends Node permits Literal, Variable, Unary, Binary,
        Assignment, Member, Call, ListExpr, MatchExpr, VariantExpr, TryExpr, BlockExpr, ParallelExpr { }

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
    public record VariantExpr(String tag, Expr value, Span span) implements Expr { }
    public record TryExpr(Expr value, Span span) implements Expr { }
    public record BlockExpr(Block body, Span span) implements Expr { }
    public enum ParallelMode { ALL, ANY, RACE }
    public record ParallelExpr(ParallelMode mode, List<Stmt> branches, Span span) implements Expr {
        public ParallelExpr { branches = List.copyOf(branches); }
    }

    public sealed interface Pattern permits WildcardPattern, LiteralPattern, RangePattern, EnumPattern, VariantPattern { Span span(); }
    public record WildcardPattern(Span span) implements Pattern { }
    public record LiteralPattern(Object value, Span span) implements Pattern { }
    public record RangePattern(Object start, Object end, boolean inclusive, Span span) implements Pattern { }
    public record EnumPattern(String name, Span span) implements Pattern { }
    public record VariantPattern(String tag, String binding, Span span) implements Pattern { }
    public record MatchArm(Pattern pattern, Expr value, Span span) { }
    public record MatchExpr(Expr subject, List<MatchArm> arms, Span span) implements Expr {
        public MatchExpr { arms = List.copyOf(arms); }
    }
}
