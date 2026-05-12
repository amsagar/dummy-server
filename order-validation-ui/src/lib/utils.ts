import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatNumber(n: number | null | undefined, opts: Intl.NumberFormatOptions = {}): string {
  if (n == null || !Number.isFinite(n)) return "—";
  return new Intl.NumberFormat("en-US", opts).format(n);
}

export function formatPercent(n: number | null | undefined, digits = 1): string {
  if (n == null || !Number.isFinite(n)) return "—";
  return `${n.toFixed(digits)}%`;
}

export function formatStatus(s: string | null | undefined): string {
  if (s == null || s === "") return "—";
  if (s === "na") return "N/A";
  return s
    .split(/[\s_-]+/)
    .map((w) => (w ? w[0].toUpperCase() + w.slice(1).toLowerCase() : w))
    .join(" ");
}

export function formatDateRange(fromTs: number, toTs: number): string {
  const from = new Date(fromTs);
  const to = new Date(toTs);
  const sameDay = from.toDateString() === to.toDateString();
  const fmt = (d: Date) =>
    d.toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
  return sameDay ? fmt(from) : `${fmt(from)} → ${fmt(to)}`;
}
