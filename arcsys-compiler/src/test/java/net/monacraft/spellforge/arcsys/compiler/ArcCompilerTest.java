package net.monacraft.spellforge.arcsys.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArcCompilerTest {
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
