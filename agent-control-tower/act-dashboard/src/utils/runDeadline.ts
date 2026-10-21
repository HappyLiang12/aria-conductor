/**
 * The run deadline System Config contract the dashboard edits. The runtime and
 * the write validator read the same values from act-common's SystemConfigKeys;
 * runDeadlineContract.test.ts fails when either side drifts.
 */
export const RUN_DEADLINE = {
  key: 'aria.tasks.deadline-minutes',
  defaultMinutes: 45,
  minMinutes: 1,
  maxMinutes: 1440,
} as const;
