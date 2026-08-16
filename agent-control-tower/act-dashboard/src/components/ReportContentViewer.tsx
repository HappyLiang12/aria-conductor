import { MarkdownViewer } from './MarkdownViewer';

interface Props {
  content?: string;
  title?: string;
}

// Report bodies are persisted in one of two forms (R10-F2):
//  - LLM-authored full HTML documents (generate/amend/regenerate) -> keep the
//    sandboxed iframe for full isolation.
//  - Captured QA report markdown written verbatim by the QA agent -> render
//    through the shared MarkdownViewer (DOMPurify) so headings/lists are styled
//    instead of showing raw `##` / `-` symbols.
function looksLikeHtmlDocument(content: string): boolean {
  return /<!doctype\s+html|<\s*html[\s>]|<\s*body[\s>]/i.test(content);
}

export function ReportContentViewer({ content, title }: Props) {
  const body = content ?? '';

  if (looksLikeHtmlDocument(body)) {
    return (
      <iframe
        className="report-frame"
        title={title}
        sandbox=""
        srcDoc={body}
      />
    );
  }

  return (
    <div className="report-markdown-body">
      <MarkdownViewer content={body || '_Loading report…_'} />
    </div>
  );
}

export default ReportContentViewer;
