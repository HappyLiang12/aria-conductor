import { describe, expect, it } from 'vitest';
import errorSurfaceSource from '../../../../act-common/src/main/java/io/aria/conductor/common/exception/ErrorSurface.java?raw';
import { ERROR_SURFACES, errorActionForSurface } from '../errorPresentation';

/** The enum constants exactly as the backend declares them. */
function javaEnumValues(): string[] {
  const body = errorSurfaceSource.match(/enum ErrorSurface \{([\s\S]*?)\}/);
  if (!body) {
    throw new Error('ErrorSurface enum body not found in the Java source');
  }
  return body[1]
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/.*$/gm, '')
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean);
}

describe('error surface contract', () => {
  it('serves exactly the surfaces the backend can persist', () => {
    expect([...ERROR_SURFACES]).toEqual(javaEnumValues());
  });

  it('has a repair action for every backend surface', () => {
    for (const surface of ERROR_SURFACES) {
      expect(errorActionForSurface(surface), `no action for ${surface}`).toBeDefined();
    }
  });
});
