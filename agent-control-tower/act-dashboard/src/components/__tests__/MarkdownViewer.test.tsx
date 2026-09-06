import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { MarkdownViewer } from '../MarkdownViewer';

describe('MarkdownViewer', () => {
  it('renders markdown headings, bold and inline code', () => {
    const { container } = render(
      <MarkdownViewer content={'# Title\n\n## Sub\n\n**bold** and `code`'} />,
    );
    expect(container.querySelector('h1')?.textContent).toBe('Title');
    expect(container.querySelector('h2')?.textContent).toBe('Sub');
    expect(container.querySelector('strong')?.textContent).toBe('bold');
    expect(container.querySelector('code')?.textContent).toBe('code');
  });

  it('sanitises script tags away', () => {
    const { container } = render(
      <MarkdownViewer content={'# Title\n\n<script>alert(1)</script>'} />,
    );
    expect(container.querySelector('h1')?.textContent).toBe('Title');
    expect(container.querySelector('script')).toBeNull();
  });

  it('renders list items and applies the spec-review-markdown contract class', () => {
    const { container } = render(<MarkdownViewer content={'- a\n- b'} className="extra" />);
    expect(container.querySelectorAll('li').length).toBe(2);
    expect(container.firstElementChild).toHaveClass('spec-review-markdown', 'extra');
  });

  it('wraps multiple list items in a single <ul> element', () => {
    const { container } = render(<MarkdownViewer content={'- a\n- b\n- c'} />);
    const ul = container.querySelector('ul');
    expect(ul).not.toBeNull();
    expect(ul!.querySelectorAll('li').length).toBe(3);
    expect(container.querySelectorAll('li').length).toBe(3);
  });

  it('leaves headings and paragraphs unaffected by list wrapping', () => {
    const { container } = render(<MarkdownViewer content={'# Title\n\njust a paragraph'} />);
    expect(container.querySelector('h1')?.textContent).toBe('Title');
    expect(container.querySelector('ul')).toBeNull();
    expect(container.textContent).toContain('just a paragraph');
  });

  it('renders a very long list (500 items) without hanging', () => {
    const items = Array.from({ length: 500 }, (_, i) => `- item ${i}`).join('\n');
    const { container } = render(<MarkdownViewer content={items} />);
    expect(container.querySelectorAll('li').length).toBe(500);
  });

  it('handles empty content gracefully', () => {
    const { container } = render(<MarkdownViewer content={undefined} />);
    expect(container.firstElementChild?.textContent).toBe('');
  });

  it('markdown_rendersTable_links_code: table, link and fenced code produce real elements', () => {
    const md = [
      '| Name | Risk |',
      '| --- | --- |',
      '| tool-a | High |',
      '',
      'See [Docs](https://example.com/help) for details.',
      '',
      '```js',
      'const x = 1;',
      '```',
    ].join('\n');
    const { container } = render(<MarkdownViewer content={md} />);

    const table = container.querySelector('table');
    expect(table).not.toBeNull();
    expect(container.querySelectorAll('th')).toHaveLength(2);
    expect(container.querySelectorAll('td')).toHaveLength(2);
    expect(table!.querySelector('th')?.textContent).toBe('Name');
    expect(table!.querySelector('td')?.textContent).toBe('tool-a');

    const link = container.querySelector('a');
    expect(link).not.toBeNull();
    expect(link!.getAttribute('href')).toBe('https://example.com/help');
    expect(link!.getAttribute('target')).toBe('_blank');
    expect(link!.getAttribute('rel')).toBe('noopener');

    const pre = container.querySelector('pre');
    expect(pre).not.toBeNull();
    expect(pre!.querySelector('code')?.textContent).toBe('const x = 1;');
  });

  it('renders ordered lists', () => {
    const { container } = render(<MarkdownViewer content={'1. first\n2. second'} />);
    const ol = container.querySelector('ol');
    expect(ol).not.toBeNull();
    expect(ol!.querySelectorAll('li')).toHaveLength(2);
  });

  it('keeps fenced code content unparsed (no markdown/HTML interpretation inside)', () => {
    const { container } = render(
      <MarkdownViewer content={'```\n**not bold** and <script>x</script>\n```'} />,
    );
    const pre = container.querySelector('pre');
    expect(pre).not.toBeNull();
    expect(pre!.textContent).toBe('**not bold** and <script>x</script>');
    expect(container.querySelector('strong')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
  });
});
