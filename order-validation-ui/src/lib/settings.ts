// Client-side settings for the order-validation analytics dashboard.
// Persists the user's chosen workflow id (the one whose runs feed the
// dashboard) and the active date range in localStorage. Single-tab tool,
// so no cross-tab sync needed.

const WORKFLOW_KEY = "ov:workflowId";
const DATE_RANGE_KEY = "ov:dateRange";

export function getWorkflowId(): string | null {
  try {
    return localStorage.getItem(WORKFLOW_KEY);
  } catch {
    return null;
  }
}

export function setWorkflowId(id: string | null): void {
  try {
    if (id) localStorage.setItem(WORKFLOW_KEY, id);
    else localStorage.removeItem(WORKFLOW_KEY);
    window.dispatchEvent(new CustomEvent("ov:settings-changed"));
  } catch {
    // ignore
  }
}

export interface DateRange {
  fromTs: number;
  toTs: number;
}

export function getDateRange(): DateRange {
  try {
    const raw = localStorage.getItem(DATE_RANGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as DateRange;
      if (Number.isFinite(parsed.fromTs) && Number.isFinite(parsed.toTs)) return parsed;
    }
  } catch {
    // fall through to default
  }
  return defaultDateRange();
}

export function setDateRange(range: DateRange): void {
  try {
    localStorage.setItem(DATE_RANGE_KEY, JSON.stringify(range));
    window.dispatchEvent(new CustomEvent("ov:settings-changed"));
  } catch {
    // ignore
  }
}

export function defaultDateRange(): DateRange {
  const now = new Date();
  const start = new Date(now);
  start.setHours(0, 0, 0, 0);
  start.setDate(start.getDate() - 6); // last 7 days inclusive
  const end = new Date(now);
  end.setHours(23, 59, 59, 999);
  return { fromTs: start.getTime(), toTs: end.getTime() };
}
