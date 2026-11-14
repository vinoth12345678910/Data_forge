# Target structure, agents and skills

This is the single source for repo layout; it wins over any other file.

Status: the skeleton, `golden/` schemas, `CLAUDE.md` and `.claude/` (agents, skills, hooks) exist. `packs/`, `ui/` and most module code are still empty; they arrive phase by phase. This file has no week-wise plan (the plan is phase-wise); build order stays in `docs/PLAN.md`.

## Monorepo skeleton

```
data-forge/
├── CLAUDE.md                  # hard rules, commands, style
├── README.md
├── LICENSE
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml  # one place for all versions
├── .github/workflows/         # ci.yml, no-network.yml, release.yml
├── .claude/
│   ├── settings.json          # permissions + hooks
│   ├── agents/
│   ├── skills/
│   └── commands/              # /gate, /status, /measure
├── docs/                      # SPEC, PLAN, STATUS, DECISIONS, CONFIG, RULES, SAFETY
├── modules/                   # backend (Java 21)
│   ├── core/                  # schema graph, spec model, config (forge.yml)
│   ├── schema/                # JDBC reader, DDL reader
│   ├── gen/                   # planner, seeded random, generators, distributions
│   ├── rules/                 # ANTLR grammar, rule compiler, verify pass, state machines
│   ├── load/                  # postgres, mysql, sqlite, file outputs
│   ├── cleanup/               # manifest, cleanup, classify, readiness, guards
│   ├── cli/                   # Picocli commands (incl. `forge mcp`, a stdio adapter: Mcp.java, McpCmd.java)
│   ├── service/               # shared service layer (planned): one API used by CLI, MCP, TUI and HTTP
│   ├── server/                # Javalin JSON API (127.0.0.1, token), polling, serves ui/dist
│   ├── mcp/                   # MCP server over the service layer (planned)
│   ├── tui/                   # terminal UI: a numbered menu over the local API (forge.tui.Tui)
│   ├── junit/                 # JUnit 5 + Testcontainers extension
│   └── testkit/               # golden schemas, test base classes, assertions
├── packs/                     # generic, ecommerce, events, saas, banking (yaml)
├── golden/                    # events-merch, ecommerce, saas-multitenant (DDL + expected)
├── ui/                        # React + Vite + TS, web UI lite (5 screens; built by Antigravity)
│   ├── src/{screens,components,api,state,styles}
│   ├── e2e/                   # Playwright
│   └── design/                # tokens, brief, reference screenshots
└── scripts/                   # measure.sh, nonet.sh, package.sh (jlink)
```

Why:
- Ten backend modules until Phase 5 ends; then `service`, `mcp` and `tui` are added (planned) and nothing else. `modules/tui` and `ui/` are the only paths the Antigravity sessions may edit (plus `docs/ui/`). Each maps to a phase; a session touches one or two.
- `cleanup` is its own module so the safety reviewer can audit deletion code alone.
- `golden/` and `packs/` at the root because tests, CLI and UI all read them.

## Agents (`.claude/agents/`)

| Agent | Job | Access |
|---|---|---|
| `planner` | Reads SPEC and PLAN, plans one feature, lists decisions for the user. Never writes code | Read-only |
| `java-builder` | Implements backend tasks in plain Java style, one module at a time | Read and write |
| `test-first` | Writes the gate test before any build, runs it, shows it fails | Read and write (tests only) |
| `dialect-specialist` | Postgres COPY, MySQL, SQLite loader and cycle-handling differences | Read and write |
| `determinism-auditor` | Hunts HashMap order, unseeded Random, time, locale, parallel streams | Read-only |
| `safety-reviewer` | Audits cleanup code against all 8 guards. Independent from the builder | Read-only |
| `scope-guard` | Checks every diff against the out-of-scope list: no network, no AI, no deleting outside manifest | Read-only |
| `perf-profiler` | JFR, 1M-row run, peak heap, rows per second, memory growth | Run and read |
| `ui-builder` | Builds React screens from the design brief. The web UI lite and the TUI are built by another tool (Antigravity, see `AGENTS.md`); this agent stays for small fixes | Read and write (`ui/` only) |
| `ui-reviewer` | Playwright screenshots of each screen, judges against the brief and banned-patterns list | Run and read |

Rule: reviewers (`determinism-auditor`, `safety-reviewer`, `scope-guard`, `ui-reviewer`) never share a session with the builder.

## Skills (`.claude/skills/`)

Backend

| Skill | What it holds |
|---|---|
| `gate-first` | Loop: failing gate test, build, green, commit, status update |
| `java-style` | Plain, human Java: short names, small classes, no clever abstractions, no needless interfaces |
| `determinism-rules` | Banned list and the two-run byte-identical test recipe |
| `jdbc-dialects` | Metadata quirks and bulk-load syntax per database |
| `rule-language` | Grammar conventions, how rules compile to SQL, error message format |
| `testcontainers-matrix` | Run one suite on three databases in CI |
| `cleanup-safety` | The 8 guards as a checklist, plus the tests that must exist |
| `perf-measure` | JFR flags, `/usr/bin/time -l`, 1M-row recipe, report table format |
| `status-report` | 5-line `STATUS.md` format and PASS/WARN/FAIL table style |
| `release-jlink` | Packaging runtime image and release checklist |

Frontend (so it does not look AI-made)

| Skill | What it holds |
|---|---|
| `ui-design-brief` | Visual identity: dense, serious dev-tool look (database admin tools, terminals; not a SaaS landing page), fixed color tokens, one type pair, spacing scale |
| `anti-slop-checklist` | Banned: purple/blue gradients, emoji as icons, centered hero blocks, rounded-everything card grids, glassmorphism, fake stat tiles, generic "Welcome back" headings, filler copy |
| `react-conventions` | Strict TypeScript, folder rules, TanStack Query for server state, no giant components |
| `schema-graph` | React Flow + dagre setup, node design, large-schema performance |
| `offline-bundle` | No CDN links, bundled fonts/icons, build check that fails on any external URL |
| `keyboard-first` | Shortcuts, focus order, command palette |
| `playwright-visual` | Screenshot per screen, dark mode check, red prod banner check, smoke test flow |
| `go-live-wizard` | Stepper rules: typed confirmation, disabled button logic, prod banner |

## Notes
- Design brief first: before `ui-builder` writes any screen, `ui/design/BRIEF.md` must exist (user writes it, or with Claude). Pick 2-3 reference tools for the look. The anti-slop list only works against a real identity.
- Anthropic's `frontend-design` skill is optional; if installed, the brief wins.
- Hooks: see `CLAUDE_RULE.md`.
- Offered but not yet written: full `ui-design-brief` and `anti-slop-checklist` skills. Waiting on user.
