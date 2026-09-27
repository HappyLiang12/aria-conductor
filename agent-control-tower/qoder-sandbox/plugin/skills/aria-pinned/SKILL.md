---
name: aria-pinned
description: Aria Conductor pinned skill; proves --plugin-dir loads exactly the pinned bundle.
---

# aria-pinned

This skill exists only to make the pinned plugin bundle a valid, loadable plugin
(manifest + one skill component). Slice A (Task A2) used it to prove that
`--plugin-dir /workspace/plugin` (the runtime-uploaded copy) was the only plugin source inside
the sandbox; the shipped image now bakes this bundle at `/opt/qoder/plugin`, and the bridge loads
that copy via its C0.3 step-1 spawn argv (`--plugin-dir /opt/qoder/plugin`; amended 2026-09-18).

Do not add behavior here; the bundle content is pinned by the A2 evidence in
`e2e/qoder/slice-a/02-isolation.md`.
