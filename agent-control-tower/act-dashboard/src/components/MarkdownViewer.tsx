import DOMPurify from 'dompurify';

interface Props {
  content?: string;
  className?: string;
}

// Minimal markdown -> HTML (headings, bold, code, lists, paragraphs) then sanitise.
// For richer rendering swap `toHtml` for a markdown lib; keep the DOMPurify step.
function toHtml(md: string): string {
  return md
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/^### (.*)$/gm, '<h3>$1</h3>')
    .replace(/^## (.*)$/gm, '<h2>$1</h2>')
    .replace(/^# (.*)$/gm, '<h1>$1</h1>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/^- (.*)$/gm, '<li>$1</li>')
    .replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>')
    .replace(/\n{2,}/g, '<br/><br/>');
}

export function MarkdownViewer({ content, className }: Props) {
  const html = DOMPurify.sanitize(toHtml(content ?? ''));
  return (
    <div
      className={`spec-review-markdown ${className ?? ''}`}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}

export default MarkdownViewer;
