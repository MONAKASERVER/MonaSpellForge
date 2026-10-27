package net.monacraft.spellforge.arcsys.vm;

import net.monacraft.spellforge.arcsys.compiler.ArcCompiler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArcSchedulerTest {
    @Test void resumesOnlyWhenDueAndCompletesOnServerTick() {
        var compiled = new ArcCompiler().compile("spell X { on cast { wait 3t; return 9; } }");
        assertTrue(compiled.valid(), () -> compiled.diagnostics().toString());
        ArcScheduler scheduler = new ArcScheduler(new ArcVm(), 8, 2);
        RecordingListener listener = new RecordingListener();
        scheduler.start(compiled.program(), "on:cast", Map.of(), new ArcVm.Limits(100, 0), new Host(), 10, listener);
        assertEquals(1, scheduler.activeTasks());
        assertEquals(0, scheduler.tick(12));
        assertEquals(1, scheduler.tick(13));
        assertEquals(0, scheduler.activeTasks());
        assertEquals(9L, listener.completed.getFirst().value());
    }

    @Test void enforcesPerTickResumeLimitAndCancellation() {
        var compiled = new ArcCompiler().compile("spell X { on cast { wait 1t; } }");
        ArcScheduler scheduler = new ArcScheduler(new ArcVm(), 8, 1);
        RecordingListener first = new RecordingListener(), second = new RecordingListener();
        scheduler.start(compiled.program(), "on:cast", Map.of(), new ArcVm.Limits(100, 0), new Host(), 0, first);
        scheduler.start(compiled.program(), "on:cast", Map.of(), new ArcVm.Limits(100, 0), new Host(), 0, second);
        assertEquals(1, scheduler.tick(1));
        assertEquals(1, scheduler.activeTasks());
        assertEquals(1, scheduler.cancelWhere(id -> true, "logout"));
        assertEquals(1, first.cancelled.size() + second.cancelled.size());
    }

    @Test void loadTestBoundsOneThousandConcurrentContinuations() {
        var compiled = new ArcCompiler().compile("spell X { on cast { wait 1t; return 1; } }");
        assertTrue(compiled.valid(), () -> compiled.diagnostics().toString());
        ArcScheduler scheduler = new ArcScheduler(new ArcVm(), 1_024, 128);
        RecordingListener listener = new RecordingListener();
        for (int i = 0; i < 1_000; i++)
            scheduler.start(compiled.program(), "on:cast", Map.of(), new ArcVm.Limits(32, 0), new Host(), 0, listener);
        assertEquals(1_000, scheduler.activeTasks());
        int total = 0;
        for (int tick = 1; tick <= 8; tick++) {
            int resumed = scheduler.tick(tick); assertTrue(resumed <= 128); total += resumed;
        }
        assertEquals(1_000, total); assertEquals(0, scheduler.activeTasks()); assertEquals(1_000, listener.completed.size());
    }

    private static final class RecordingListener implements ArcScheduler.Listener {
        final List<ArcVm.Result> completed = new ArrayList<>();
        final List<ArcVm.Result> trapped = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        public void completed(ArcScheduler.TaskId id, ArcVm.Result result) { completed.add(result); }
        public void trapped(ArcScheduler.TaskId id, ArcVm.Result result) { trapped.add(result); }
        public void cancelled(ArcScheduler.TaskId id, String reason) { cancelled.add(reason); }
    }

    private static final class Host implements ArcVm.Host {
        public Object invoke(String api, List<Object> arguments, List<String> names, ArcVm.Execution execution) { return true; }
        public Object member(Object receiver, String name) { return null; }
    }
}
