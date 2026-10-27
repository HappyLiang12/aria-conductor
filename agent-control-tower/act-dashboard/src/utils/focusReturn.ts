/**
 * Capture the element that opened a surface and return a restore function.
 * Restoration is best-effort: a trigger that left the DOM reports false so the
 * caller can choose a meaningful successor instead of focusing the body.
 */
export function rememberFocus(): () => boolean {
  const element = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  return () => {
    if (!element?.isConnected) return false;
    element.focus();
    return true;
  };
}
