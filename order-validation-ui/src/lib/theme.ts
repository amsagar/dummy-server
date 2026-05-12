// Theme manager. Reads/writes `data-theme` on the root <html> element and
// persists the choice in localStorage. Defaults to "light"; "dark" is opt-in
// via the toggle in TopBar.

export type Theme = "light" | "dark";

const STORAGE_KEY = "ov:theme";

export function getTheme(): Theme {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === "light" || raw === "dark") return raw;
  } catch {
    // ignore (private mode etc.)
  }
  return "light";
}

export function setTheme(theme: Theme): void {
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // ignore
  }
  applyTheme(theme);
  window.dispatchEvent(new CustomEvent("ov:theme-changed", { detail: theme }));
}

export function applyTheme(theme: Theme): void {
  document.documentElement.setAttribute("data-theme", theme);
  // Hint the browser for native form controls (date pickers, scrollbars).
  document.documentElement.style.colorScheme = theme;
}

export function toggleTheme(): Theme {
  const next: Theme = getTheme() === "dark" ? "light" : "dark";
  setTheme(next);
  return next;
}
