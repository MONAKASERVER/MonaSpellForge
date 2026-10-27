package net.monacraft.spellforge.arcsys.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArcCompilerTest {
    @Test void enforcesPureAndDeclaredEffectFunctions() {
        var pure = new ArcCompiler().compile("fn bad(target) { combat.damage(target, 2); } spell X { on cast { bad(target.nearest(8)); } }");
        assertFalse(pure.valid());
        assertTrue(pure.diagnostics().stream().anyMatch(d -> d.code().equals("ARC-E2040")), pure.diagnostics().toString());

        var undeclared = new ArcCompiler().compile("effect fn bad(target) uses [MANA_CONSUME] { combat.damage(target, 2); } spell X { on cast { bad(target.nearest(8)); } }");
        assertFalse(undeclared.valid());
        assertTrue(undeclared.diagnostics().stream().anyMatch(d -> d.code().equals("ARC-E2041")));

        var valid = new ArcCompiler().compile("effect fn apply_damage(target) uses [COMBAT_DAMAGE] { combat.damage(target, 2); } spell X { on cast { apply_damage(target.nearest(8)); } }");
        assertTrue(valid.valid(), () -> valid.diagnostics().toString());
    }
    @Test
    void emitsDeferredCleanupBeforeReturn() {
        String source = """
            spell Cleanup {
              on cast {
                defer { event.emit(\"cleanup\"); }
                return 7;
              }
            }
            """;
        var result = new ArcCompiler().compile(source);
        assertTrue(result.valid(), () -> result.diagnostics().toString());
        var code = result.program().instructions();
        int cleanup = -1;
        int firstReturn = -1;
        for (int i = 0; i < code.size(); i++) {
            if (cleanup < 0 && code.get(i).op() == ArcBytecode.Op.CALL_HOST && code.get(i).text().startsWith("event.emit")) cleanup = i;
            if (firstReturn < 0 && code.get(i).op() == ArcBytecode.Op.RETURN) firstReturn = i;
        }
        assertTrue(cleanup >= 0 && cleanup < firstReturn);
    }

    @Test
    void rejectsQuestionOperatorForNonResultValue() {
        var result = new ArcCompiler().compile("spell X { on cast { return Some(1)?; } }");
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("ARC-T5015")));
    }

    @Test
    void embedsPersistentStateScopeAndTtlInClosedHostCalls() {
        var result = new ArcCompiler().compile("spell X { state hits = 0 persist(player, ttl: 7d); on cast { hits += 1; } }");
        assertTrue(result.valid(), () -> result.diagnostics().toString());
        assertTrue(result.program().instructions().stream().anyMatch(instruction ->
                instruction.op() == ArcBytecode.Op.CALL_HOST && instruction.text().equals("state.get\u0000default,name,scope,ttl")));
        assertTrue(result.program().instructions().stream().anyMatch(instruction ->
                instruction.op() == ArcBytecode.Op.PUSH_LONG && instruction.number() == 7L * 24 * 60 * 60 * 20));
    }

    @Test
    void compilesParallelBranchesIntoVerifiedTaskEntries() {
        var result = new ArcCompiler().compile("""
            spell X { on cast {
              let results = await parallel all { spell.cast("A"); wait 2t; };
              return results.all_success;
            } }
            """);
        assertTrue(result.valid(), () -> result.diagnostics().toString());
        var parallel = result.program().instructions().stream().filter(i -> i.op() == ArcBytecode.Op.PARALLEL).findFirst().orElseThrow();
        assertEquals(2, parallel.number());
        assertEquals(2, result.program().entryPoints().keySet().stream().filter(name -> name.startsWith("task:")).count());
    }
    private static final String SOURCE = """
        spell Test { on pulse(20t) {
          let targets = mobs.hostile(radius: 24, limit: 8);
          for target in targets { if (target.hp_ratio < 0.3) { spell.cast("EXECUTION", target); } }
        } }
        """;

    @Test void compilationIsDeterministicAndTracksCapabilities() {
        var first = new ArcCompiler().compile(SOURCE);
        var second = new ArcCompiler().compile(SOURCE);
        assertTrue(first.valid(), () -> first.diagnostics().toString());
        assertEquals(first.program().sha256(), second.program().sha256());
        assertTrue(first.program().capabilities().contains("spell.cast"));
        assertTrue(first.program().capabilities().contains("sense.hostile"));
    }

    @Test void rejectsArbitraryHostCalls() {
        var result = new ArcCompiler().compile("spell X { on cast { java.lang.Runtime.exec(\"calc\"); } }");
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("ARC-E2030")));
    }

    @Test void compilesTypedLocalFunctionCalls() {
        var result = new ArcCompiler().compile("""
            fn double_it(value: Int) -> Int { return value * 2; }
            spell X { on cast { let result = double_it(4); spell.cast("TEST", result); } }
            """);
        assertTrue(result.valid(), () -> result.diagnostics().toString());
        assertTrue(result.program().instructions().stream().anyMatch(i -> i.op() == ArcBytecode.Op.CALL && i.text().equals("double_it")));
    }
}
