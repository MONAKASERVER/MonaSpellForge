package net.monacraft.spellforge.paper;

import net.monacraft.spellforge.api.CircuitService;
import net.monacraft.spellforge.api.FragmentService;
import net.monacraft.spellforge.model.SpellModel.Circuit;
import net.monacraft.spellforge.model.SpellModel.CircuitNode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Saves a circuit and permanently embeds only newly attached fragment instances. */
final class CircuitEmbeddingService {
    private final CircuitService circuits;
    private final DefaultFragmentService fragments;
    private final SpellDatabase database;

    CircuitEmbeddingService(CircuitService circuits, DefaultFragmentService fragments, SpellDatabase database) {
        this.circuits = circuits;
        this.fragments = fragments;
        this.database = database;
    }

    CircuitService.SaveResult save(Player player, Circuit circuit, long expectedRevision) {
        // Editors must be able to persist an incomplete circuit as a draft. This is
        // especially important for ARCSYS_CORE: its server-side binding can only be
        // configured after the node has a stable, persisted UUID. Activation and
        // execution still compile the circuit and therefore reject invalid drafts.
        return saveInternal(player, circuit, expectedRevision, true);
    }

    CircuitService.SaveResult stow(Player player, Circuit circuit, long expectedRevision, UUID nodeId) {
        Circuit previous = circuits.getCircuit(circuit.circuitId()).orElse(null);
        if (previous == null || !previous.ownerId().equals(player.getUniqueId())) return failure("OWNER_MISMATCH", circuit);
        boolean existed = previous.nodes().stream().anyMatch(node -> node.nodeId().equals(nodeId));
        boolean removed = circuit.nodes().stream().noneMatch(node -> node.nodeId().equals(nodeId));
        if (!existed || !removed || circuit.nodes().size() > 25 || circuit.edges().size() > 64) {
            return failure("INVALID_STOW", circuit);
        }
        return saveInternal(player, circuit, expectedRevision, true);
    }

    private CircuitService.SaveResult saveInternal(Player player, Circuit circuit, long expectedRevision, boolean allowDraft) {
        if (!circuit.ownerId().equals(player.getUniqueId())) return failure("OWNER_MISMATCH", circuit);
        var validation = circuits.validate(circuit);
        if (!allowDraft && !validation.valid()) return failure("VALIDATION_FAILED", circuit);

        Circuit previous = circuits.getCircuit(circuit.circuitId()).orElse(null);
        if (previous != null && !previous.ownerId().equals(player.getUniqueId())) return failure("OWNER_MISMATCH", circuit);
        Map<UUID, CircuitNode> embedded = new HashMap<>();
        if (previous != null) for (CircuitNode node : previous.nodes()) embedded.put(node.fragmentInstanceId(), node);

        Set<UUID> seen = new HashSet<>();
        Set<UUID> newlyEmbedded = new HashSet<>();
        for (CircuitNode node : circuit.nodes()) {
            if (!seen.add(node.fragmentInstanceId())) return failure("DUPLICATE_FRAGMENT", circuit);
            CircuitNode old = embedded.get(node.fragmentInstanceId());
            if (old != null) {
                if (!old.fragmentId().equals(node.fragmentId()) || old.level() != node.level()) return failure("FRAGMENT_TAMPERED", circuit);
            } else newlyEmbedded.add(node.fragmentInstanceId());
        }
        List<CircuitNode> returnedNodes = embedded.values().stream()
                .filter(node -> !seen.contains(node.fragmentInstanceId()))
                .toList();

        Map<UUID, FragmentService.ValidationResult> inventory = new HashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            FragmentService.ValidationResult result = fragments.validateItem(item, player);
            if (result.valid()) inventory.putIfAbsent(result.instance().instanceId(), result);
        }
        FragmentService.ValidationResult cursor = fragments.validateItem(player.getItemOnCursor(), player);
        if (cursor.valid()) inventory.putIfAbsent(cursor.instance().instanceId(), cursor);
        for (CircuitNode node : circuit.nodes()) {
            if (!newlyEmbedded.contains(node.fragmentInstanceId())) continue;
            FragmentService.ValidationResult item = inventory.get(node.fragmentInstanceId());
            var stored = database.storedFragment(player.getUniqueId(), node.fragmentInstanceId()).orElse(null);
            boolean inventoryMatches = item != null && item.instance().fragmentId().equals(node.fragmentId()) && item.instance().level() == node.level();
            boolean storageMatches = stored != null && stored.fragmentId().equals(node.fragmentId()) && stored.level() == node.level();
            if (!inventoryMatches && !storageMatches) {
                return failure("MISSING_FRAGMENT", circuit);
            }
        }

        try {
            Circuit saved = database.saveEmbedded(circuit, expectedRevision, newlyEmbedded, returnedNodes);
            // Invalid drafts are persisted for editing, but must never remain the
            // active spell after an edit made them incomplete.
            if (!validation.valid()) database.setCircuitActive(saved.circuitId(), player.getUniqueId(), false);
            removeEmbeddedItems(player, newlyEmbedded);
            return new CircuitService.SaveResult(true, false, "OK", saved);
        } catch (SpellDatabase.OptimisticConflict conflict) {
            return new CircuitService.SaveResult(false, true, "REVISION_CONFLICT:" + conflict.revision, circuit);
        } catch (SpellDatabase.FragmentUnavailable unavailable) {
            return failure("MISSING_FRAGMENT", circuit);
        } catch (SpellDatabase.FragmentStorageFull full) {
            return failure("FRAGMENT_STORAGE_FULL:" + full.fragmentId, circuit);
        } catch (SpellDatabase.OwnershipMismatch mismatch) {
            return failure("OWNER_MISMATCH", circuit);
        }
    }

    private void removeEmbeddedItems(Player player, Set<UUID> instances) {
        if (instances.isEmpty()) return;
        List<Integer> clear = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            UUID instance = fragments.instanceIdUnchecked(contents[slot]);
            if (instance != null && instances.contains(instance)) clear.add(slot);
        }
        for (int slot : clear) player.getInventory().setItem(slot, new ItemStack(Material.AIR));
        UUID cursor = fragments.instanceIdUnchecked(player.getItemOnCursor());
        if (cursor != null && instances.contains(cursor)) player.setItemOnCursor(new ItemStack(Material.AIR));
    }

    private static CircuitService.SaveResult failure(String message, Circuit circuit) {
        return new CircuitService.SaveResult(false, false, message, circuit);
    }
}
