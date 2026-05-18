import { useEffect, useState } from "react";
import { getTheme, setTheme as persistTheme, type Theme } from "@/lib/theme";

export function useTheme(): { theme: Theme; setTheme: (t: Theme) => void; toggle: () => void } {
  const [theme, setThemeState] = useState<Theme>(() => getTheme());

  useEffect(() => {
    const onChange = (e: Event) => {
      const next = (e as CustomEvent<Theme>).detail;
      if (next === "light" || next === "dark") setThemeState(next);
    };
    window.addEventListener("ov:theme-changed", onChange);
    return () => window.removeEventListener("ov:theme-changed", onChange);
  }, []);

  return {
    theme,
    setTheme: (t) => {
      persistTheme(t);
      setThemeState(t);
    },
    toggle: () => {
      const next: Theme = theme === "dark" ? "light" : "dark";
      persistTheme(next);
      setThemeState(next);
    },
  };
}
