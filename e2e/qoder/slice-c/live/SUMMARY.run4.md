# qoder slice-c harness summary (run 4)

- git: 8f5ed4d
- evidence dir: D:/project/aria-conductor/e2e/qoder/slice-c/live
- provider guard: QODER_E2E_MODEL=efficient (zero-credit pin)
- api: http://localhost:8097   dashboard: http://localhost:5273   opensandbox: http://localhost:8090
- flags: dry-run=0 skip-regression=1 rebuild-image=0 no-preflight=0 only=S4,S5,S9,S10,S11,S12
- finished: 2026-09-18T11:31:30Z

| step | log | status |
|------|-----|--------|
| preflight | preflight.run5.log | PASS |
| rebuild-image | — | SKIPPED |
| S1 | — | SKIPPED |
| S2 | — | SKIPPED |
| S3 | — | SKIPPED |
| S4 | S4.run5.log | PASS |
| S5 | S5.run5.log | PASS |
| S6 | — | SKIPPED |
| S7 | — | SKIPPED |
| S8 | — | SKIPPED |
| S9 | S9.run5.log | PASS |
| S10 | S10.run5.log | FAIL |
| S11 | S11.run5.log | FAIL |
| S12 | S12.run5.log | FAIL |
| regression-mvn-test | — | SKIPPED |
| regression-mvn-verify | — | SKIPPED |
| regression-vitest | — | SKIPPED |
| regression-build | — | SKIPPED |
| regression-playwright | — | SKIPPED |
| regression-container-runtime | — | SKIPPED |
| leak-scan | leak-scan.run4.log | PASS |

- executed: 5 pass, 3 fail, 13 skipped (of 21 in plan)
- PAT leak scan: 0 hits across 139 evidence file(s)
- PAT leak scan (grep -F -c -f <pat> over every evidence file): 0 hits in every file

OVERALL: FAIL
