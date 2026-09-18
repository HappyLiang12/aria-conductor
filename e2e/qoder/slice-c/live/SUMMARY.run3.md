# qoder slice-c harness summary (run 3)

- git: a4ed69a
- evidence dir: D:/project/aria-conductor/e2e/qoder/slice-c/live
- provider guard: QODER_E2E_MODEL=efficient (zero-credit pin)
- api: http://localhost:8097   dashboard: http://localhost:5273   opensandbox: http://localhost:8090
- flags: dry-run=0 skip-regression=0 rebuild-image=1 no-preflight=0 only=<all>
- finished: 2026-09-18T11:01:03Z

| step | log | status |
|------|-----|--------|
| preflight | preflight.run4.log | PASS |
| rebuild-image | rebuild-image.run4.log | PASS |
| S1 | S1.run4.log | PASS |
| S2 | S2.run4.log | PASS |
| S3 | S3.run4.log | PASS |
| S4 | S4.run4.log | FAIL |
| S5 | S5.run4.log | FAIL |
| S6 | S6.run4.log | PASS |
| S7 | S7.run4.log | PASS |
| S8 | S8.run4.log | PASS |
| S9 | S9.run4.log | FAIL |
| S10 | S10.run4.log | FAIL |
| S11 | S11.run4.log | FAIL |
| S12 | S12.run4.log | FAIL |
| regression-mvn-test | regression-mvn-test.run4.log | PASS |
| regression-mvn-verify | regression-mvn-verify.run4.log | PASS |
| regression-vitest | regression-vitest.run3.log | PASS |
| regression-build | regression-build.run3.log | PASS |
| regression-playwright | regression-playwright.run3.log | FAIL |
| regression-container-runtime | regression-container-runtime.run3.log | PASS |
| leak-scan | leak-scan.run3.log | PASS |

- executed: 14 pass, 7 fail, 0 skipped (of 21 in plan)
- PAT leak scan: 0 hits across 121 evidence file(s)
- PAT leak scan (grep -F -c -f <pat> over every evidence file): 0 hits in every file

OVERALL: FAIL
