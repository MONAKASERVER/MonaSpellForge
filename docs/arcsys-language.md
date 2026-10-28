# ArcSys 2.0 — Magic Programming Language

ArcSys is a case-sensitive UTF-8 domain-specific language for MonaSpellForge. A `.arc` program describes spell decisions; it does not receive Java, Bukkit or operating-system access. Paper recompiles every deployment and ArcVM executes verified bytecode under fuel, mana, effect, memory and scheduler limits.

## Design rules

1. Magic concepts are language types, not comments: `Mana`, `Duration`, `Distance`, `Percent`, `Entity`, `Location`.
2. Observation and effects are separate. Sensor APIs return bounded values; mutations become effect requests that Paper validates.
3. The server is authoritative. Fabric, Web and VS Code diagnostics improve editing but never grant capabilities.
4. Every loop, list, string, call stack, continuation and external effect has a hard limit.
5. Java classes, reflection, files, processes, sockets, SQL, native code, threads and dynamic `eval` are unavailable.

## Core types

Scalar types: `Int`, `Float`, `Number`, `Bool`, `String`.

Minecraft reference types: `Entity`, `Location`, `World`, `Vector`, `Block`, `Item`. These are opaque server-validated references, never Bukkit objects or unrestricted UUID handles.

Dimension types:

- `Duration`: `20t`, `5s`, `7d`
- `Distance`: `24b`
- `Mana`: `100mp`
- `Percent`: `35%`

Generic types: `List<T>`, `Option<T>`, `Result<T,E>`. `Some`, `None`, `Ok`, `Err`, exhaustive `match`, and `?` use the same generic type information.

Different dimensions cannot be added or compared accidentally. Existing unitless API arguments remain a warning during alpha migration; new programs should always use units.

## Variables and functions

```arc
fn low_health(target: Entity) -> Bool {
  return target.hp_ratio < 0.30;
}

effect fn strike(target: Entity, power: Number) -> Result<Any, String>
  uses [COMBAT_DAMAGE] {
  return combat.damage(target, power);
}

spell Judgment {
  state executions: Int = 0 persist(player, ttl: 7d);

  on cast {
    let mut remaining: Int = 3;
    let targets: List<Entity> = mobs.hostile(radius: 24b, limit: 8);
    for enemy in targets {
      if (low_health(enemy)) {
        strike(enemy, 80)?;
        executions += 1;
        remaining -= 1;
      }
    }
  }
}
```

`let` and `const` are immutable. `let mut` and `state` are assignable. Undeclared names, incorrect function arity, incorrect argument types, invalid return values and assignment to immutable bindings are compile errors.

## Runtime context

Handlers and ARCSYS_CORE executions expose typed, read-only context values:

- `caster`, `player`, `target`: `Entity`
- `target_location`, `location`: `Location`
- `world`: `World`
- `mana_available`: `Mana`
- `number_in`: `Float`
- `boolean_in`: `Bool`
- `entity_in`: `Entity`
- `location_in`: `Location`
- `trigger_type`, `circuit_id`, `node_id`, `execution_id`: `String`

Possessing a reference does not authorize an effect. Paper still checks owner, PvP, WorldGuard, MythicMobs, distance, world and active execution context.

## Standard magic APIs

The authoritative catalog is `ArcMagicApiCatalog`. It defines each API's parameter names and types, return type, Capability, fuel, base mana, effect classification and documentation. The compiler consumes it directly. The VS Code catalog is generated from it using:

```powershell
.\gradlew.bat :arcsys-language:exportMagicApiCatalog
```

Namespaces currently include:

- sensing: `mobs`, `entities`, `target`, `entity`, `raycast`, `blocks`, `items`
- combat: `combat.damage`, `combat.apply`, `combat.can_attack`
- casting: `spell.cast`, `spell.effect`
- mana and state: `mana`, `state`, `pdc`
- visual effects: `world.particle`, `particles`, `display`, `virtual_structure`
- control: `time`, `event`, `random`, `math`
- operator bridge: allowlisted `skript.emit`

Effectful APIs return `Result` and are revalidated by Paper before anything changes in the world. A pure function cannot call them. An `effect fn` must declare the matching Capability through `uses [...]`.

## Diagnostics and tooling

Parser diagnostics use `ARC-E...`; static type diagnostics use `ARC-T...`; migration warnings use `ARC-TW...`. Paper's compiler diagnostics are final. VS Code completion and Hover display the generated signature, base MP, Capability and read-only/Effect classification.

The language server remains advisory. Deploy uses VS Code → authenticated localhost Fabric bridge → plugin message → Paper. VS Code never connects directly to Paper.

## Alpha limitations

User-defined `struct`, `enum`, `trait`, `impl`, cross-file modules and imports are reserved syntax, not a completed nominal type/linking system. `atomic` and `transaction` do not yet roll back external Bukkit effects. Live multiplayer load, proxy and Bedrock client verification remain release blockers; build success is not real-server verification.
