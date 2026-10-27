package net.monacraft.spellforge.arcsys.vm;

import net.monacraft.spellforge.arcsys.compiler.ArcBytecode.Program;

import java.util.*;
import java.util.function.Predicate;

/**
 * Single-threaded cooperative scheduler for suspended ArcVM activations.
 * Bukkit/Paper callers must invoke {@link #tick(long)} from the server thread.
 */
public final class ArcScheduler {
    public static final int DEFAULT_MAX_TASKS = 1_024;
    public static final int DEFAULT_MAX_RESUMES_PER_TICK = 128;

    public record TaskId(UUID value) {
        public TaskId { Objects.requireNonNull(value); }
    }

    public interface Listener {
        void completed(TaskId id, ArcVm.Result result);
        void trapped(TaskId id, ArcVm.Result result);
        default void cancelled(TaskId id, String reason) { }
    }

    private final ArcVm vm;
    private final int maxTasks;
    private final int maxResumesPerTick;
    private final Map<TaskId, Task> tasks = new HashMap<>();
    private final PriorityQueue<Task> due = new PriorityQueue<>(Comparator
            .comparingLong((Task task) -> task.dueTick).thenComparing(task -> task.id.value()));

    public ArcScheduler() { this(new ArcVm(), DEFAULT_MAX_TASKS, DEFAULT_MAX_RESUMES_PER_TICK); }

    public ArcScheduler(ArcVm vm, int maxTasks, int maxResumesPerTick) {
        this.vm = Objects.requireNonNull(vm);
        if (maxTasks < 1 || maxTasks > 65_536) throw new IllegalArgumentException("maxTasks");
        if (maxResumesPerTick < 1 || maxResumesPerTick > 4_096) throw new IllegalArgumentException("maxResumesPerTick");
        this.maxTasks = maxTasks;
        this.maxResumesPerTick = maxResumesPerTick;
    }

    public TaskId start(Program program, String entry, Map<String, Object> inputs, ArcVm.Limits limits,
                        ArcVm.Host host, long currentTick, Listener listener) {
        Objects.requireNonNull(program); Objects.requireNonNull(entry); Objects.requireNonNull(limits);
        Objects.requireNonNull(host); Objects.requireNonNull(listener);
        if (tasks.size() >= maxTasks) throw new IllegalStateException("ArcVM scheduler task limit reached");
        TaskId id = new TaskId(UUID.randomUUID());
        ArcVm.Result result = vm.run(program, entry, inputs, limits, host);
        accept(id, program, limits, host, listener, currentTick, result);
        return id;
    }

    /** Returns the number of continuations resumed on this tick. */
    public int tick(long currentTick) {
        int resumed = 0;
        while (resumed < maxResumesPerTick && !due.isEmpty() && due.peek().dueTick <= currentTick) {
            Task task = due.poll();
            if (tasks.remove(task.id) != task) continue;
            ArcVm.Result result = vm.resume(task.program, task.continuation, task.limits, task.host);
            accept(task.id, task.program, task.limits, task.host, task.listener, currentTick, result);
            resumed++;
        }
        return resumed;
    }

    public boolean cancel(TaskId id, String reason) {
        Task task = tasks.remove(id);
        if (task == null) return false;
        due.remove(task);
        task.listener.cancelled(id, reason == null ? "cancelled" : reason);
        return true;
    }

    public int cancelWhere(Predicate<TaskId> predicate, String reason) {
        List<TaskId> matching = tasks.keySet().stream().filter(predicate).toList();
        matching.forEach(id -> cancel(id, reason));
        return matching.size();
    }

    public int activeTasks() { return tasks.size(); }

    private void accept(TaskId id, Program program, ArcVm.Limits limits, ArcVm.Host host,
                        Listener listener, long currentTick, ArcVm.Result result) {
        switch (result.status()) {
            case COMPLETED -> listener.completed(id, result);
            case TRAPPED -> listener.trapped(id, result);
            case SUSPENDED -> {
                long dueTick;
                try { dueTick = Math.addExact(currentTick, result.resumeAfterTicks()); }
                catch (ArithmeticException overflow) {
                    listener.trapped(id, syntheticTrap(result, "ARC-R3027", "scheduler tick overflow"));
                    return;
                }
                Task task = new Task(id, program, result.continuation(), limits, host, listener, dueTick);
                tasks.put(id, task); due.add(task);
            }
        }
    }

    private static ArcVm.Result syntheticTrap(ArcVm.Result previous, String code, String message) {
        return new ArcVm.Result(ArcVm.Status.TRAPPED, null, previous.fuelUsed(), previous.effects(),
                code, message, null, 0);
    }

    private record Task(TaskId id, Program program, ArcVm.Continuation continuation, ArcVm.Limits limits,
                        ArcVm.Host host, Listener listener, long dueTick) { }
}
