package net.monacraft.spellforge.arcsys.vm;

import net.monacraft.spellforge.arcsys.compiler.ArcCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArcProfilerTest {
    @Test void profilesOpcodesHostCallsAndSourceLines() {
        var compiled = new ArcCompiler().compile("""
            module profile.demo;
            spell X { on cast {
              let target = target.nearest(8);
              return target;
            } }
            """);
        assertTrue(compiled.valid(), () -> compiled.diagnostics().toString());
        ArcVm.Host delegate = new ArcVm.Host() {
            public Object invoke(String api, List<Object> args, List<String> names, ArcVm.Execution execution) { return "mob"; }
            public Object member(Object receiver, String name) { return null; }
        };
        var profiler = new ArcProfiler(compiled.program(), delegate);
        var result = new ArcVm().run(compiled.program(), "on:cast", Map.of(), new ArcVm.Limits(64, 0), profiler);
        assertEquals(ArcVm.Status.COMPLETED, result.status());
        var snapshot = profiler.snapshot();
        assertTrue(snapshot.instructions() > 0);
        assertEquals(1L, snapshot.hostCalls().get("target.nearest"));
        assertTrue(snapshot.sourceHits().keySet().stream().anyMatch(line -> line.module().equals("profile.demo")));
        assertTrue(snapshot.elapsedNanos() >= 0);
    }
}
