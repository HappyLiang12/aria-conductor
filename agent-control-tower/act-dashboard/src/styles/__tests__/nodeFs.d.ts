/**
 * The dashboard's tsconfig carries no `@types/node`, so `tsc -b` (part of `pnpm build`)
 * rejects a bare `node:fs` import. One test reads the author stylesheet as text — the
 * `.ask-ctx` rule-text pin added by T40 item 5 — and needs exactly this call. Declaring
 * only it keeps the shim visible and keeps the package dependency list unchanged.
 */
declare module 'node:fs' {
  export function readFileSync(path: string, encoding: 'utf8'): string;
}
