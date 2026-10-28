package net.monacraft.spellforge.paper;

import net.monacraft.spellforge.catalog.FragmentCatalog;
import net.monacraft.spellforge.compiler.CircuitCompiler;
import net.monacraft.spellforge.model.SpellModel.Circuit;
import net.monacraft.spellforge.model.SpellModel.CircuitNode;
import net.monacraft.spellforge.model.SpellModel.FragmentInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SpellDatabaseTest {
    @TempDir Path temp;
    @Test void persistsCircuitAndEnforcesRevision() throws Exception {
        UUID owner=UUID.randomUUID();Circuit input=Circuit.empty(owner);
        try(SpellDatabase database=new SpellDatabase(temp.resolve("test.sqlite"))){
            Circuit saved=database.save(input,0);
            assertEquals(1,saved.revision());assertEquals(owner,database.activeCircuit(owner).orElseThrow().ownerId());
            assertThrows(SpellDatabase.OptimisticConflict.class,()->database.save(saved,0));
        }
    }

    @Test void deletesOnlyOwnedCircuitAndLeavesSpellsDisabledWhenActiveCircuitIsDeleted() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        try (SpellDatabase database = new SpellDatabase(temp.resolve("delete.sqlite"))) {
            Circuit first = database.save(Circuit.empty(owner), 0);
            Circuit second = database.save(Circuit.empty(owner), 0);
            assertEquals(second.circuitId(), database.activeCircuit(owner).orElseThrow().circuitId());
            assertFalse(database.deleteCircuit(second.circuitId(), stranger));
            assertTrue(database.circuit(second.circuitId()).isPresent());
            assertTrue(database.deleteCircuit(second.circuitId(), owner));
            assertTrue(database.circuit(second.circuitId()).isEmpty());
            assertTrue(database.activeCircuit(owner).isEmpty());
        }
    }

    @Test void activeCircuitCanBeDisabledAndSwitchedExclusively() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        try (SpellDatabase database = new SpellDatabase(temp.resolve("active-toggle.sqlite"))) {
            Circuit first = database.save(Circuit.empty(owner), 0);
            Circuit second = database.save(Circuit.empty(owner), 0);
            assertEquals(second.circuitId(), database.activeCircuit(owner).orElseThrow().circuitId());
            assertFalse(database.setCircuitActive(first.circuitId(), stranger, true));
            assertTrue(database.setCircuitActive(second.circuitId(), owner, false));
            assertTrue(database.activeCircuit(owner).isEmpty());
            assertTrue(database.setCircuitActive(first.circuitId(), owner, true));
            assertEquals(first.circuitId(), database.activeCircuit(owner).orElseThrow().circuitId());
            assertTrue(database.setCircuitActive(second.circuitId(), owner, true));
            assertEquals(second.circuitId(), database.activeCircuit(owner).orElseThrow().circuitId());
        }
    }

    @Test void circuitServiceRefusesToActivateAnInvalidDraft() throws Exception {
        UUID owner = UUID.randomUUID();
        try (SpellDatabase database = new SpellDatabase(temp.resolve("invalid-draft.sqlite"))) {
            Circuit draft = database.save(Circuit.empty(owner), 0);
            assertTrue(database.setCircuitActive(draft.circuitId(), owner, false));
            DefaultCircuitService service = new DefaultCircuitService(database,
                    new CircuitCompiler(FragmentCatalog.defaults()));
            assertFalse(service.setActive(draft.circuitId(), owner, true));
            assertTrue(database.activeCircuit(owner).isEmpty());
        }
    }

    @Test void neutralStoneBindingAndUseAreOwnerCheckedAndAtomic() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID stone = UUID.randomUUID();
        try (SpellDatabase database = new SpellDatabase(temp.resolve("stone.sqlite"))) {
            Circuit circuit = database.save(Circuit.empty(owner), 0);
            database.registerNeutralStone(stone, owner, 5, "test", 1234L);
            assertTrue(database.neutralStone(stone).orElseThrow().circuit() == null);
            assertFalse(database.bindNeutralStone(stone, stranger, circuit));
            assertTrue(database.bindNeutralStone(stone, owner, circuit));
            assertFalse(database.bindNeutralStone(stone, owner, circuit));
            assertFalse(database.consumeNeutralStone(stone, stranger, 5));
            assertTrue(database.consumeNeutralStone(stone, owner, 5));
            assertFalse(database.consumeNeutralStone(stone, owner, 5));
            assertEquals(4, database.neutralStone(stone).orElseThrow().usesRemaining());
        }
    }

    @Test void neutralStoneCustodyCanTransferOnceWithoutWeakeningTheLedger() throws Exception {
        UUID original = UUID.randomUUID(), recipient = UUID.randomUUID(), attacker = UUID.randomUUID();
        UUID stone = UUID.randomUUID();
        try (SpellDatabase database = new SpellDatabase(temp.resolve("stone-transfer.sqlite"))) {
            database.registerNeutralStone(stone, original, 5, "test", 1234L);
            assertTrue(database.transferNeutralStone(stone, original, recipient, 5));
            assertEquals(recipient, database.neutralStone(stone).orElseThrow().ownerId());
            assertFalse(database.transferNeutralStone(stone, original, attacker, 5),
                    "an old copied item cannot transfer custody after the ledger moved");
            Circuit recipientCircuit = database.save(Circuit.empty(recipient), 0);
            assertTrue(database.bindNeutralStone(stone, recipient, recipientCircuit));
            assertFalse(database.consumeNeutralStone(stone, original, 5));
            assertTrue(database.consumeNeutralStone(stone, recipient, 5));
        }
    }

    @Test void recoversSignedNeutralStoneOnlyWhenLedgerRowIsMissing() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stone = UUID.randomUUID();
        try (SpellDatabase database = new SpellDatabase(temp.resolve("stone-recovery.sqlite"))) {
            assertTrue(database.recoverSignedNeutralStone(stone, owner, 5, 1234L, null));
            SpellDatabase.NeutralStoneRecord recovered = database.neutralStone(stone).orElseThrow();
            assertEquals(owner, recovered.ownerId());
            assertEquals(5, recovered.usesRemaining());
            assertNull(recovered.circuit());
            assertTrue(database.recoverSignedNeutralStone(stone, owner, 5, 1234L, null));
        }
    }

    @Test void storedFragmentIsConsumedAtomicallyWhenEmbedded() throws Exception {
        UUID owner=UUID.randomUUID(),instanceId=UUID.randomUUID();
        FragmentInstance instance=new FragmentInstance(instanceId,"FIRE",1,owner,"test",Instant.now());
        try(SpellDatabase database=new SpellDatabase(temp.resolve("fragments.sqlite"))){
            database.recordIssuance(instance,"test");
            assertTrue(database.storeFragment(instance).stored());
            assertEquals(1,database.storedFragments(owner).size());
            assertFalse(database.isIssued(instance),"stored physical duplicates must no longer validate");
            Circuit empty=Circuit.empty(owner);
            CircuitNode node=new CircuitNode(UUID.randomUUID(),0,instanceId,"FIRE",1,Map.of(),0,0);
            Circuit circuit=new Circuit(empty.circuitId(),owner,"embedded",0,1,List.of(node),List.of(),empty.createdAt(),empty.updatedAt());
            Circuit saved=database.saveEmbedded(circuit,0,Set.of(instanceId),List.of());
            assertEquals(1,saved.revision());
            assertTrue(database.storedFragments(owner).isEmpty());
            Circuit stowedInput=new Circuit(saved.circuitId(),owner,"embedded",saved.revision(),1,List.of(),List.of(),saved.createdAt(),saved.updatedAt());
            Circuit stowed=database.saveEmbedded(stowedInput,saved.revision(),Set.of(),List.of(node));
            assertEquals(2,stowed.revision());
            assertEquals(instanceId,database.storedFragment(owner,instanceId).orElseThrow().instanceId());
            Circuit reembedded=database.saveEmbedded(new Circuit(stowed.circuitId(),owner,"embedded",stowed.revision(),1,
                    List.of(node),List.of(),stowed.createdAt(),stowed.updatedAt()),stowed.revision(),Set.of(instanceId),List.of());
            assertEquals(3,reembedded.revision());
            assertTrue(database.storedFragments(owner).isEmpty());
        }
    }

    @Test void arcDeployCreatesImmutableRevisionsAndRejectsStaleBase() throws Exception {
        UUID owner = UUID.randomUUID();
        try (SpellDatabase database = new SpellDatabase(temp.resolve("arc.sqlite"))) {
            byte[] firstCode = {1, 2, 3};
            var first = database.deployArcSpell(owner, null, 0, "Example", "spell Example {}", "hash-1", firstCode, "math", "");
            assertEquals(1, first.revision());
            assertEquals("spell Example {}", database.arcSpell(first.spellId(), owner).orElseThrow().source());
            var second = database.deployArcSpell(owner, first.spellId(), 1, "Example", "spell Example { on cast {} }", "hash-2", new byte[]{4}, "math", "");
            assertEquals(2, second.revision());
            assertThrows(SpellDatabase.OptimisticConflict.class, () -> database.deployArcSpell(owner, first.spellId(), 1,
                    "Example", "stale", "hash-stale", new byte[]{5}, "math", ""));
            assertEquals(2, database.arcSpell(first.spellId(), owner).orElseThrow().revision());
            assertEquals(2, database.arcSpells(owner).getFirst().revision());
        }
    }

    @Test void persistentArcStateHonorsTypeTtlMigrationAndPlayerErasure() throws Exception {
        UUID owner = UUID.randomUUID(), spell = UUID.randomUUID();
        long now = System.currentTimeMillis();
        try (SpellDatabase database = new SpellDatabase(temp.resolve("arc-state.sqlite"))) {
            database.putArcState(owner, spell, "hits", "PLAYER", 12L, 1, now + 10_000);
            var first = database.arcState(owner, spell, "hits", "PLAYER", now).orElseThrow();
            assertEquals("INT", first.type()); assertEquals(12L, first.value()); assertEquals(1, first.schemaVersion());
            database.putArcState(owner, spell, "hits", "PLAYER", 14L, 2, now + 20_000);
            assertEquals(2, database.arcState(owner, spell, "hits", "PLAYER", now).orElseThrow().schemaVersion());
            database.putArcState(owner, spell, "expired", "SPELL", true, 1, now + 30_000);
            assertTrue(database.arcState(owner, spell, "expired", "SPELL", now + 30_001).isEmpty());
            database.putArcState(owner, spell, "name", "SPELL", "arc", 1, null);
            assertEquals(2, database.eraseArcStateOwner(owner));
            assertTrue(database.arcState(owner, spell, "hits", "PLAYER", now).isEmpty());
        }
    }

    @Test void arcCoreBindingIsOwnerScopedRevisionPinnedAndPrunedWithNode() throws Exception {
        UUID owner = UUID.randomUUID(), instance = UUID.randomUUID(), nodeId = UUID.randomUUID();
        try (SpellDatabase database = new SpellDatabase(temp.resolve("arc-core.sqlite"))) {
            Circuit empty = Circuit.empty(owner);
            CircuitNode core = new CircuitNode(nodeId, 0, instance, "ARCSYS_CORE", 3, Map.of(), 0, 0);
            Circuit saved = database.save(new Circuit(empty.circuitId(), owner, "core", 0, 1,
                    List.of(core), List.of(), empty.createdAt(), empty.updatedAt()), 0);
            var rev1 = database.deployArcSpell(owner, null, 0, "CoreLogic", "spell CoreLogic { on cast {} }",
                    "hash-1", new byte[]{1}, "", "");
            database.deployArcSpell(owner, rev1.spellId(), 1, "CoreLogic", "spell CoreLogic { on cast { let x = 1; } }",
                    "hash-2", new byte[]{2}, "", "");
            var binding = database.saveArcCoreBinding(new SpellDatabase.ArcCoreBinding(UUID.randomUUID(), owner,
                    saved.circuitId(), nodeId, instance, rev1.spellId(), 1L, "PINNED", 1, 0, 0));
            assertEquals(1, database.arcCoreBinding(saved.circuitId(), nodeId).orElseThrow().pinnedRevision());
            assertEquals("hash-1", database.arcSpell(binding.spellId(), owner, binding.pinnedRevision()).orElseThrow().sourceHash());
            Circuit withoutCore = new Circuit(saved.circuitId(), owner, saved.name(), saved.revision(), 1,
                    List.of(), List.of(), saved.createdAt(), saved.updatedAt());
            database.save(withoutCore, saved.revision());
            assertTrue(database.arcCoreBinding(saved.circuitId(), nodeId).isEmpty());
        }
    }
}
