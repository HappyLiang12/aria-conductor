/**
 * Compact, allocation-light markdown renderer for assistant bubbles.
 *
 * Copied from AriaPanel's module-local renderer so the main chat page can reuse
 * the same output and CSS without refactoring the 1905-line panel (the
 * chat-image-attachments branch is editing that file in parallel). Consolidate
 * the two into one module once those branches merge.
 */
export function renderMarkdown(text: string): string {
  if (!text) return '<p></p>';
  // Guard very large payloads — render verbatim to avoid costly regex passes.
  if (text.length > 10000) {
    return `<pre>${escapeHtml(text)}</pre>`;
  }
  try {
    // Extract fenced code blocks first so their pipes/dashes aren't parsed as tables/rules.
    const codeBlocks: string[] = [];
    let src = text.replace(/```(\w*)\n([\s\S]*?)```/g, (_m, _lang, code) => {
      codeBlocks.push(`<pre class="md-code-block"><code>${escapeHtml(String(code))}</code></pre>`);
      return `\u0000CB${codeBlocks.length - 1}\u0000`;
    });
    src = escapeHtml(src);
    const inline = (s: string): string =>
      s
        .replace(/`([^`]+)`/g, '<code class="md-inline-code">$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/\*([^*]+)\*/g, '<em>$1</em>');
    const lines = src.split('\n');
    const out: string[] = [];
    let inList = false;
    let inTable = false;
    const closeList = () => { if (inList) { out.push('</ul>'); inList = false; } };
    const closeTable = () => { if (inTable) { out.push('</tbody></table>'); inTable = false; } };
    for (const raw of lines) {
      const line = raw.trimEnd();
      const trimmed = line.trim();
      if (trimmed.startsWith('|') && trimmed.endsWith('|') && trimmed.length > 1) {
        const cells = trimmed.slice(1, -1).split('|').map((c) => c.trim());
        const isSeparator = cells.every((c) => /^:?-{2,}:?$/.test(c));
        if (isSeparator) { continue; }
        if (!inTable) {
          closeList();
          out.push('<table class="md-table"><thead><tr>');
          for (const c of cells) out.push(`<th>${inline(c)}</th>`);
          out.push('</tr></thead><tbody>');
          inTable = true;
        } else {
          out.push('<tr>');
          for (const c of cells) out.push(`<td>${inline(c)}</td>`);
          out.push('</tr>');
        }
        continue;
      }
      closeTable();
      if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
        closeList();
        out.push('<hr/>');
        continue;
      }
      let h = line;
      h = h.replace(/^### (.+)$/, '<h4>$1</h4>');
      h = h.replace(/^## (.+)$/, '<h3>$1</h3>');
      h = h.replace(/^# (.+)$/, '<h2>$1</h2>');
      h = inline(h);
      if (/^[•\-\*] (.+)$/.test(h)) {
        if (!inList) { out.push('<ul>'); inList = true; }
        out.push(`<li>${h.replace(/^[•\-\*] /, '')}</li>`);
        continue;
      }
      if (/^\d+\. (.+)$/.test(h)) {
        if (!inList) { out.push('<ul>'); inList = true; }
        out.push(`<li>${h.replace(/^\d+\. /, '')}</li>`);
        continue;
      }
      closeList();
      if (h.trim() === '') { out.push(''); } else { out.push(h); }
    }
    closeList();
    closeTable();
    let html = out.join('\n');
    html = html.replace(/\u0000CB(\d+)\u0000/g, (_m, i) => codeBlocks[Number(i)] ?? '');
    html = html.replace(/\n\n/g, '</p><p>');
    html = html.replace(/\n/g, '<br/>');
    if (!html.startsWith('<')) html = `<p>${html}</p>`;
    return html;
  } catch {
    return `<pre>${escapeHtml(text)}</pre>`;
  }
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
