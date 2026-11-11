import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * T40 item 5. `OperatorSessionNotice` renders `<span class="ask-ctx">` (a div inside
 * `ConfirmDialog`'s `<p>` is invalid nesting, so T35 changed it to a span), and an
 * inline span applies no vertical margin — so `.ask-ctx`'s `margin-bottom: 6px`
 * silently stopped spacing the notice. jsdom does not lay out author stylesheets, so
 * what this test can do is pin the rule TEXT that makes the margin effective:
 * `display: block` on the span is the property that turns the authored 6px into
 * spacing, and dropping it again reddens here.
 *
 * T48 (re-review N6): this comment used to cite a real-browser measurement
 * "recorded in task-40-report.md". That report contains no Item 5 section and no
 * measurement, so the citation is removed rather than the number re-stated: the
 * authored value is 6px and the rule below is the reason it applies. No
 * measurement is claimed that this lane did not take.
 *
 * The path is relative to the package root, which is the cwd of `pnpm test` and of the
 * documented gate command (`pnpm exec vitest run ...` from `act-dashboard`).
 */
const stylesheet = readFileSync('src/styles/index.css', 'utf8');

function ruleFor(selector: string): string {
  const match = stylesheet.match(new RegExp(`(^|\\n)${selector} \\{([^}]*)\\}`));
  if (!match) throw new Error(`no '${selector}' rule in index.css`);
  return match[2]!;
}

describe('.ask-ctx', () => {
  it('is block-level, so its bottom margin applies to the span notice', () => {
    const rule = ruleFor('.ask-ctx');

    expect(rule).toContain('display: block');
    expect(rule).toContain('margin-bottom: 6px');
  });
});
