package net.monacraft.spellforge.paper;

import net.monacraft.spellforge.api.CircuitService;
import net.monacraft.spellforge.compiler.CircuitCompiler;
import net.monacraft.spellforge.model.SpellModel.Circuit;
import net.monacraft.spellforge.model.SpellModel.CompilationResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class DefaultCircuitService implements CircuitService {
    private final SpellDatabase database;
    private final CircuitCompiler compiler;

    DefaultCircuitService(SpellDatabase database, CircuitCompiler compiler) { this.database = database; this.compiler = compiler; }
    @Override public Optional<Circuit> getCircuit(UUID circuitId) { return database.circuit(circuitId); }
    @Override public List<Circuit> getCircuits(UUID ownerId) { return database.circuits(ownerId); }
    @Override public CompilationResult validate(Circuit circuit) { return compiler.compile(circuit); }
    @Override public CompilationResult compile(Circuit circuit) { return compiler.compile(circuit); }
    @Override public SaveResult save(Circuit circuit, long expectedRevision) {
        CompilationResult validation = compiler.compile(circuit);
        if (!validation.valid()) return new SaveResult(false, false, "VALIDATION_FAILED", circuit);
        try { Circuit saved = database.save(circuit, expectedRevision); return new SaveResult(true, false, "OK", saved); }
        catch (SpellDatabase.OptimisticConflict conflict) { return new SaveResult(false, true, "REVISION_CONFLICT:" + conflict.revision, circuit); }
    }
    @Override public boolean delete(UUID circuitId, UUID ownerId) { return database.deleteCircuit(circuitId, ownerId); }
    @Override public Optional<Circuit> getActiveCircuit(UUID ownerId) { return database.activeCircuit(ownerId); }
    @Override public boolean setActive(UUID circuitId, UUID ownerId, boolean active) {
        if (active) {
            Circuit circuit = database.circuit(circuitId).filter(value -> value.ownerId().equals(ownerId)).orElse(null);
            if (circuit == null || !compiler.compile(circuit).valid()) return false;
        }
        return database.setCircuitActive(circuitId, ownerId, active);
    }
}
