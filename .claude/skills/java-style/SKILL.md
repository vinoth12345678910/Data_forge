---
name: java-style
description: Plain, human Java 21 style for Data Forge: short names, small classes, no clever abstractions. Use when writing or reviewing Java.
---

# java-style

- Plain Java 21. Short, clear names. Small classes, short methods.
- No clever abstractions. No interface with one implementation unless the plan says so.
- Prefer records and simple data classes. Prefer explicit loops over deep stream pipelines when the loop is clearer.
- Match the surrounding code: comment density, naming, idiom.
- Comments say why, not what. No dead code, no TODO without an owner.
- Generation code: obey `determinism-rules`.
- Use bash, not zsh, for generated files and loops.
