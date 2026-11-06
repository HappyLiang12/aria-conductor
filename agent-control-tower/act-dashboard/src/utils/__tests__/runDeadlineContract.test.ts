import { describe, expect, it } from 'vitest';
import systemConfigKeysSource from '../../../../act-common/src/main/java/io/aria/conductor/common/config/SystemConfigKeys.java?raw';
import { RUN_DEADLINE } from '../runDeadline';

function javaConstant(name: string): string {
  const match = systemConfigKeysSource.match(
    new RegExp(`static final (?:String|int) ${name} = ([^;]+);`),
  );
  if (!match) {
    throw new Error(`SystemConfigKeys.${name} not found in the Java source`);
  }
  return match[1].trim();
}

function javaInt(name: string): number {
  const literal = javaConstant(name);
  const value = Number(literal);
  if (!Number.isInteger(value)) {
    throw new Error(`SystemConfigKeys.${name} is not an integer literal: ${literal}`);
  }
  return value;
}

function javaString(name: string): string {
  const literal = javaConstant(name);
  const match = literal.match(/^"([^"]*)"$/);
  if (!match) {
    throw new Error(`SystemConfigKeys.${name} is not a string literal: ${literal}`);
  }
  return match[1];
}

describe('run deadline config contract', () => {
  it('mirrors the key, default and range the runtime and the admin API enforce', () => {
    expect(RUN_DEADLINE).toEqual({
      key: javaString('TASK_DEADLINE_MINUTES'),
      defaultMinutes: javaInt('TASK_DEADLINE_DEFAULT_MINUTES'),
      minMinutes: javaInt('TASK_DEADLINE_MIN_MINUTES'),
      maxMinutes: javaInt('TASK_DEADLINE_MAX_MINUTES'),
    });
  });

  it('keeps the edited default inside the edited range', () => {
    expect(RUN_DEADLINE.minMinutes).toBeGreaterThanOrEqual(1);
    expect(RUN_DEADLINE.maxMinutes).toBeGreaterThan(RUN_DEADLINE.minMinutes);
    expect(RUN_DEADLINE.defaultMinutes).toBeGreaterThanOrEqual(RUN_DEADLINE.minMinutes);
    expect(RUN_DEADLINE.defaultMinutes).toBeLessThanOrEqual(RUN_DEADLINE.maxMinutes);
  });
});
