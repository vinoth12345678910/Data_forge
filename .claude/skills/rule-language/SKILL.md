---
name: rule-language
description: Rule language grammar conventions, how rules compile to SQL, error message format. SKELETON: the design is user-owned.
---

# rule-language (skeleton)

**The rule language and the verify pass are user-owned design.** Stop and ask; do not design them.

Known from the plan:
- ANTLR grammar. Example: `orders.total = sum(order_items.price * order_items.qty)`.
- Rules drive derived columns, constraints and capacity limits during generation.
- The verify pass compiles each rule to SQL and runs it in the database. It must not trust the generator.
- Failure messages name the rule and the row.

TODO (user to supply): grammar conventions, the compile-to-SQL mapping, the error message format.
