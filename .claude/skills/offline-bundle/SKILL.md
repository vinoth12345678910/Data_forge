---
name: offline-bundle
description: Keeping the UI fully offline: no CDN links, bundled fonts and icons, a build check that fails on any external URL.
---

# offline-bundle

- No CDN links. Bundle all fonts and icons. No external URLs in `ui/src`.
- The server binds localhost only.
- A hook blocks a commit that adds a URL under `ui/src`.

TODO: the build check that fails on any external URL in the built output (write it in Phase 6; the commit hook only covers source).
