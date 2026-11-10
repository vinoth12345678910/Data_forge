---
name: go-live-wizard
description: Rules for the go-live wizard stepper: typed confirmation, disabled red button, prod banner. Use when building the wizard.
---

# go-live-wizard

- A stepper with typed confirmation.
- The red button stays disabled until the database name and the row total both match what the user typed.
- A red banner for prod on every screen.
- The wizard calls the same server API as the CLI; the guards live in `modules/cleanup`, not in the UI.

The safety design is user-owned; do not add or relax a guard in the UI.
