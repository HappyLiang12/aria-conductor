# qoder slice-c harness summary (run 1)

- git: fd67e2c
- evidence dir: D:/project/aria-conductor/e2e/qoder/slice-c/live
- provider guard: QODER_E2E_MODEL=efficient (zero-credit pin)
- api: http://localhost:8097   dashboard: http://localhost:5273   opensandbox: http://localhost:8090
- flags: dry-run=0 skip-regression=0 rebuild-image=0 no-preflight=0 only=<all>
- finished: 2026-09-18T07:16:07Z

| step | log | status |
|------|-----|--------|
| preflight | preflight.run2.log | PASS |
| rebuild-image | rebuild-image.run2.log | PASS |
| S1 | S1.run2.log | PASS |
| S2 | S2.run2.log | FAIL |
| S3 | S3.run2.log | FAIL |
| S4 | S4.run2.log | PASS |
| S5 | S5.run2.log | PASS |
| S6 | S6.run2.log | PASS |
| S7 | S7.run2.log | PASS |
| S8 | S8.run2.log | PASS |
| S9 | S9.run2.log | PASS |
| S10 | S10.run2.log | FAIL |
| S11 | S11.run2.log | FAIL |
| S12 | S12.run2.log | FAIL |
| regression-mvn-test | regression-mvn-test.run2.log | FAIL |
| regression-mvn-verify | regression-mvn-verify.run2.log | FAIL |
| regression-vitest | regression-vitest.run1.log | PASS |
| regression-build | regression-build.run1.log | PASS |
| regression-playwright | regression-playwright.run1.log | FAIL |
| regression-container-runtime | regression-container-runtime.run1.log | PASS |
| leak-scan | leak-scan.run1.log | PASS |

- executed: 13 pass, 8 fail, 0 skipped (of 21 in plan)
- PAT leak scan: 0 hits across 73 evidence file(s)
- PAT leak scan (grep -F -c -f <pat> over every evidence file): 0 hits in every file

OVERALL: FAIL
