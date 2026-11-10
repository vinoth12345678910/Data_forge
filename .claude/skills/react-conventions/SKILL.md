---
name: react-conventions
description: Conventions for the React + Vite + TypeScript UI: strict TypeScript, folder rules, TanStack Query for server state. Use when writing UI code.
---

# react-conventions

- Strict TypeScript. No `any` without a comment saying why.
- Layout: `ui/src/{screens,components,api,state,styles}`; Playwright tests in `ui/e2e/`.
- TanStack Query for server state. The UI edits `forge.yml` only through the server API, so CLI and UI never disagree.
- No giant components: split before they grow past what fits on a screen.
- Stack: Tailwind, shadcn/ui, React Flow + dagre, Monaco, Recharts, TanStack Table.
- No CDN links and no external URLs in `ui/src` (`offline-bundle`).

TODO (user): naming and file conventions beyond the above.
