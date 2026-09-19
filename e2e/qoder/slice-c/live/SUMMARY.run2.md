# qoder slice-c harness summary (run 2)

- git: 0a18d96
- evidence dir: D:/project/aria-conductor/e2e/qoder/slice-c/live
- provider guard: QODER_E2E_MODEL=efficient (zero-credit pin)
- api: http://localhost:8097   dashboard: http://localhost:5273   opensandbox: http://localhost:8090
- flags: dry-run=0 skip-regression=0 rebuild-image=1 no-preflight=0 only=<all>
- finished: 2026-09-18T08:44:18Z

| step | log | status |
|------|-----|--------|
| preflight | preflight.run3.log | PASS |
| rebuild-image | rebuild-image.run3.log | PASS |
| S1 | S1.run3.log | PASS |
| S2 | S2.run3.log | PASS |
| S3 | S3.run3.log | FAIL |
| S4 | S4.run3.log | PASS |
| S5 | S5.run3.log | PASS |
| S6 | S6.run3.log | PASS |
| S7 | S7.run3.log | PASS |
| S8 | S8.run3.log | PASS |
| S9 | S9.run3.log | PASS |
| S10 | S10.run3.log | FAIL |
| S11 | S11.run3.log | FAIL |
| S12 | S12.run3.log | PASS |
| regression-mvn-test | regression-mvn-test.run3.log | PASS |
| regression-mvn-verify | regression-mvn-verify.run3.log | PASS |
| regression-vitest | regression-vitest.run2.log | PASS |
| regression-build | regression-build.run2.log | PASS |
| regression-playwright | regression-playwright.run2.log | FAIL |
| regression-container-runtime | regression-container-runtime.run2.log | PASS |
| leak-scan | leak-scan.run2.log | PASS |

- executed: 17 pass, 4 fail, 0 skipped (of 21 in plan)
- PAT leak scan: 0 hits across 96 evidence file(s)
- PAT leak scan (grep -F -c -f <pat> over every evidence file): 0 hits in every file

OVERALL: FAIL
