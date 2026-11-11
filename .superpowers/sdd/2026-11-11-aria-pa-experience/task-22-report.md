# Task 22 (Lane Q) — Aria is told it has a memory

Worktree `D:/project/pa-lq`, branch `lane/pa-lq`, base `486de062`.

The brief named `D:/project/aria-conductor/.superpowers/sdd/2026-11-11-aria-pa-experience/task-22-report.md` as the report path, but the same brief forbids touching `D:/project/aria-conductor`. This report is written in the lane worktree at the equivalent path.

## Section added to `AriaPromptCatalog.POLICY`

Inserted between the operator-only-tools paragraph and `## Workflows`:

```
IMPORTANT — memory:
You have a durable memory of the operator and the workspace; recall_memory is how you
read it. Recall before asking the operator something you may already know. When you
learn a durable fact about the operator (name, timezone, preferences, working style),
propose it with remember_fact instead of only acknowledging it in prose. For a person
or thing you have no entity id for, pass kind and canonicalKey (e.g. person /
person:happy): one call proposes the new entity and its first fact together. A
proposal is PENDING until the operator decides it. Never claim a fact is remembered,
saved or known until it is approved; say it is waiting for the operator instead.
Never decide, promote or approve your own proposals, and do not call operator-only
memory tools.
```

Every instruction maps to a real capability at this base:
`MemoryTools.recallMemory(text, focusRef)` (`MemoryTools.java:139`) and
`MemoryTools.rememberFact(entityId, factKey, valueText, sourceRef, kind, canonicalKey)`
(`MemoryTools.java:101`), whose two shapes are exactly "(entityId) adds the fact to an
existing entity, (kind, canonicalKey) creates a new entity with this first fact in the
same transaction". No invented parameter.

## RED

Test added first: `AriaPromptContributorTest.policyTellsAriaToRecallAndProposeDurableFacts`
(whitespace-normalized so it pins the rule, not the wrap). First run, before the catalog edit:

```
[ERROR] AriaPromptContributorTest.policyTellsAriaToRecallAndProposeDurableFacts:112
Expecting actual: "You are Aria, the AI operator assistant ..."
to contain: "IMPORTANT — memory:"
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
```

## Line counts and the pin

- Managed policy prompt (`AriaPromptCatalog.POLICY`, rendered via `String.lines()`): **60 -> 72** (12 lines: 11 prose + 1 separator).
- The 57-line pin is `agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/ToolCatalogParityTest.java:248`, inside `theGeneratedToolSectionExcludesTheOperatorOnlyMemoryTools`: `assertThat(section.content().lines()).hasSize(57)`.

That pin does **not** move with this change, and was left untouched. It counts the
generated tool section, not the policy prose: `ToolCatalogContextContributor` renders one
line per non-operator-only descriptor, so 67 descriptors - 10 operator-only = 57
(`ToolCatalogParityTest.java:217-221` pins 67/65/10). The B4 amendment kept that number by
construction (spec `:429-437`: "descriptor, name, operator-only and prompt-line counts
(67/65/10/57) do not move, and the generated prompt section is unchanged because the
contributor renders name and policy only"). Changing it to 72 would make a currently green
test assert a false number, and reaching any other value would require editing
`ToolCatalog`/`MemoryTools` (not this lane). No assertion was deleted, loosened to a range,
or padded/trimmed to fit.

## Commands and results

```
bash scripts/mvn-lane.sh inner act-aria -am -Dtest=AriaPromptContributorTest
  RED  -> Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
  GREEN-> Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
bash scripts/mvn-lane.sh inner act-aria -am
  -> Tests run: 191, Failures: 0, Errors: 0, Skipped: 4
bash scripts/mvn-lane.sh inner act-mcp -am -Dtest=ToolCatalogParityTest
  -> Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
```

## Four content pins unchanged

`AriaPromptContributorTest` still asserts, and passes, that the prompt contains
`instantiate_template` and does not contain `decide_approval`, `list_agents:` or
`## Available Tools`. The new section introduces none of those strings.

## Commits

- `16391a2a` test(aria): pin the memory guidance the onboarding bar needs
- `ceaa15ae` feat(aria): tell Aria it has a memory and can propose a person
