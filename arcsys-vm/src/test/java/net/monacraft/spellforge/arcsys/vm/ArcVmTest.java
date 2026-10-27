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
                Map.of("on:cast", 0), Set.of(), Set.of(), 1);
        var result = new ArcVm().run(program, "on:cast", Map.of(), new ArcVm.Limits(10, 0), new NoopHost());
        assertEquals(ArcVm.Status.TRAPPED, result.status());
        assertEquals("ARC-V4004", result.code());
    }

    @Test void executesBoundedLocalFunction() {
        var compiled = new ArcCompiler().compile("""
            fn double_it(value: Int) -> Int { return value * 2; }
            spell X { on cast { return double_it(4); } }
            """);
        assertTrue(compiled.valid(), () -> compiled.diagnostics().toString());
        var result = new ArcVm().run(compiled.program(), "on:cast", Map.of(), new ArcVm.Limits(100, 0), new NoopHost());
        assertEquals(ArcVm.Status.COMPLETED, result.status());
        assertEquals(8.0, result.value());
    }

    @Test void matchesOptionAndResultVariantsWithBindings() {
        var resultProgram = new ArcCompiler().compile("""
            spell X { on cast {
              let result = Ok(7);
              return match result { Ok(value) => value, Err(error) => 0 };
            } }
            """);
        assertTrue(resultProgram.valid(), () -> resultProgram.diagnostics().toString());
        var result = new ArcVm().run(resultProgram.program(), "on:cast", Map.of(), new ArcVm.Limits(100, 0), new NoopHost());
        assertEquals(7L, result.value());

        var optionProgram = new ArcCompiler().compile("""
            spell X { on cast {
              let value = Some(3);
              return match value { Some(found) => found, None => 0 };
            } }
            """);
        assertTrue(optionProgram.valid(), () -> optionProgram.diagnostics().toString());
        var option = new ArcVm().run(optionProgram.program(), "on:cast", Map.of(), new ArcVm.Limits(100, 0), new NoopHost());
        assertEquals(3L, option.value());
    }

    @Test void questionOperatorUnwrapsOkAndPropagatesErr() {
        String source = "spell X { on cast { let value = spell.cast(\"TEST\", 1)?; return value; } }";
        var compiled = new ArcCompiler().compile(source);
        assertTrue(compiled.valid(), () -> compiled.diagnostics().toString());
        ArcVm.Host success = new VariantHost(ArcBytecode.Variant.of("Ok", 12L));
        var ok = new ArcVm().run(compiled.program(), "on:cast", Map.of(), new ArcVm.Limits(100, 0), success);
        assertEquals(12L, ok.value());
        ArcVm.Host failure = new VariantHost(ArcBytecode.Variant.of("Err", "NO_MANA"));
        var err = new ArcVm().run(compiled.program(), "on:cast", Map.of(), new ArcVm.Limits(100, 0), failure);
        assertInstanceOf(ArcBytecode.Variant.class, err.value());
        assertEquals("Err", ((ArcBytecode.Variant) err.value()).tag());
        assertEquals("NO_MANA", ((ArcBytecode.Variant) err.value()).value());
    }

    @Test void suspendsAndResumesWaitWithoutResettingState() {
        var compiled = new ArcCompiler().compile("spell X { on cast { let value = 4; wait 5t; return value + 2; } }");
        assertTrue(compiled.valid(), () -> compiled.diagnostics().toString());
        ArcVm vm = new ArcVm();
        var suspended = vm.run(compiled.program(), "on:cast", Map.of(), new ArcVm.Limits(100, 0), new NoopHost());
        assertEquals(ArcVm.Status.SUSPENDED, suspended.status());
        assertTrue(suspended.resumable());
        assertEquals(5L, suspended.resumeAfterTicks());
        var completed = vm.resume(compiled.program(), suspended.continuation(), new ArcVm.Limits(100, 0), new NoopHost());
        assertEquals(ArcVm.Status.COMPLETED, completed.status());
        assertEquals(6.0, completed.value());
        assertTrue(completed.fuelUsed() > suspended.fuelUsed());
    }

    @Test void waitCannotRefillFuelAcrossResumes() {
        var compiled = new ArcCompiler().compile("spell X { on cast { repeat 100 { wait 1t; } } }");
        assertTrue(compiled.valid(), () -> compiled.diagnostics().toString());
        ArcVm vm = new ArcVm();
        var result = vm.run(compiled.program(), "on:cast", Map.of(), new ArcVm.Limits(40, 0), new NoopHost());
        int resumes = 0;
        while (result.status() == ArcVm.Status.SUSPENDED && resumes++ < 100) {
            result = vm.resume(compiled.program(), result.continuation(), new ArcVm.Limits(40, 0), new NoopHost());
        }
        assertEquals(ArcVm.Status.TRAPPED, result.status());
        assertEquals("ARC-R3002", result.code());
        assertTrue(resumes < 100);
    }

    private static final class NoopHost implements ArcVm.Host {
        public Object invoke(String api, List<Object> arguments, List<String> argumentNames, ArcVm.Execution execution) { return 1L; }
        public Object member(Object receiver, String name) { return null; }
    }

    private record VariantHost(Object value) implements ArcVm.Host {
        public Object invoke(String api, List<Object> arguments, List<String> argumentNames, ArcVm.Execution execution) { return value; }
        public Object member(Object receiver, String name) { return null; }
    }
}
