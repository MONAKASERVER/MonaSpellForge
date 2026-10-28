package net.monacraft.spellforge.arcsys.vm;

import net.monacraft.spellforge.arcsys.compiler.ArcBytecode.Instruction;
import net.monacraft.spellforge.arcsys.compiler.ArcBytecode.Op;
import net.monacraft.spellforge.arcsys.compiler.ArcBytecode.Program;
import net.monacraft.spellforge.arcsys.compiler.ArcBytecode.SourceSite;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic ArcVM profiler. It observes the existing debug hook and never
 * grants the program additional host capabilities.
 */
public final class ArcProfiler implements ArcVm.Host {
    public record SourceLine(String module, int line) { }

    public record Snapshot(long instructions, int maxStackDepth, int minimumFuelRemaining,
                           Map<Op, Long> opCounts, Map<String, Long> hostCalls,
                           Map<SourceLine, Long> sourceHits, long elapsedNanos) {
        public Snapshot {
            opCounts = Map.copyOf(opCounts);
            hostCalls = Map.copyOf(hostCalls);
            sourceHits = Map.copyOf(sourceHits);
        }
    }

    private final Program program;
    private final ArcVm.Host delegate;
    private final long startedAt = System.nanoTime();
    private final Map<Op, Long> opCounts = new LinkedHashMap<>();
    private final Map<String, Long> hostCalls = new LinkedHashMap<>();
    private final Map<SourceLine, Long> sourceHits = new LinkedHashMap<>();
    private long instructions;
    private int maxStackDepth;
    private int minimumFuelRemaining = Integer.MAX_VALUE;

    public ArcProfiler(Program program, ArcVm.Host delegate) {
        this.program = Objects.requireNonNull(program);
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public synchronized Object invoke(String api, List<Object> arguments, List<String> argumentNames,
                                      ArcVm.Execution execution) {
        hostCalls.merge(api, 1L, Long::sum);
        return delegate.invoke(api, arguments, argumentNames, execution);
    }

    @Override
    public Object member(Object receiver, String name) {
        return delegate.member(receiver, name);
    }

    @Override
    public synchronized ArcVm.DebugAction beforeInstruction(int pc, Instruction instruction,
                                                            Map<String, Object> variables,
                                                            int stackDepth, int fuelRemaining) {
        instructions++;
        opCounts.merge(instruction.op(), 1L, Long::sum);
        maxStackDepth = Math.max(maxStackDepth, stackDepth);
        minimumFuelRemaining = Math.min(minimumFuelRemaining, fuelRemaining);
        SourceSite site = program.sourceAt(pc);
        if (site != null) {
            sourceHits.merge(new SourceLine(site.module(), site.span().start().line()), 1L, Long::sum);
        }
        return delegate.beforeInstruction(pc, instruction, variables, stackDepth, fuelRemaining);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(instructions, maxStackDepth,
                minimumFuelRemaining == Integer.MAX_VALUE ? 0 : minimumFuelRemaining,
                opCounts, hostCalls, sourceHits, Math.max(0, System.nanoTime() - startedAt));
    }
}
