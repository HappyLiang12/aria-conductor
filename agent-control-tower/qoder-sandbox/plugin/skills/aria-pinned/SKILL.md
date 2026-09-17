---
name: aria-pinned
description: Aria Conductor pinned skill; proves --plugin-dir loads exactly the pinned bundle.
---

# aria-pinned

This skill exists only to make the pinned plugin bundle a valid, loadable plugin
(manifest + one skill component). Slice A (Task A2) uses it to prove that
`--plugin-dir /workspace/plugin` is the only plugin source inside the sandbox.

Do not add behavior here; the bundle content is pinned by the A2 evidence in
`e2e/qoder/slice-a/02-isolation.md`.
