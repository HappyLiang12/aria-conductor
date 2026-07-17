import { useState, useCallback } from 'react';
import type { NotificationType } from '../types';

const STORAGE_KEY = 'aria-notification-prefs';

interface NotificationPrefs {
  showToast: boolean;
  showBadge: boolean;
  typeFilters: Record<NotificationType, boolean>;
}

const defaultPrefs: NotificationPrefs = {
  showToast: true,
  showBadge: true,
  typeFilters: {
    'run.completed': true,
    'run.failed': true,
    'approval.requested': true,
    'knowledge.submitted': true,
    'report.generated': true,
    reminder: true,
    monitor: true,
    brief: true,
  },
};

function loadPrefs(): NotificationPrefs {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) {
      return { ...defaultPrefs, ...JSON.parse(stored) };
    }
  } catch {
    // ignore parse errors
  }
  return defaultPrefs;
}

function savePrefs(prefs: NotificationPrefs): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
  } catch {
    // ignore storage errors
  }
}

export function useNotificationPrefs() {
  const [prefs, setPrefs] = useState<NotificationPrefs>(loadPrefs);

  const updatePref = useCallback(
    <K extends keyof NotificationPrefs>(key: K, value: NotificationPrefs[K]) => {
      setPrefs((prev) => {
        const next = { ...prev, [key]: value };
        savePrefs(next);
        return next;
      });
    },
    [],
  );

  const isTypeEnabled = useCallback(
    (type: NotificationType): boolean => {
      return prefs.typeFilters[type] ?? true;
    },
    [prefs.typeFilters],
  );

  const toggleTypeFilter = useCallback(
    (type: NotificationType) => {
      setPrefs((prev) => {
        const next = {
          ...prev,
          typeFilters: {
            ...prev.typeFilters,
            [type]: !prev.typeFilters[type],
          },
        };
        savePrefs(next);
        return next;
      });
    },
    [],
  );

  return {
    prefs,
    updatePref,
    isTypeEnabled,
    toggleTypeFilter,
  };
}
