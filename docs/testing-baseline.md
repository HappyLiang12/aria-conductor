# Testing Baseline (Ratchet Anchor)

> Measured on branch `main` worktree, 2026-07-25, via
> `cd agent-control-tower && mvn clean verify -Dspring.profiles.active=h2`
> (surefire unit tier + failsafe integration tier, H2 profile).
>
> **Ratchet rule:** the JaCoCo `check` minimums in each module pom are always
> set BELOW the last measured value (M0 = baseline − 5pp). Never raise a
> minimum above measured coverage; raise them in a dedicated one-line PR only
> after new tests have landed and been re-measured.

## M0 Java line/branch coverage baseline (JaCoCo, unit tier)

| Module | Line | Branch | M0 line min | M0 branch min |
|--------|------|--------|-------------|---------------|
| act-common | 10.1% (40/397) | 2.9% (4/140) | 0.05 | 0.00 |
| act-agent | 44.2% (454/1028) | 33.6% (119/354) | 0.39 | 0.28 |
| act-execution | 39.9% (1373/3438) | 27.9% (446/1600) | 0.34 | 0.22 |
| act-knowledge | 53.2% (863/1621) | 49.4% (324/656) | 0.48 | 0.44 |
| act-aria | 63.5% (856/1348) | 43.0% (225/523) | 0.58 | 0.38 |
| act-dashboard-api | 52.1% (219/420) | 40.7% (48/118) | 0.47 | 0.35 |
| act-app | 87.5% (70/80) | 68.0% (34/50) | 0.82 | 0.63 |
| act-test-support | n/a (no tests of its own) | n/a | 0 | 0 |

## M1 measured coverage (post Phase B+C+D+F, unit tier) + enforced minimums

Measured after execution/knowledge test expansion (PRs #45/#46), Phase C slices, and
Phase F property/concurrency tests landed on the stacked chain. Minimums set BELOW
measured with a CI-variance margin (ratchet iron law); all clear the plan's M1 floor
(core execution/agent 55% line, others 40%) and most already reach M2/M3 levels.

| Module | Measured line | Measured branch | M1 line min | M1 branch min |
|--------|---------------|-----------------|-------------|---------------|
| act-common | 62.1% (246/396) | 73.6% (103/140) | 0.55 | 0.60 |
| act-agent | 75.6% (776/1027) | 50.0% (177/354) | 0.68 | 0.42 |
| act-execution | 65.5% (2250/3436) | 49.7% (795/1600) | 0.58 | 0.42 |
| act-knowledge | 78.8% (1275/1618) | 61.9% (406/656) | 0.70 | 0.54 |
| act-aria | 93.5% (1260/1348) | 83.7% (438/523) | 0.86 | 0.76 |
| act-dashboard-api | 89.3% (374/419) | 60.2% (71/118) | 0.80 | 0.52 |
| act-app | 87.5% (70/80) | 68.0% (34/50) | 0.80 | 0.60 |

## Test inventory baseline (pre Phase B/C)

| Stack | Files | Tests |
|-------|-------|-------|
| Java unit tier (surefire) | 98 | 621 `@Test` |
| Java integration tier (failsafe `*IntegrationTest`/`*E2ETest`) | 13 | ~55 |
| Playwright E2E | 24 specs | 158 `test()` |
| Python langchain-adk | 1 | 5 |
| TS mcp-server | 15 | 75 |
| Frontend (vitest) | 0 | 0 |
| Contract | 0 | 0 |

## Ratchet milestones (from the approved plan)

| Milestone | Trigger | Core modules (execution/agent) | Others |
|-----------|---------|--------------------------------|--------|
| M0 | baseline measured | baseline − 5pp | baseline − 5pp |
| **M1 (current)** | **Phase B/C/D/F landed + re-measured** | **execution 0.58 / agent 0.68 line; 0.42 branch** | **0.55–0.86 line (see M1 table)** |
| M1 | Phase B landed + re-measured | 55% line | 40% line |
| M2 | Phase C+D landed + re-measured | 60% line / 50% branch | — |
| M3 | Phase E+F landed + re-measured | 70% line / 60% branch | act-app wiring exception ~50% |

Mirrors: pytest `--cov-fail-under` 0 → 40 → 70; vitest `coverage.thresholds`
raised in lockstep. PIT mutation score (nightly) is the primary honesty check;
`mutationThreshold` ratchets to 60 after two weeks of stable reports.
