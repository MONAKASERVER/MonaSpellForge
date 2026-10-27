package net.monacraft.spellforge.paper;

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
}
