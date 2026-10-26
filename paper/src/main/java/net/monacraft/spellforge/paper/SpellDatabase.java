package net.monacraft.spellforge.paper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.monacraft.spellforge.model.SpellModel.Circuit;
import net.monacraft.spellforge.model.SpellModel.CircuitNode;
import net.monacraft.spellforge.model.SpellModel.FragmentInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

final class SpellDatabase implements AutoCloseable {
    private final Connection connection;
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>) (value, type, context) -> new com.google.gson.JsonPrimitive(value.toEpochMilli()))
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonDeserializer<Instant>) (json, type, context) -> Instant.ofEpochMilli(json.getAsLong()))
            .create();

    SpellDatabase(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        migrate();
        backfillEmbeddedFragments();
    }

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS fragment_issuance(
                  instance_uuid TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, fragment_id TEXT NOT NULL, level INTEGER NOT NULL,
                  issuer TEXT NOT NULL, source TEXT NOT NULL, issued_at INTEGER NOT NULL, revoked_at INTEGER
                )""");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS circuits(
                  circuit_uuid TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, name TEXT NOT NULL, revision INTEGER NOT NULL,
                  schema_version INTEGER NOT NULL, circuit_json TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS circuits_owner_idx ON circuits(owner_uuid, updated_at DESC)");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS upgrade_audit(
                  operation_uuid TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, instance_uuid TEXT NOT NULL,
                  from_level INTEGER NOT NULL, to_level INTEGER NOT NULL, success INTEGER NOT NULL,
                  cost_json TEXT NOT NULL, created_at INTEGER NOT NULL
                )""");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cast_audit(
                  cast_uuid TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, circuit_uuid TEXT NOT NULL,
                  result_code TEXT NOT NULL, mana_cost REAL NOT NULL, runtime_cost INTEGER NOT NULL,
                  started_at INTEGER NOT NULL, finished_at INTEGER
                )""");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS neutral_stones(
                  instance_uuid TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL,
                  circuit_uuid TEXT, circuit_revision INTEGER, circuit_json TEXT,
                  uses_remaining INTEGER NOT NULL, issuer TEXT NOT NULL,
                  issued_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS neutral_stones_owner_idx ON neutral_stones(owner_uuid, updated_at DESC)");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS stored_fragments(
                  instance_uuid TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, fragment_id TEXT NOT NULL,
                  level INTEGER NOT NULL, stored_at INTEGER NOT NULL,
                  FOREIGN KEY(instance_uuid) REFERENCES fragment_issuance(instance_uuid)
                )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS stored_fragments_owner_idx ON stored_fragments(owner_uuid, fragment_id, stored_at)");
            statement.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES(1, unixepoch())");
            statement.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES(2, unixepoch())");
        }
        ensureColumn("fragment_issuance", "consumed_circuit_uuid", "TEXT");
        ensureColumn("fragment_issuance", "consumed_at", "INTEGER");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES(3, unixepoch())");
            statement.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES(4, unixepoch())");
        }
    }

    private void ensureColumn(String table, String column, String definition) throws SQLException {
        boolean present = false;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) if (column.equalsIgnoreCase(result.getString("name"))) { present = true; break; }
        }
        if (!present) try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    synchronized void recordIssuance(FragmentInstance instance, String source) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO fragment_issuance(instance_uuid,owner_uuid,fragment_id,level,issuer,source,issued_at,revoked_at)
                VALUES(?,?,?,?,?,?,?,NULL)""")) {
            ps.setString(1, instance.instanceId().toString()); ps.setString(2, instance.ownerId().toString());
            ps.setString(3, instance.fragmentId()); ps.setInt(4, instance.level()); ps.setString(5, instance.issuer());
            ps.setString(6, source); ps.setLong(7, instance.issuedAt().toEpochMilli()); ps.executeUpdate();
        }
    }

    synchronized boolean isIssued(FragmentInstance instance) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT 1 FROM fragment_issuance f
                WHERE f.instance_uuid=? AND f.owner_uuid=? AND f.fragment_id=? AND f.level=?
                  AND f.revoked_at IS NULL AND f.consumed_at IS NULL
                  AND NOT EXISTS(SELECT 1 FROM stored_fragments s WHERE s.instance_uuid=f.instance_uuid)""")) {
            ps.setString(1, instance.instanceId().toString()); ps.setString(2, instance.ownerId().toString());
            ps.setString(3, instance.fragmentId()); ps.setInt(4, instance.level());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException ignored) { return false; }
    }

    synchronized List<Circuit> circuits(UUID ownerId) {
        List<Circuit> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT circuit_json FROM circuits WHERE owner_uuid=? ORDER BY updated_at DESC")) {
            ps.setString(1, ownerId.toString());
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(gson.fromJson(rs.getString(1), Circuit.class)); }
        } catch (SQLException error) { throw new IllegalStateException(error); }
        return result;
    }

    synchronized Optional<Circuit> circuit(UUID circuitId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT circuit_json FROM circuits WHERE circuit_uuid=?")) {
            ps.setString(1, circuitId.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(gson.fromJson(rs.getString(1), Circuit.class)) : Optional.empty(); }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    synchronized Optional<Circuit> activeCircuit(UUID ownerId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT circuit_json FROM circuits WHERE owner_uuid=? AND active=1 LIMIT 1")) {
            ps.setString(1, ownerId.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(gson.fromJson(rs.getString(1), Circuit.class)) : Optional.empty(); }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    synchronized boolean setCircuitActive(UUID circuitId, UUID ownerId, boolean active) {
        try {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT 1 FROM circuits WHERE circuit_uuid=? AND owner_uuid=?")) {
                    select.setString(1, circuitId.toString());
                    select.setString(2, ownerId.toString());
                    try (ResultSet result = select.executeQuery()) { exists = result.next(); }
                }
                if (!exists) {
                    connection.rollback();
                    return false;
                }
                if (active) {
                    try (PreparedStatement clear = connection.prepareStatement("UPDATE circuits SET active=0 WHERE owner_uuid=?")) {
                        clear.setString(1, ownerId.toString());
                        clear.executeUpdate();
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE circuits SET active=? WHERE circuit_uuid=? AND owner_uuid=?")) {
                    update.setInt(1, active ? 1 : 0);
                    update.setString(2, circuitId.toString());
                    update.setString(3, ownerId.toString());
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    synchronized Circuit save(Circuit circuit, long expectedRevision) {
        try {
            connection.setAutoCommit(false);
            try {
                Long actual = null;
                try (PreparedStatement select = connection.prepareStatement("SELECT revision FROM circuits WHERE circuit_uuid=?")) {
                    select.setString(1, circuit.circuitId().toString());
                    try (ResultSet rs = select.executeQuery()) { if (rs.next()) actual = rs.getLong(1); }
                }
                if (actual != null && actual != expectedRevision) throw new OptimisticConflict(actual);
                long revision = actual == null ? 1 : actual + 1;
                Circuit saved = new Circuit(circuit.circuitId(), circuit.ownerId(), circuit.name(), revision, circuit.schemaVersion(),
                        circuit.nodes(), circuit.edges(), circuit.createdAt(), java.time.Instant.now());
                try (PreparedStatement clear = connection.prepareStatement("UPDATE circuits SET active=0 WHERE owner_uuid=?")) {
                    clear.setString(1, circuit.ownerId().toString()); clear.executeUpdate();
                }
                try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO circuits(circuit_uuid,owner_uuid,name,revision,schema_version,circuit_json,active,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,1,?,?) ON CONFLICT(circuit_uuid) DO UPDATE SET name=excluded.name,revision=excluded.revision,
                    schema_version=excluded.schema_version,circuit_json=excluded.circuit_json,active=1,updated_at=excluded.updated_at""")) {
                    upsert.setString(1, saved.circuitId().toString()); upsert.setString(2, saved.ownerId().toString()); upsert.setString(3, saved.name());
                    upsert.setLong(4, saved.revision()); upsert.setInt(5, saved.schemaVersion()); upsert.setString(6, gson.toJson(saved));
                    upsert.setLong(7, saved.createdAt().toEpochMilli()); upsert.setLong(8, saved.updatedAt().toEpochMilli()); upsert.executeUpdate();
                }
                connection.commit(); return saved;
            } catch (RuntimeException | SQLException error) { connection.rollback(); throw error; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    synchronized Circuit saveEmbedded(Circuit circuit, long expectedRevision, Set<UUID> newlyEmbedded) {
        try {
            connection.setAutoCommit(false);
            try {
                Long actual = null;
                String actualOwner = null;
                try (PreparedStatement select = connection.prepareStatement("SELECT revision,owner_uuid FROM circuits WHERE circuit_uuid=?")) {
                    select.setString(1, circuit.circuitId().toString());
                    try (ResultSet result = select.executeQuery()) {
                        if (result.next()) { actual = result.getLong(1); actualOwner = result.getString(2); }
                    }
                }
                if (actual != null && !circuit.ownerId().toString().equals(actualOwner)) throw new OwnershipMismatch();
                if (actual != null && actual != expectedRevision) throw new OptimisticConflict(actual);
                long now = System.currentTimeMillis();
                for (UUID instanceId : newlyEmbedded) {
                    try (PreparedStatement consume = connection.prepareStatement("""
                            UPDATE fragment_issuance SET consumed_circuit_uuid=?,consumed_at=?
                            WHERE instance_uuid=? AND owner_uuid=? AND revoked_at IS NULL AND consumed_at IS NULL""")) {
                        consume.setString(1, circuit.circuitId().toString());
                        consume.setLong(2, now);
                        consume.setString(3, instanceId.toString());
                        consume.setString(4, circuit.ownerId().toString());
                        if (consume.executeUpdate() != 1) throw new FragmentUnavailable(instanceId);
                    }
                    try (PreparedStatement removeStored = connection.prepareStatement(
                            "DELETE FROM stored_fragments WHERE instance_uuid=? AND owner_uuid=?")) {
                        removeStored.setString(1, instanceId.toString());
                        removeStored.setString(2, circuit.ownerId().toString());
                        removeStored.executeUpdate();
                    }
                }
                long revision = actual == null ? 1 : actual + 1;
                Circuit saved = new Circuit(circuit.circuitId(), circuit.ownerId(), circuit.name(), revision,
                        circuit.schemaVersion(), circuit.nodes(), circuit.edges(), circuit.createdAt(), Instant.ofEpochMilli(now));
                try (PreparedStatement clear = connection.prepareStatement("UPDATE circuits SET active=0 WHERE owner_uuid=?")) {
                    clear.setString(1, circuit.ownerId().toString()); clear.executeUpdate();
                }
                try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO circuits(circuit_uuid,owner_uuid,name,revision,schema_version,circuit_json,active,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,1,?,?) ON CONFLICT(circuit_uuid) DO UPDATE SET name=excluded.name,revision=excluded.revision,
                    schema_version=excluded.schema_version,circuit_json=excluded.circuit_json,active=1,updated_at=excluded.updated_at""")) {
                    upsert.setString(1, saved.circuitId().toString()); upsert.setString(2, saved.ownerId().toString()); upsert.setString(3, saved.name());
                    upsert.setLong(4, saved.revision()); upsert.setInt(5, saved.schemaVersion()); upsert.setString(6, gson.toJson(saved));
                    upsert.setLong(7, saved.createdAt().toEpochMilli()); upsert.setLong(8, saved.updatedAt().toEpochMilli()); upsert.executeUpdate();
                }
                connection.commit();
                return saved;
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    synchronized StoreFragmentResult storeFragment(FragmentInstance instance) {
        try {
            connection.setAutoCommit(false);
            try {
                boolean available;
                try (PreparedStatement check = connection.prepareStatement("""
                        SELECT 1 FROM fragment_issuance
                        WHERE instance_uuid=? AND owner_uuid=? AND fragment_id=? AND level=?
                          AND revoked_at IS NULL AND consumed_at IS NULL""")) {
                    check.setString(1, instance.instanceId().toString());
                    check.setString(2, instance.ownerId().toString());
                    check.setString(3, instance.fragmentId());
                    check.setInt(4, instance.level());
                    try (ResultSet result = check.executeQuery()) { available = result.next(); }
                }
                if (!available) {
                    connection.rollback();
                    return new StoreFragmentResult(false, storedFragmentCount(instance.ownerId(), instance.fragmentId()), "UNAVAILABLE");
                }
                int count = storedFragmentCount(instance.ownerId(), instance.fragmentId());
                if (count >= 255) {
                    connection.rollback();
                    return new StoreFragmentResult(false, count, "LIMIT");
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO stored_fragments(instance_uuid,owner_uuid,fragment_id,level,stored_at)
                        VALUES(?,?,?,?,?)""")) {
                    insert.setString(1, instance.instanceId().toString());
                    insert.setString(2, instance.ownerId().toString());
                    insert.setString(3, instance.fragmentId());
                    insert.setInt(4, instance.level());
                    insert.setLong(5, System.currentTimeMillis());
                    insert.executeUpdate();
                }
                connection.commit();
                return new StoreFragmentResult(true, count + 1, "OK");
            } catch (SQLException error) {
                connection.rollback();
                if (error.getErrorCode() == 19) return new StoreFragmentResult(false,
                        storedFragmentCount(instance.ownerId(), instance.fragmentId()), "DUPLICATE");
                throw error;
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException error) { throw new IllegalStateException("Could not store fragment", error); }
    }

    synchronized List<net.monacraft.spellforge.model.SpellModel.OwnedFragment> storedFragments(UUID ownerId) {
        List<net.monacraft.spellforge.model.SpellModel.OwnedFragment> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT instance_uuid,fragment_id,level FROM stored_fragments
                WHERE owner_uuid=? ORDER BY stored_at,instance_uuid""")) {
            statement.setString(1, ownerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new net.monacraft.spellforge.model.SpellModel.OwnedFragment(
                        UUID.fromString(rows.getString(1)), rows.getString(2), rows.getInt(3)));
            }
            return result;
        } catch (SQLException error) { throw new IllegalStateException("Could not read stored fragments", error); }
    }

    synchronized Optional<net.monacraft.spellforge.model.SpellModel.OwnedFragment> storedFragment(UUID ownerId, UUID instanceId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT fragment_id,level FROM stored_fragments WHERE owner_uuid=? AND instance_uuid=?""")) {
            statement.setString(1, ownerId.toString());
            statement.setString(2, instanceId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(new net.monacraft.spellforge.model.SpellModel.OwnedFragment(
                        instanceId, row.getString(1), row.getInt(2))) : Optional.empty();
            }
        } catch (SQLException error) { throw new IllegalStateException("Could not read stored fragment", error); }
    }

    private int storedFragmentCount(UUID ownerId, String fragmentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM stored_fragments WHERE owner_uuid=? AND fragment_id=?")) {
            statement.setString(1, ownerId.toString());
            statement.setString(2, fragmentId);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
        }
    }

    private synchronized void backfillEmbeddedFragments() {
        try (PreparedStatement circuits = connection.prepareStatement("SELECT circuit_uuid,circuit_json FROM circuits");
             ResultSet result = circuits.executeQuery();
             PreparedStatement consume = connection.prepareStatement("""
                     UPDATE fragment_issuance SET consumed_circuit_uuid=?,consumed_at=?
                     WHERE instance_uuid=? AND revoked_at IS NULL AND consumed_at IS NULL""")) {
            long now = System.currentTimeMillis();
            while (result.next()) {
                String circuitId = result.getString(1);
                Circuit circuit = gson.fromJson(result.getString(2), Circuit.class);
                for (CircuitNode node : circuit.nodes()) {
                    consume.setString(1, circuitId);
                    consume.setLong(2, now);
                    consume.setString(3, node.fragmentInstanceId().toString());
                    consume.addBatch();
                }
            }
            consume.executeBatch();
            try (Statement cleanup = connection.createStatement()) {
                cleanup.executeUpdate("DELETE FROM stored_fragments WHERE instance_uuid IN (SELECT instance_uuid FROM fragment_issuance WHERE consumed_at IS NOT NULL)");
            }
        } catch (SQLException error) { throw new IllegalStateException("Could not migrate embedded fragments", error); }
    }

    synchronized boolean deleteCircuit(UUID circuitId, UUID ownerId) {
        try {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT 1 FROM circuits WHERE circuit_uuid=? AND owner_uuid=?")) {
                    select.setString(1, circuitId.toString());
                    select.setString(2, ownerId.toString());
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM circuits WHERE circuit_uuid=? AND owner_uuid=?")) {
                    delete.setString(1, circuitId.toString());
                    delete.setString(2, ownerId.toString());
                    if (delete.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    synchronized void registerNeutralStone(UUID instanceId, UUID ownerId, int uses, String issuer, long issuedAt) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO neutral_stones(instance_uuid,owner_uuid,circuit_uuid,circuit_revision,circuit_json,
                  uses_remaining,issuer,issued_at,updated_at) VALUES(?,?,NULL,NULL,NULL,?,?,?,?)""")) {
            statement.setString(1, instanceId.toString());
            statement.setString(2, ownerId.toString());
            statement.setInt(3, uses);
            statement.setString(4, issuer);
            statement.setLong(5, issuedAt);
            statement.setLong(6, issuedAt);
            statement.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException("Could not register neutral stone", error); }
    }

    synchronized Optional<NeutralStoneRecord> neutralStone(UUID instanceId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_uuid,circuit_json,uses_remaining,issuer,issued_at
                FROM neutral_stones WHERE instance_uuid=?""")) {
            statement.setString(1, instanceId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                String circuitJson = result.getString(2);
                return Optional.of(new NeutralStoneRecord(instanceId, UUID.fromString(result.getString(1)),
                        circuitJson == null ? null : gson.fromJson(circuitJson, Circuit.class),
                        result.getInt(3), result.getString(4), result.getLong(5)));
            }
        } catch (SQLException error) { throw new IllegalStateException("Could not read neutral stone", error); }
    }

    synchronized boolean bindNeutralStone(UUID instanceId, UUID ownerId, Circuit circuit) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE neutral_stones SET circuit_uuid=?,circuit_revision=?,circuit_json=?,updated_at=?
                WHERE instance_uuid=? AND owner_uuid=? AND circuit_uuid IS NULL AND uses_remaining>0""")) {
            statement.setString(1, circuit.circuitId().toString());
            statement.setLong(2, circuit.revision());
            statement.setString(3, gson.toJson(circuit));
            statement.setLong(4, System.currentTimeMillis());
            statement.setString(5, instanceId.toString());
            statement.setString(6, ownerId.toString());
            return statement.executeUpdate() == 1;
        } catch (SQLException error) { throw new IllegalStateException("Could not bind neutral stone", error); }
    }

    synchronized boolean consumeNeutralStone(UUID instanceId, UUID ownerId, int expectedUses) {
        if (expectedUses < 1) return false;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE neutral_stones SET uses_remaining=uses_remaining-1,updated_at=?
                WHERE instance_uuid=? AND owner_uuid=? AND uses_remaining=? AND circuit_uuid IS NOT NULL""")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, instanceId.toString());
            statement.setString(3, ownerId.toString());
            statement.setInt(4, expectedUses);
            return statement.executeUpdate() == 1;
        } catch (SQLException error) { throw new IllegalStateException("Could not consume neutral stone", error); }
    }

    synchronized void auditCast(UUID castId, UUID playerId, UUID circuitId, String result, double mana, int runtime, long startedAt, Long finishedAt) {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO cast_audit VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, castId.toString()); ps.setString(2, playerId.toString()); ps.setString(3, circuitId.toString());
            ps.setString(4, result); ps.setDouble(5, mana); ps.setInt(6, runtime); ps.setLong(7, startedAt);
            if (finishedAt == null) ps.setNull(8, Types.BIGINT); else ps.setLong(8, finishedAt); ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    synchronized void auditUpgrade(UUID operationId, UUID playerId, UUID instanceId, int from, int to, boolean success, String costJson) {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO upgrade_audit VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, operationId.toString()); ps.setString(2, playerId.toString()); ps.setString(3, instanceId.toString());
            ps.setInt(4, from); ps.setInt(5, to); ps.setInt(6, success ? 1 : 0); ps.setString(7, costJson); ps.setLong(8, System.currentTimeMillis()); ps.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException("Could not audit upgrade", error); }
    }

    synchronized String auditSummary(UUID playerId) {
        return "fragments=" + count("fragment_issuance", "owner_uuid", playerId) + ", circuits=" + count("circuits", "owner_uuid", playerId)
                + ", casts=" + count("cast_audit", "player_uuid", playerId) + ", upgrades=" + count("upgrade_audit", "player_uuid", playerId);
    }

    private long count(String table, String column, UUID value) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?")) {
            ps.setString(1, value.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        } catch (SQLException error) { return -1; }
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
    record NeutralStoneRecord(UUID instanceId, UUID ownerId, Circuit circuit, int usesRemaining, String issuer, long issuedAt) { }
    record StoreFragmentResult(boolean stored, int count, String code) { }
    static final class OptimisticConflict extends RuntimeException { final long revision; OptimisticConflict(long revision) { this.revision = revision; } }
    static final class FragmentUnavailable extends RuntimeException { final UUID instanceId; FragmentUnavailable(UUID instanceId) { this.instanceId = instanceId; } }
    static final class OwnershipMismatch extends RuntimeException { }
}
