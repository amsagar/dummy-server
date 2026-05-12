import { useEffect, useRef, useState } from "react";
import type { DateRange } from "@/lib/settings";

interface Props {
  value: DateRange;
  onChange: (r: DateRange) => void;
  onClose: () => void;
}

function toInputValue(ts: number): string {
  const d = new Date(ts);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

function fromInputValue(s: string, endOfDay: boolean): number {
  const [y, m, d] = s.split("-").map(Number);
  const date = new Date(y, m - 1, d);
  if (endOfDay) date.setHours(23, 59, 59, 999);
  else date.setHours(0, 0, 0, 0);
  return date.getTime();
}

export function DateRangePicker({ value, onChange, onClose }: Props) {
  const [from, setFrom] = useState(toInputValue(value.fromTs));
  const [to, setTo] = useState(toInputValue(value.toTs));
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) onClose();
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [onClose]);

  const apply = () => {
    onChange({ fromTs: fromInputValue(from, false), toTs: fromInputValue(to, true) });
  };

  const presets: { label: string; days: number }[] = [
    { label: "Today", days: 0 },
    { label: "7d", days: 6 },
    { label: "30d", days: 29 },
    { label: "90d", days: 89 },
  ];

  return (
    <div
      ref={ref}
      className="absolute right-0 top-12 z-30 bg-card border border-border rounded-lg shadow-xl p-4 w-[320px]"
    >
      <div className="text-xs font-medium text-foreground/80 mb-2">Date range</div>
      <div className="grid grid-cols-2 gap-2 mb-3">
        <label className="flex flex-col gap-1">
          <span className="text-[11px] text-foreground/75">From</span>
          <input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            className="h-9 rounded-md border border-border bg-muted text-foreground px-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-[11px] text-foreground/75">To</span>
          <input
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            className="h-9 rounded-md border border-border bg-muted text-foreground px-2 text-sm"
          />
        </label>
      </div>
      <div className="flex flex-wrap gap-1.5 mb-3">
        {presets.map((p) => (
          <button
            key={p.label}
            className="px-2.5 py-1 rounded-md border border-border bg-muted text-foreground text-xs font-medium hover:bg-accent transition-colors"
            onClick={() => {
              const end = new Date();
              end.setHours(23, 59, 59, 999);
              const start = new Date(end);
              start.setDate(end.getDate() - p.days);
              start.setHours(0, 0, 0, 0);
              setFrom(toInputValue(start.getTime()));
              setTo(toInputValue(end.getTime()));
            }}
          >
            {p.label}
          </button>
        ))}
      </div>
      <div className="flex justify-end gap-2">
        <button
          onClick={onClose}
          className="px-3 h-8 rounded-md border border-border bg-muted text-foreground text-sm font-medium hover:bg-accent transition-colors"
        >
          Cancel
        </button>
        <button
          onClick={apply}
          className="px-3 h-8 rounded-md bg-primary text-white btn-primary-text text-sm font-medium hover:bg-primary/90 transition-colors"
        >
          Apply
        </button>
      </div>
    </div>
  );
}
