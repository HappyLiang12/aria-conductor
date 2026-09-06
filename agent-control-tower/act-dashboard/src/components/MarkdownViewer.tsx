import DOMPurify from 'dompurify';

interface Props {
  content?: string;
  className?: string;
}

// Bounded, dependency-free markdown renderer. NOT a full markdown engine:
// it handles the constructs spec reviews actually emit — fenced code blocks,
// pipe tables, headings, unordered/ordered lists, bold, inline code, links,
// and paragraphs. Blocks are split first (code fences isolated BEFORE any
// inline parsing so their content is never interpreted), then each block is
// rendered. All text is HTML-escaped up front; output is sanitised by
// DOMPurify at the call site.
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// Inline spans. Inline-code spans are lifted out first (placeholder swap) so
// their content is never touched by the other rules, then restored as <code>.
function renderInline(md: string): string {
  const codes: string[] = [];
  let out = md.replace(/`([^`]+)`/g, (_m, code: string) => `\u0000${codes.push(code) - 1}\u0000`);
  out = out
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(
      /\[([^\]]+)\]\(([^)\s]+)\)/g,
      (_m, text: string, url: string) =>
        `<a href="${url}" target="_blank" rel="noopener">${text}</a>`,
    );
  return out.replace(/\u0000(\d+)\u0000/g, (_m, i: string) => `<code>${codes[Number(i)]}</code>`);
}

const FENCE_RE = /^```/;
const TABLE_SEP_RE = /^\s*\|[\s:|-]+\|\s*$/;
const TABLE_ROW_RE = /^\s*\|.+\|\s*$/;
const HEADING_RE = /^(#{1,6})\s+(.*)$/;
const UL_ITEM_RE = /^-\s+(.*)$/;
const OL_ITEM_RE = /^\s*\d+\.\s+(.*)$/;
// Lines that start a new block while a paragraph is being gathered.
const BLOCK_START_RE = /^(#{1,6})\s+|```|^-\s+|^\s*\d+\.\s+/;

function parseRow(row: string): string[] {
  return row
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((c) => c.trim());
}

function renderTable(lines: string[], startIndex: number): { html: string; next: number } {
  const header = parseRow(lines[startIndex]);
  let i = startIndex + 2; // skip header + separator
  const body: string[][] = [];
  while (i < lines.length && /^\s*\|/.test(lines[i]) && lines[i].trim() !== '') {
    body.push(parseRow(lines[i]));
    i++;
  }
  const thead = `<thead><tr>${header.map((c) => `<th>${renderInline(escapeHtml(c))}</th>`).join('')}</tr></thead>`;
  const tbody = `<tbody>${body
    .map((row) => `<tr>${row.map((c) => `<td>${renderInline(escapeHtml(c))}</td>`).join('')}</tr>`)
    .join('')}</tbody>`;
  return { html: `<table>${thead}${tbody}</table>`, next: i };
}

function toHtml(md: string): string {
  const lines = md.split('\n');
  const blocks: string[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];

    // Fenced code FIRST — content is escaped verbatim, never inline-parsed.
    if (FENCE_RE.test(line.trim())) {
      const lang = line.trim().slice(3).trim();
      const buf: string[] = [];
      i++;
      while (i < lines.length && !FENCE_RE.test(lines[i].trim())) {
        buf.push(lines[i]);
        i++;
      }
      i++; // consume closing fence (or run off the end of an unclosed block)
      const cls = lang ? ` class="language-${lang}"` : '';
      blocks.push(`<pre><code${cls}>${escapeHtml(buf.join('\n'))}</code></pre>`);
      continue;
    }

    // Pipe table: header row + --- separator + body rows.
    if (TABLE_ROW_RE.test(line) && i + 1 < lines.length && TABLE_SEP_RE.test(lines[i + 1])) {
      const t = renderTable(lines, i);
      blocks.push(t.html);
      i = t.next;
      continue;
    }

    // Headings.
    const h = HEADING_RE.exec(line);
    if (h) {
      const level = h[1].length;
      blocks.push(`<h${level}>${renderInline(escapeHtml(h[2]))}</h${level}>`);
      i++;
      continue;
    }

    // Unordered list.
    if (UL_ITEM_RE.test(line)) {
      const items: string[] = [];
      while (i < lines.length && UL_ITEM_RE.test(lines[i])) {
        items.push(UL_ITEM_RE.exec(lines[i])![1]);
        i++;
      }
      blocks.push(`<ul>${items.map((it) => `<li>${renderInline(escapeHtml(it))}</li>`).join('')}</ul>`);
      continue;
    }

    // Ordered list.
    if (OL_ITEM_RE.test(line)) {
      const items: string[] = [];
      while (i < lines.length && OL_ITEM_RE.test(lines[i])) {
        items.push(OL_ITEM_RE.exec(lines[i])![1]);
        i++;
      }
      blocks.push(`<ol>${items.map((it) => `<li>${renderInline(escapeHtml(it))}</li>`).join('')}</ol>`);
      continue;
    }

    // Blank lines separate blocks.
    if (line.trim() === '') {
      i++;
      continue;
    }

    // Paragraph: consecutive non-blank lines that don't start another block.
    const para: string[] = [];
    while (i < lines.length && lines[i].trim() !== '' && !BLOCK_START_RE.test(lines[i])) {
      para.push(lines[i]);
      i++;
    }
    if (para.length === 0) {
      // Line starts with something block-like we don't handle (e.g. a lone
      // "|" row without a separator) — keep it as literal paragraph text.
      para.push(line);
      i++;
    }
    blocks.push(`<p>${renderInline(escapeHtml(para.join('\n'))).replace(/\n/g, '<br/>')}</p>`);
  }
  return blocks.join('\n');
}

export function MarkdownViewer({ content, className }: Props) {
  // ADD_ATTR keeps target/rel on self-generated safe anchors (DOMPurify's
  // default allowlist drops them).
  const html = DOMPurify.sanitize(toHtml(content ?? ''), { ADD_ATTR: ['target', 'rel'] });
  return (
    <div
      className={`spec-review-markdown ${className ?? ''}`}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}

export default MarkdownViewer;
