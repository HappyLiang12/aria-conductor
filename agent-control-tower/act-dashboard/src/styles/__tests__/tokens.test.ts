import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const tokens = readFileSync('src/styles/tokens.css', 'utf8');
const index = readFileSync('src/styles/index.css', 'utf8');

describe('design tokens', () => {
  it('defines the shadcn variables in both themes', () => {
    for (const name of [
      '--background', '--foreground', '--card', '--popover',
      '--primary', '--secondary', '--muted', '--muted-foreground',
      '--accent', '--destructive', '--border', '--input', '--ring', '--radius',
    ]) {
      expect(tokens).toContain(`${name}:`);
    }
    expect(tokens).toMatch(/body\.light\s*\{/);
  });

  it('puts Tailwind in layers before legacy rules', () => {
    expect(index).toMatch(/@layer theme, base, components, utilities;/);
    expect(index.indexOf('@import "tailwindcss"')).toBeGreaterThan(0);
    expect(index.indexOf('@import "./tokens.css"')).toBeGreaterThan(
      index.indexOf('@import "tailwindcss"'),
    );
  });

  it('lets the unlayered legacy root use the 8px radius', () => {
    // The unlayered legacy `:root` wins the cascade over tokens.css (layered),
    // so the effective radius is whatever this block declares.
    const legacyRoot = index.match(/:root\s*\{[^}]*\}/);
    expect(legacyRoot).not.toBeNull();
    expect(legacyRoot?.[0]).toContain('--radius: 8px;');
    expect(legacyRoot?.[0]).not.toContain('--radius: 14px');
  });

  it('applies dark utilities by default and turns them off in light mode', () => {
    // The app toggles `body.light`; `.dark` is never set, so a `.dark`-based
    // variant would be dead code.
    expect(index).toContain('@custom-variant dark (&:where(body:not(.light) *));');
    expect(index).not.toContain('@custom-variant dark (&:is(.dark *));');
  });
});
