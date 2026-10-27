package net.monacraft.spellforge.arcsys.vm;

import net.monacraft.spellforge.arcsys.compiler.ArcCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.monacraft.spellforge.arcsys.compiler.ArcBytecode;

import static org.junit.jupiter.api.Assertions.*;

class ArcVmTest {
    @Test void executesOnlyThroughClosedHostAndProducesEffects() {
        String source = "spell X { on cast { let t = target.nearest(8); spell.cast(\"THUNDER\", t); } }";
        var compiled = new ArcCompiler().compile(source);
        assertTrue(compiled.valid(), () -> compiled.diagnostics().toString());
        ArcVm vm = new ArcVm();
        var result = vm.run(compiled.program(), "on:cast", Map.of(), new ArcVm.Limits(128, 4), new ArcVm.Host() {
            public Object invoke(String api, List<Object> args, List<String> names, ArcVm.Execution execution) {
                if (api.equals("target.nearest")) return Map.of("id", "dummy", "hp_ratio", 1.0);
                if (api.equals("spell.cast")) { execution.request(new ArcVm.EffectRequest("cast", Map.of("spell", args.getFirst()))); return true; }
                throw new AssertionError(api);
            }
            public Object member(Object receiver, String name) { return ((Map<?, ?>) receiver).get(name); }
        });
        assertEquals(ArcVm.Status.COMPLETED, result.status());
        assertEquals(1, result.effects().size());
    }

    @Test void trapsWhenFuelIsExhausted() {
        var compiled = new ArcCompiler().compile("spell X { on cast { repeat(100) { math.clamp(1, 0, 2); } } }");
        assertTrue(compiled.valid(), () -> compiled.diagnostics().toString());
        var result = new ArcVm().run(compiled.program(), "on:cast", Map.of(), new ArcVm.Limits(20, 0), new NoopHost());
        assertEquals(ArcVm.Status.TRAPPED, result.status());
        assertEquals("ARC-R3002", result.code());
    }

    @Test void verifierRejectsInvalidJumpBeforeExecution() {
        var program = new ArcBytecode.Program("Bad",
                List.of(new ArcBytecode.Instruction(ArcBytecode.Op.JUMP, "", 999, 0)),
                Map.of("on:cast", 0), Set.of(), 1);
        var result = new ArcVm().run(program, "on:cast", Map.of(), new ArcVm.Limits(10, 0), new NoopHost());
        assertEquals(ArcVm.Status.TRAPPED, result.status());
        assertEquals("ARC-V4004", result.code());
    }

    private static final class NoopHost implements ArcVm.Host {
        public Object invoke(String api, List<Object> arguments, List<String> argumentNames, ArcVm.Execution execution) { return 1L; }
        public Object member(Object receiver, String name) { return null; }
    }
}
