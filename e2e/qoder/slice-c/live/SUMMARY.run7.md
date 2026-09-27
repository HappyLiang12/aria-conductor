# qoder slice-c harness summary (run 7)

- git: 6c70d1a
- evidence dir: /d/project/aria-conductor/e2e/qoder/slice-c/live
- provider guard: QODER_E2E_MODEL=efficient (zero-credit pin)
- api: http://localhost:8097   dashboard: http://localhost:5273   opensandbox: http://localhost:8090
- flags: dry-run=0 skip-regression=1 rebuild-image=0 no-preflight=0 only=S10,S11,S12
- finished: 2026-09-18T12:30:52Z

| step | log | status |
|------|-----|--------|
| preflight | preflight.run8.log | PASS |
| rebuild-image | — | SKIPPED |
| S1 | — | SKIPPED |
| S2 | — | SKIPPED |
| S3 | — | SKIPPED |
| S4 | — | SKIPPED |
| S5 | — | SKIPPED |
| S6 | — | SKIPPED |
| S7 | — | SKIPPED |
| S8 | — | SKIPPED |
| S9 | — | SKIPPED |
| S10 | S10.run8.log | PASS |
| S11 | S11.run8.log | FAIL |
| S12 | S12.run8.log | PASS |
| regression-mvn-test | — | SKIPPED |
| regression-mvn-verify | — | SKIPPED |
| regression-vitest | — | SKIPPED |
| regression-build | — | SKIPPED |
| regression-playwright | — | SKIPPED |
| regression-container-runtime | — | SKIPPED |
| leak-scan | leak-scan.run7.log | PASS |

- executed: 4 pass, 1 fail, 16 skipped (of 21 in plan)
- PAT leak scan: 0 hits across 176 evidence file(s)
- PAT leak scan (grep -F -c -f <pat> over every evidence file): 0 hits in every file

OVERALL: FAIL
