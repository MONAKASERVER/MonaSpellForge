package net.monacraft.spellforge.api;

import net.monacraft.spellforge.model.SpellModel.Circuit;
import net.monacraft.spellforge.model.SpellModel.CompilationResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CircuitService {
    Optional<Circuit> getCircuit(UUID circuitId);
    List<Circuit> getCircuits(UUID ownerId);
    CompilationResult validate(Circuit circuit);
    CompilationResult compile(Circuit circuit);
    SaveResult save(Circuit circuit, long expectedRevision);
    boolean delete(UUID circuitId, UUID ownerId);
    default Optional<Circuit> getActiveCircuit(UUID ownerId) { return Optional.empty(); }
    default boolean setActive(UUID circuitId, UUID ownerId, boolean active) { return false; }
    record SaveResult(boolean saved, boolean conflict, String message, Circuit circuit) {}
}
