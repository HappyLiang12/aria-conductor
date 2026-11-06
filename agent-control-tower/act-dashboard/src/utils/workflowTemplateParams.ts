/**
 * Extract user-supplied parameter names from a workflow template YAML.
 * Mirrors the backend regex in WorkflowTemplateConverter (L25-29):
 *   - Only scans `prompt_template:` values (inline and block scalar continuations)
 *   - Excludes system placeholders: {previousOutput}, {specRef}, {branchName}
 *   - Returns deduplicated, alphabetically sorted parameter names
 * Comment semantics mirror SnakeYAML (the backend parses the same docs):
 *   - Only lines whose first non-whitespace char is `#` are comments, and only
 *     OUTSIDE block scalars — inside a block scalar `#` is literal content.
 *   - Quoted scalars keep `#` literal inside the quotes (anything after the
 *     closing quote is a comment); plain scalars treat a ` #` as a comment.
 */

const SYSTEM_PLACEHOLDERS = new Set(['previousOutput', 'specRef', 'branchName']);
const PARAM_REGEX = /\{([a-zA-Z_][a-zA-Z0-9_]*)\}/g;

/**
 * Extract parameter names from YAML content.
 * Only scans prompt_template values to avoid false positives from comments or other fields.
 */
export function extractTemplateParams(yaml: string): string[] {
  if (!yaml) return [];

  const params = new Set<string>();
  const lines = yaml.split('\n');
  let inPromptBlock = false;
  let blockIndent = 0;

  for (const line of lines) {
    // Detect prompt_template field (handles both `  prompt_template:` and `  - prompt_template:`)
    const promptMatch = line.match(/^(\s*)(?:-\s+)?prompt_template\s*:\s*(.*)/);
    if (promptMatch) {
      const value = promptMatch[2].trim();
      if (value === '|' || value === '>' || value === '|-' || value === '>-') {
        // Block scalar — scan continuation lines
        inPromptBlock = true;
        blockIndent = promptMatch[1].length + 2; // typical indent increase
      } else {
        // Inline value — extract params from it (quote-aware comment handling)
        extractFromText(value, params, false);
        inPromptBlock = false;
      }
      continue;
    }

    // Block scalar continuation: content is literal, `#` included (SnakeYAML parity)
    if (inPromptBlock) {
      const lineIndent = line.search(/\S/);
      if (line.trim() === '' || lineIndent >= blockIndent) {
        extractFromText(line, params, true);
        continue;
      }
      inPromptBlock = false;
    }

    // Skip comment lines — only outside block scalars (first non-whitespace char is `#`)
    if (/^\s*#/.test(line)) continue;
  }

  return [...params].sort();
}

/** Index of the closing quote for a scalar starting with `"` or `'`, or -1 if unterminated. */
function closingQuoteIndex(text: string): number {
  const quote = text.charAt(0);
  for (let i = 1; i < text.length; i++) {
    const ch = text.charAt(i);
    if (quote === '"' && ch === '\\') { i++; continue; } // escaped char inside double quotes
    if (quote === "'" && ch === "'" && text.charAt(i + 1) === "'") { i++; continue; } // YAML '' escape
    if (ch === quote) return i;
  }
  return -1;
}

/**
 * Strip a trailing YAML comment from an inline scalar value, mirroring SnakeYAML:
 *  - quoted scalars: everything up to the closing quote is literal content, the rest is a comment
 *  - plain scalars: ` #` starts a comment
 *  - block scalar lines (literal): `#` is always content
 */
function stripInlineComment(text: string, literal: boolean): string {
  if (literal) return text;
  const first = text.charAt(0);
  if (first === '"' || first === "'") {
    const end = closingQuoteIndex(text);
    return end === -1 ? text : text.slice(0, end + 1);
  }
  return text.replace(/\s+#.*$/, '');
}

function extractFromText(text: string, params: Set<string>, literal: boolean): void {
  const stripped = stripInlineComment(text, literal);
  let match: RegExpExecArray | null;
  PARAM_REGEX.lastIndex = 0;
  while ((match = PARAM_REGEX.exec(stripped)) !== null) {
    const name = match[1];
    if (!SYSTEM_PLACEHOLDERS.has(name)) {
      params.add(name);
    }
  }
}
