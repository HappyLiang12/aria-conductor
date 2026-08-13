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
});
