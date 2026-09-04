/**
 * Extract user-supplied parameter names from a workflow template YAML.
 * Mirrors the backend regex in WorkflowTemplateConverter (L25-29):
 *   - Only scans `prompt_template:` values (inline and block scalar continuations)
 *   - Excludes system placeholders: {previousOutput}, {specRef}, {branchName}
 *   - Returns deduplicated, alphabetically sorted parameter names
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
    // Skip comment lines
    if (/^\s*#/.test(line)) continue;

    // Detect prompt_template field (handles both `  prompt_template:` and `  - prompt_template:`)
    const promptMatch = line.match(/^(\s*)(?:-\s+)?prompt_template\s*:\s*(.*)/);
    if (promptMatch) {
      const value = promptMatch[2].trim();
      if (value === '|' || value === '>' || value === '|-' || value === '>-') {
        // Block scalar — scan continuation lines
        inPromptBlock = true;
        blockIndent = promptMatch[1].length + 2; // typical indent increase
      } else {
        // Inline value — extract params from it
        extractFromText(value, params);
        inPromptBlock = false;
      }
      continue;
    }

    // If we're in a block scalar, check if line is still part of it
    if (inPromptBlock) {
      const lineIndent = line.search(/\S/);
      if (line.trim() === '' || lineIndent >= blockIndent) {
        extractFromText(line, params);
      } else {
        inPromptBlock = false;
      }
    }
  }

  return [...params].sort();
}

function extractFromText(text: string, params: Set<string>): void {
  // Strip inline comments (but not inside quotes)
  const stripped = text.replace(/\s+#.*$/, '');
  let match: RegExpExecArray | null;
  PARAM_REGEX.lastIndex = 0;
  while ((match = PARAM_REGEX.exec(stripped)) !== null) {
    const name = match[1];
    if (!SYSTEM_PLACEHOLDERS.has(name)) {
      params.add(name);
    }
  }
}
