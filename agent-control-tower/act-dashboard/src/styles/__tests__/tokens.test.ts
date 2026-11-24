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
});
