import { CalendarRange, Moon, Sun } from "lucide-react";
import { useSettings } from "@/hooks/useSettings";
import { useTheme } from "@/hooks/useTheme";
import { formatDateRange } from "@/lib/utils";
import { DateRangePicker } from "./DateRangePicker";
import { useState } from "react";

interface TopBarProps {
  title: string;
  subtitle?: string;
}

export function TopBar({ title, subtitle }: TopBarProps) {
  const { dateRange, setDateRange } = useSettings();
  const { theme, toggle } = useTheme();
  const [open, setOpen] = useState(false);
  return (
    <header className="h-16 flex items-center justify-between px-6 border-b border-border bg-card">
      <div>
        <div className="text-base font-semibold">{title}</div>
        {subtitle && <div className="text-xs text-foreground/80 mt-0.5">{subtitle}</div>}
      </div>
      <div className="flex items-center gap-2 relative">
        <button
          onClick={() => setOpen((o) => !o)}
          className="inline-flex items-center gap-2 h-9 px-3 rounded-md border border-border bg-muted text-foreground text-sm font-medium hover:bg-accent transition-colors"
        >
          <CalendarRange className="size-4 text-foreground/80" />
          <span>{formatDateRange(dateRange.fromTs, dateRange.toTs)}</span>
        </button>
        <button
          onClick={toggle}
          aria-label={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
          title={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
          className="inline-flex items-center justify-center size-9 rounded-md border border-border bg-muted hover:bg-accent transition-colors"
        >
          {theme === "dark" ? (
            <Sun className="size-4 text-foreground/80" />
          ) : (
            <Moon className="size-4 text-foreground/80" />
          )}
        </button>
        {open && (
          <DateRangePicker
            value={dateRange}
            onChange={(r) => {
              setDateRange(r);
              setOpen(false);
            }}
            onClose={() => setOpen(false)}
          />
        )}
      </div>
    </header>
  );
}
