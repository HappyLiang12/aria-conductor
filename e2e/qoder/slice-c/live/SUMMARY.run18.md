# qoder slice-c harness summary (run 18)

- git: 173d210
- evidence dir: D:/project/aria-conductor/e2e/qoder/slice-c/live
- provider guard: QODER_E2E_MODEL=efficient (zero-credit pin)
- api: http://localhost:8097   dashboard: http://localhost:5273   opensandbox: http://localhost:8090
- flags: dry-run=0 skip-regression=0 rebuild-image=0 no-preflight=0 only=S4
- finished: 2026-09-18T22:56:24Z

| step | log | status |
|------|-----|--------|
| preflight | preflight.run20.log | PASS |
| rebuild-image | — | SKIPPED |
| S1 | — | SKIPPED |
| S2 | — | SKIPPED |
| S3 | — | SKIPPED |
| S4 | S4.run14.log | PASS |
| S5 | — | SKIPPED |
| S6 | — | SKIPPED |
| S7 | — | SKIPPED |
| S8 | — | SKIPPED |
| S9 | — | SKIPPED |
| S10 | — | SKIPPED |
| S11 | — | SKIPPED |
| S12 | — | SKIPPED |
| regression-mvn-test | — | SKIPPED |
| regression-mvn-verify | — | SKIPPED |
| regression-vitest | — | SKIPPED |
| regression-build | — | SKIPPED |
| regression-playwright | — | SKIPPED |
| regression-container-runtime | — | SKIPPED |
| leak-scan | leak-scan.run18.log | PASS |

- executed: 3 pass, 0 fail, 18 skipped (of 21 in plan)
- PAT leak scan: 0 hits across 330 evidence file(s)
- PAT leak scan (grep -F -c -f <pat> over every evidence file): 0 hits in every file

OVERALL: PASS
