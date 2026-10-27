# ArcSys language 2.0 alpha

ArcSys is a case-sensitive UTF-8 domain language. Source files use `.arc`. Player source cannot import Java classes or access reflection, files, processes, sockets, SQL, native code, threads or dynamic evaluation.

The alpha grammar implements spell declarations, `on cast`, `on pulse(Duration)`, typed and inferred `let`/`const`/`state`, blocks, `if`/`else`, bounded `repeat`, `for ... in`, `break`, `continue`, `return`, typed functions, literals, member access, calls, named arguments and arithmetic/logical expressions. `while`, unrestricted recursion and dynamic imports are rejected.

Operator precedence, high to low: member/call; unary `! -`; `* / %`; `+ -`; comparisons; equality; `&&`; `||`. Integer overflow, non-finite floats, division by zero, excessive nesting and oversized tokens are diagnostics or VM faults.

Important types are `Int`, `Float`, `Bool`, `String`, `Duration`, `Vec3`, `EntityRef`, `SpellRef`, `List<T>`, `Option<T>`, `Result<T>`, `Mana`, and `EffectPlan`. Host calls never return Bukkit objects.

Stable diagnostics use `ARC-[E|W]NNNN`, one-based line/column and a suggestion. Client diagnostics are advisory; Paper recompiles on validate and deploy.

Supported host namespaces initially include `mobs`, `target`, `self`, `spell`, `state`, `signal`, and rate-limited `log`. Every side-effecting call declares required capabilities. Capabilities come only from the authenticated player, permissions and validated circuit fragments.
