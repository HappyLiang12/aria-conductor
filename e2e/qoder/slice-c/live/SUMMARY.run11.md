# qoder slice-c harness summary (run 11)

- git: 6b48899
- evidence dir: D:/project/aria-conductor/e2e/qoder/slice-c/live
- provider guard: QODER_E2E_MODEL=efficient (zero-credit pin)
- api: http://localhost:8097   dashboard: http://localhost:5273   opensandbox: http://localhost:8090
- flags: dry-run=0 skip-regression=0 rebuild-image=0 no-preflight=0 only=S5,S9,S10,regression-mvn-verify,regression-playwright
- finished: 2026-09-18T19:05:08Z

| step | log | status |
|------|-----|--------|
| preflight | preflight.run13.log | PASS |
| rebuild-image | — | SKIPPED |
| S1 | — | SKIPPED |
| S2 | — | SKIPPED |
| S3 | — | SKIPPED |
| S4 | — | SKIPPED |
| S5 | S5.run9.log | PASS |
| S6 | — | SKIPPED |
| S7 | — | SKIPPED |
| S8 | — | SKIPPED |
| S9 | S9.run9.log | PASS |
| S10 | S10.run12.log | PASS |
| S11 | — | SKIPPED |
| S12 | — | SKIPPED |
| regression-mvn-test | — | SKIPPED |
| regression-mvn-verify | regression-mvn-verify.run7.log | PASS |
| regression-vitest | — | SKIPPED |
| regression-build | — | SKIPPED |
| regression-playwright | regression-playwright.run6.log | FAIL |
| regression-container-runtime | — | SKIPPED |
| leak-scan | leak-scan.run11.log | PASS |

- executed: 6 pass, 1 fail, 14 skipped (of 21 in plan)
- PAT leak scan: 0 hits across 267 evidence file(s)
- PAT leak scan (grep -F -c -f <pat> over every evidence file): 0 hits in every file

OVERALL: FAIL
