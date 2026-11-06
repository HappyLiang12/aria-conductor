/**
 * Child-process environment allowlist for `qodercli --acp` (C0.3 step 1, plan B3a Step 3).
 *
 * The CLI only needs its standard runtime variables plus the Qoder PAT
 * (`QODER_PERSONAL_ACCESS_TOKEN`, the channel confirmed by A3/A4). Everything else in the
 * bridge process environment is dropped by default — notably other providers' LLM
 * credentials, database-managed keys and the SDK-entrypoint switches that make `--acp`
 * refuse to start (the spike/A3 removed those explicitly before the allowlist existed).
 *
 * The model pin is NOT an environment variable: it travels as
 * `session/set_model {sessionId, modelId}` (C0.1/A4/A5). Neither is the plugin directory:
 * the root-owned copy baked into the image at `/opt/qoder/plugin` reaches the CLI through
 * the spawn argv (`--plugin-dir`, `DEFAULT_PLUGIN_DIR` in `src/acp-client.ts`), and cwd
 * through `session/new.cwd` per session. The environment carries neither.
 */
export const CHILD_ENV_ALLOWLIST = [
  'PATH',
  'HOME',
  'LANG',
  'TERM',
  'QODER_PERSONAL_ACCESS_TOKEN',
] as const;

/**
 * Keys that must never reach the CLI child process. The allowlist above already excludes
 * every one of them (default-deny); this list is the independently testable statement of
 * intent for B3a Step 3, and `assertAllowlistDisjoint` fails closed if the two ever
 * intersect while someone edits the allowlist.
 */
export const FORBIDDEN_CHILD_ENV_KEYS = [
  // Other providers' LLM credentials.
  'DEEPSEEK_API_KEY',
  'LLM_API_KEY',
  'OPENAI_API_KEY',
  'ANTHROPIC_API_KEY',
  'GEMINI_API_KEY',
  'AZURE_OPENAI_API_KEY',
  // Database-managed secrets and datasource keys.
  'DATABASE_URL',
  'SPRING_DATASOURCE_PASSWORD',
  'DB_PASSWORD',
  // Alternative Qoder auth channels and the SDK-entrypoint switches (A3/A4 evidence):
  // forwarding any of these would change how the CLI authenticates or starts.
  'QODER_AUTH_MANAGED_TOKEN',
  'QODER_DEVICE_TOKEN',
  'QODER_ENV_JOB_TOKEN',
  'QODER_SDK_ACCESS_TOKEN',
  'QODER_AGENT_SDK_ENTRYPOINT',
  'QODER_WORKER_RUNTIME_ASSET_ROOT',
  'QODER_AGENT_SDK_VERSION',
  'QODERCLI_RUNTIME_PACKAGING',
  'QODER_SESSION_TYPE',
  'QODER_WORKER_CWD',
  'QODER_SDK_AUTH_PAYLOAD_FILE',
] as const;

export class EnvAllowlistViolationError extends Error {
  readonly code = 'ENV_ALLOWLIST_VIOLATION';

  constructor(message: string) {
    super(message);
    this.name = 'EnvAllowlistViolationError';
  }
}

/** Fails closed when the allowlist would forward a forbidden key. */
export function assertAllowlistDisjoint(allowlist: readonly string[], forbidden: readonly string[]): void {
  const overlap = allowlist.filter(key => forbidden.includes(key));
  if (overlap.length > 0) {
    throw new EnvAllowlistViolationError(
      `environment allowlist would forward forbidden key(s): ${overlap.join(', ')}`,
    );
  }
}

/** The subset of `env` that is forbidden (used by the tests and by defensive callers). */
export function forbiddenKeysPresent(env: Record<string, string>): string[] {
  return Object.keys(env).filter(key => (FORBIDDEN_CHILD_ENV_KEYS as readonly string[]).includes(key));
}

/**
 * Copy only the allowlisted keys that are present in `source` (default `process.env`).
 * Keys are never synthesised: a variable the sandbox does not provide is simply absent.
 */
export function buildChildEnv(source: NodeJS.ProcessEnv = process.env): Record<string, string> {
  assertAllowlistDisjoint(CHILD_ENV_ALLOWLIST, FORBIDDEN_CHILD_ENV_KEYS);
  const childEnv: Record<string, string> = {};
  for (const key of CHILD_ENV_ALLOWLIST) {
    const value = source[key];
    if (typeof value === 'string') {
      childEnv[key] = value;
    }
  }
  return childEnv;
}
