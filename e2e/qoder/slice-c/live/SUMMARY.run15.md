# qoder slice-c harness summary (run 15)

- git: 7c8016a
- evidence dir: D:/project/aria-conductor/e2e/qoder/slice-c/live
- provider guard: QODER_E2E_MODEL=efficient (zero-credit pin)
- api: http://localhost:8097   dashboard: http://localhost:5273   opensandbox: http://localhost:8090
- flags: dry-run=0 skip-regression=0 rebuild-image=0 no-preflight=0 only=S4,S10
- finished: 2026-09-18T22:17:35Z

| step | log | status |
|------|-----|--------|
| preflight | preflight.run17.log | PASS |
| rebuild-image | — | SKIPPED |
| S1 | — | SKIPPED |
| S2 | — | SKIPPED |
| S3 | — | SKIPPED |
| S4 | S4.run11.log | FAIL |
| S5 | — | SKIPPED |
| S6 | — | SKIPPED |
| S7 | — | SKIPPED |
| S8 | — | SKIPPED |
| S9 | — | SKIPPED |
| S10 | S10.run15.log | PASS |
| S11 | — | SKIPPED |
| S12 | — | SKIPPED |
| regression-mvn-test | — | SKIPPED |
| regression-mvn-verify | — | SKIPPED |
| regression-vitest | — | SKIPPED |
| regression-build | — | SKIPPED |
| regression-playwright | — | SKIPPED |
| regression-container-runtime | — | SKIPPED |
| leak-scan | leak-scan.run15.log | PASS |

- executed: 3 pass, 1 fail, 17 skipped (of 21 in plan)
- PAT leak scan: 0 hits across 314 evidence file(s)
- PAT leak scan (grep -F -c -f <pat> over every evidence file): 0 hits in every file

OVERALL: FAIL
