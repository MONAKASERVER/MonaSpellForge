package net.monacraft.spellforge.arcsys.language;

import net.monacraft.spellforge.arcsys.language.ArcAst.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArcParserTest {
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
}
