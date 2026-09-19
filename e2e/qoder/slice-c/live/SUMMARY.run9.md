# qoder slice-c harness summary (run 9)

- git: 06caaf0
- evidence dir: D:/project/aria-conductor/e2e/qoder/slice-c/live
- provider guard: QODER_E2E_MODEL=efficient (zero-credit pin)
- api: http://localhost:8097   dashboard: http://localhost:5273   opensandbox: http://localhost:8090
- flags: dry-run=0 skip-regression=0 rebuild-image=0 no-preflight=0 only=S4,S5,S9,S10,S11,S12,regression-playwright,regression-container-runtime
- finished: 2026-09-18T15:01:55Z

| step | log | status |
|------|-----|--------|
| preflight | preflight.run11.log | PASS |
| rebuild-image | — | SKIPPED |
| S1 | — | SKIPPED |
| S2 | — | SKIPPED |
| S3 | — | SKIPPED |
| S4 | S4.run7.log | PASS |
| S5 | S5.run7.log | PASS |
| S6 | — | SKIPPED |
| S7 | — | SKIPPED |
| S8 | — | SKIPPED |
| S9 | S9.run7.log | PASS |
| S10 | S10.run10.log | PASS |
| S11 | S11.run11.log | FAIL |
| S12 | S12.run10.log | PASS |
| regression-mvn-test | — | SKIPPED |
| regression-mvn-verify | — | SKIPPED |
| regression-vitest | — | SKIPPED |
| regression-build | — | SKIPPED |
| regression-playwright | regression-playwright.run4.log | FAIL |
| regression-container-runtime | regression-container-runtime.run4.log | PASS |
| leak-scan | leak-scan.run9.log | PASS |

- executed: 8 pass, 2 fail, 11 skipped (of 21 in plan)
- PAT leak scan: 0 hits across 221 evidence file(s)
- PAT leak scan (grep -F -c -f <pat> over every evidence file): 0 hits in every file

OVERALL: FAIL
