# qoder slice-c harness summary (run 10)

- git: 6b48899
- evidence dir: D:/project/aria-conductor/e2e/qoder/slice-c/live
- provider guard: QODER_E2E_MODEL=efficient (zero-credit pin)
- api: http://localhost:8097   dashboard: http://localhost:5273   opensandbox: http://localhost:8090
- flags: dry-run=0 skip-regression=0 rebuild-image=1 no-preflight=0 only=<all>
- finished: 2026-09-18T18:29:44Z

| step | log | status |
|------|-----|--------|
| preflight | preflight.run12.log | PASS |
| rebuild-image | rebuild-image.run6.log | PASS |
| S1 | S1.run6.log | PASS |
| S2 | S2.run6.log | PASS |
| S3 | S3.run6.log | PASS |
| S4 | S4.run8.log | PASS |
| S5 | S5.run8.log | FAIL |
| S6 | S6.run6.log | PASS |
| S7 | S7.run6.log | PASS |
| S8 | S8.run6.log | PASS |
| S9 | S9.run8.log | FAIL |
| S10 | S10.run11.log | FAIL |
| S11 | S11.run12.log | PASS |
| S12 | S12.run11.log | PASS |
| regression-mvn-test | regression-mvn-test.run7.log | PASS |
| regression-mvn-verify | regression-mvn-verify.run6.log | FAIL |
| regression-vitest | regression-vitest.run5.log | PASS |
| regression-build | regression-build.run5.log | PASS |
| regression-playwright | regression-playwright.run5.log | FAIL |
| regression-container-runtime | regression-container-runtime.run5.log | PASS |
| leak-scan | leak-scan.run10.log | PASS |

- executed: 16 pass, 5 fail, 0 skipped (of 21 in plan)
- PAT leak scan: 0 hits across 251 evidence file(s)
- PAT leak scan (grep -F -c -f <pat> over every evidence file): 0 hits in every file

OVERALL: FAIL
