import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { ReportContentViewer } from '../ReportContentViewer';

describe('ReportContentViewer', () => {
  it('renders captured markdown report content as HTML headings and lists', () => {
    const markdown = '## Summary\n\n- passed\n- failed\n- skipped';
    const { container } = render(
      <ReportContentViewer content={markdown} title="SDD QA Report" />,
    );

    // Styled elements, not raw markdown symbols.
    expect(container.querySelector('h2')?.textContent).toBe('Summary');
    expect(container.querySelector('ul')).not.toBeNull();
    expect(container.querySelectorAll('li').length).toBe(3);
    // No raw markdown markers leak into the output.
    expect(container.textContent).not.toContain('##');
    expect(container.textContent).not.toContain('- passed');
    // Markdown is rendered inline (no iframe needed for already-sanitised text).
    expect(container.querySelector('iframe')).toBeNull();
  });

  it('keeps the sandboxed iframe for full HTML report documents', () => {
    const html =
      '<!DOCTYPE html><html><head><style>body{color:#111}</style></head>' +
      '<body><h1>Weekly Report</h1><p>hello world</p></body></html>';
    const { container } = render(<ReportContentViewer content={html} title="Weekly" />);

    const frame = container.querySelector('iframe');
    expect(frame).not.toBeNull();
    expect(frame?.getAttribute('srcDoc')).toContain('Weekly Report');
  });
});
