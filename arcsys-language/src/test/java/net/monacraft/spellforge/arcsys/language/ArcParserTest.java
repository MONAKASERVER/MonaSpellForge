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
