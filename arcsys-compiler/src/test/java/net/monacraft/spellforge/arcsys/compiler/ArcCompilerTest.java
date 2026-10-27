package net.monacraft.spellforge.arcsys.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArcCompilerTest {
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
}
