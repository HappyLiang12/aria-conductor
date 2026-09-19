# qoder slice-c harness summary (run 13)

- git: 7c8016a
- evidence dir: D:/project/aria-conductor/e2e/qoder/slice-c/live
- provider guard: QODER_E2E_MODEL=efficient (zero-credit pin)
- api: http://localhost:8097   dashboard: http://localhost:5273   opensandbox: http://localhost:8090
- flags: dry-run=0 skip-regression=0 rebuild-image=1 no-preflight=0 only=<all>
- finished: 2026-09-18T21:53:37Z

| step | log | status |
|------|-----|--------|
| preflight | preflight.run15.log | PASS |
| rebuild-image | rebuild-image.run7.log | PASS |
| S1 | S1.run7.log | PASS |
| S2 | S2.run7.log | PASS |
| S3 | S3.run7.log | PASS |
| S4 | S4.run9.log | FAIL |
| S5 | S5.run10.log | FAIL |
| S6 | S6.run7.log | PASS |
| S7 | S7.run7.log | PASS |
| S8 | S8.run7.log | PASS |
| S9 | S9.run10.log | FAIL |
| S10 | S10.run13.log | FAIL |
| S11 | S11.run13.log | PASS |
| S12 | S12.run12.log | PASS |
| regression-mvn-test | regression-mvn-test.run8.log | PASS |
| regression-mvn-verify | regression-mvn-verify.run8.log | PASS |
| regression-vitest | regression-vitest.run6.log | PASS |
| regression-build | regression-build.run6.log | PASS |
| regression-playwright | regression-playwright.run8.log | PASS |
| regression-container-runtime | regression-container-runtime.run6.log | PASS |
| leak-scan | leak-scan.run13.log | PASS |

- executed: 17 pass, 4 fail, 0 skipped (of 21 in plan)
- PAT leak scan: 0 hits across 298 evidence file(s)
- PAT leak scan (grep -F -c -f <pat> over every evidence file): 0 hits in every file

OVERALL: FAIL
