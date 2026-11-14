# Design brief for the web UI lite and the TUI (non-visual constraints)

Owner-supplied visual identity is **not set yet**. Do not invent a style.

## Fixed constraints (not negotiable)
- **Localhost only.** The UI talks only to the local Data Forge API (`127.0.0.1`), with the session token printed by the server at startup (`docs/design/UI_API.md`). No other network access. **No external URLs anywhere** (no CDN links, remote fonts, remote icons, analytics, telemetry); a build check fails on any `http://` or `https://` that is not the local API.
- **Bundled fonts and icons** only (shipped inside `ui/`).
- **Dark mode is the default.** A light mode is optional.
- **A red banner on every screen when the environment label is `prod`.**
- **Keyboard friendly:** every action reachable by keyboard, visible focus, sensible tab order.
- **Strict TypeScript, React, Vite, TanStack Query** for server state. Connection passwords live in memory only: never in `localStorage`, cookies, files or logs.
- **Errors show what the API returns** (rule and row), never raw driver text.
- **Verify has three separate states:** passed, failed (rule, table, row) and "not checked in this phase". "Not checked" is never shown in a pass colour and never counted as passed.
- **Run summary must say the data is partial** and that rules not handled in this phase are not checked, using the text from the API envelope.
- **Go-live shows a dry run, readiness, the manual checklist and the terminal command as copyable text. There is no button, call or endpoint that runs a cleanup, a purge or a backup.**

## The five screens
Connect, Schema graph, Run, Verify, Go-live (dry run and readiness only). See `docs/PLAN.md`, Phase 6.

## Decided (owner, 2026-11-14): plain neutral dark
No reference tools were supplied. The owner chose a plain neutral dark style: system fonts, plain CSS custom properties, no ornament. Restyle later by changing `ui/src/styles/app.css`.

## REFERENCE TOOLS: TODO, supplied by the owner (not needed for v1)
<!-- The owner lists 2 or 3 reference tools and what they like about the look of each. Until this block is filled, the visual style is undecided: stop and ask. -->
- Tool 1: TODO
- Tool 2: TODO
- Tool 3: TODO
