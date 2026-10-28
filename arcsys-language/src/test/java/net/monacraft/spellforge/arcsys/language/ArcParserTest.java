package net.monacraft.spellforge.arcsys.language;

import net.monacraft.spellforge.arcsys.language.ArcAst.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArcParserTest {
    @Test
    void parsesDeferredCleanupBlock() {
        String source = """
            spell Cleanup {
              on cast {
                defer { event.emit(\"cleanup\"); }
                return 7;
              }
            }
            """;
        var result = ArcParser.parse(source);
        assertTrue(result.valid(), () -> result.diagnostics().toString());
        var statement = result.program().spells().getFirst().handlers().getFirst().body().statements().getFirst();
        assertInstanceOf(ArcAst.DeferStmt.class, statement);
    }

    @Test
    void parsesResultPatternsBlockArmsAndTryOperator() {
        String source = """
            spell ResultSpell {
              on cast {
                let result = spell.cast("TEST", 1);
                match result {
                  Ok(hit) => { return hit; },
                  Err(error) => { return error; }
                }
              }
              fn unwrap() { return Ok(3)?; }
            }
            """;
        var result = ArcParser.parse(source);
        assertTrue(result.valid(), () -> result.diagnostics().toString());
        var match = assertInstanceOf(ArcAst.MatchExpr.class,
                assertInstanceOf(ArcAst.ExprStmt.class, result.program().spells().getFirst().handlers().getFirst().body().statements().get(1)).expression());
        assertInstanceOf(ArcAst.VariantPattern.class, match.arms().getFirst().pattern());
        assertInstanceOf(ArcAst.BlockExpr.class, match.arms().getFirst().value());
    }

    @Test
    void parsesTypedPersistentStatePolicy() {
        var result = ArcParser.parse("spell X { state hits: Int = 0 persist(player, ttl: 7d); on cast {} }");
        assertTrue(result.valid(), () -> result.diagnostics().toString());
        var persistence = result.program().spells().getFirst().states().getFirst().persistence();
        assertEquals(ArcAst.PersistenceScope.PLAYER, persistence.scope());
        assertEquals(7L * 24 * 60 * 60 * 20, persistence.ttlTicks());
        assertFalse(ArcParser.parse("spell X { on cast { let x = 1 persist(player); } }").valid());
    }

    @Test
    void parsesAwaitParallelModesAndRace() {
        var result = ArcParser.parse("""
            spell X { on cast {
              let all = await parallel all { spell.cast("A"); spell.cast("B"); };
              let any = await parallel any { spell.cast("C"); wait 2t; };
              let winner = race { wait 1t; spell.cast("D"); };
            } }
            """);
        assertTrue(result.valid(), () -> result.diagnostics().toString());
        var statements = result.program().spells().getFirst().handlers().getFirst().body().statements();
        assertEquals(ArcAst.ParallelMode.ALL, assertInstanceOf(ArcAst.ParallelExpr.class,
                assertInstanceOf(ArcAst.VarDecl.class, statements.get(0)).initializer()).mode());
        assertEquals(ArcAst.ParallelMode.ANY, assertInstanceOf(ArcAst.ParallelExpr.class,
                assertInstanceOf(ArcAst.VarDecl.class, statements.get(1)).initializer()).mode());
        assertEquals(ArcAst.ParallelMode.RACE, assertInstanceOf(ArcAst.ParallelExpr.class,
                assertInstanceOf(ArcAst.VarDecl.class, statements.get(2)).initializer()).mode());
    }
    private static final String SAMPLE = """
        spell ApocalypseEngine {
          on pulse(20t) {
            let targets = mobs.hostile(radius: 24, limit: 8);
            for target in targets {
              if (target.hp_ratio < 0.3) {
                spell.cast("EXECUTION", target);
              } else {
                spell.cast("THUNDER", target);
              }
            }
          }
        }
        """;

    @Test void parsesDeveloperBridgeExample() {
        var result = ArcParser.parse(SAMPLE);
        assertTrue(result.valid(), () -> result.diagnostics().toString());
        assertEquals("ApocalypseEngine", result.program().spells().getFirst().name());
        HandlerDecl handler = result.program().spells().getFirst().handlers().getFirst();
        assertEquals(Trigger.PULSE, handler.trigger());
        assertEquals(2, handler.body().statements().size());
    }

    @Test void reportsMalformedSourceWithoutThrowing() {
        var result = ArcParser.parse("spell Broken { on pulse( { let = ; }");
        assertFalse(result.valid());
        assertFalse(result.diagnostics().isEmpty());
    }

    @Test void lexerRejectsOversizedSource() {
        var result = ArcParser.parse("x".repeat(ArcLexer.MAX_SOURCE_BYTES + 1));
        assertFalse(result.valid());
        assertEquals("ARC-E0001", result.diagnostics().getFirst().code());
    }

    @Test void parsesUnitsStatesEventsAndSkriptSugar() {
        String source = """
            module monaka.combat;
            const RADIUS: Distance = 24b;
            enum Mode { Beam, Burst }
            spell "Adaptive" {
              requires fragments { MOB_SENSOR: 1 }
              state hits: Int = 0 persist(player, ttl: 7d);
              on damage(player) {
                guard mana.current >= 100mp else { return; }
                chance 35% { emit signal "READY"; }
                wait 5t;
                repeat 2 { hits += 1; }
              }
            }
            """;
        var result = ArcParser.parse(source);
        assertTrue(result.valid(), () -> result.diagnostics().toString());
        assertEquals(2, result.program().spells().getFirst().states().size());
        assertEquals(Trigger.DAMAGE, result.program().spells().getFirst().handlers().getFirst().trigger());
    }

    @Test void typeCheckerRejectsMixedDimensions() {
        var parsed = ArcParser.parse("spell X { on cast { let broken = 5s + 4b; } }");
        assertTrue(parsed.valid(), () -> parsed.diagnostics().toString());
        var diagnostics = new ArcTypeChecker().check(parsed.program());
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("ARC-T5009")));
    }

    @Test void preservesAndChecksTypedFunctionSignatures() {
        var valid = ArcParser.parse("""
            fn distance_ok(value: Distance) -> Distance { return value; }
            spell X { on cast { let radius: Distance = distance_ok(8b); } }
            """);
        assertTrue(valid.valid(), () -> valid.diagnostics().toString());
        FunctionDecl function = valid.program().spells().getFirst().functions().getFirst();
        assertEquals("Distance", function.parameterTypes().get("value"));
        assertEquals("Distance", function.returnType());
        assertTrue(new ArcTypeChecker().check(valid.program()).stream().noneMatch(d -> d.severity() == SourceModel.Severity.ERROR));

        var invalid = ArcParser.parse("fn bad(value: Entity) -> Bool { return value; } spell X { on cast { bad(4); } }");
        var diagnostics = new ArcTypeChecker().check(invalid.program());
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("ARC-T5021")));
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("ARC-T5038")));

        var fallthrough = ArcParser.parse("fn maybe(value: Bool) -> Int { if (value) { return 1; } } spell X { on cast {} }");
        assertTrue(new ArcTypeChecker().check(fallthrough.program()).stream().anyMatch(d -> d.code().equals("ARC-T5041")));
    }

    @Test void rejectsUndefinedVariablesAndMutationWithoutMut() {
        var parsed = ArcParser.parse("spell X { on cast { let fixed = 1; fixed = 2; return missing; } }");
        var diagnostics = new ArcTypeChecker().check(parsed.program());
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("ARC-T5025")));
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("ARC-T5023")));

        var mutable = ArcParser.parse("spell X { on cast { let mut count: Int = 1; count = 2; } }");
        assertTrue(new ArcTypeChecker().check(mutable.program()).stream().noneMatch(d -> d.severity() == SourceModel.Severity.ERROR));
    }

    @Test void validatesMagicApiNamedArgumentsAndGenericResultTypes() {
        var parsed = ArcParser.parse("""
            fn inspect() -> Result<Any, String> { return combat.damage(target, 4); }
            spell X { on cast { let mobs = mobs.hostile(radius: 8b, limit: 4); } }
            """);
        var diagnostics = new ArcTypeChecker().check(parsed.program());
        assertTrue(diagnostics.stream().noneMatch(d -> d.severity() == SourceModel.Severity.ERROR), diagnostics.toString());

        var bad = ArcParser.parse("spell X { on cast { mobs.hostile(raduis: 8b); } }");
        assertTrue(new ArcTypeChecker().check(bad.program()).stream().anyMatch(d -> d.code().equals("ARC-T5029")));
    }

    @Test void parsesSkriptStyleCombatAndPeriodicSyntax() {
        var result = ArcParser.parse("""
            spell HELLFIRE_COUNTER {
              on damage(player) {
                chance 35% {
                  wait 5t;
                  cast FIRE at player;
                  apply BURNING to player for 5s;
                }
                every 20t for 5s { emit "PULSE"; }
              }
            }
            """);
        assertTrue(result.valid(), () -> result.diagnostics().toString());
    }
}
