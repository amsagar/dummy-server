import * as React from "react";
import { useState, useRef, useEffect } from "react";
import { Check, ChevronDown, Search } from "lucide-react";
import { cn } from "@/lib/utils";

export interface SearchableSelectOption {
  value: string;
  label: string;
  sublabel?: string;
}

interface SearchableSelectProps {
  options: SearchableSelectOption[];
  value?: string;
  onValueChange: (value: string) => void;
  placeholder?: string;
  searchPlaceholder?: string;
  disabled?: boolean;
  className?: string;
  /** Width class for the dropdown panel, e.g. "w-72". Defaults to "w-full". */
  dropdownWidth?: string;
}

export function SearchableSelect({
  options,
  value,
  onValueChange,
  placeholder = "Select…",
  searchPlaceholder = "Search…",
  disabled = false,
  className,
  dropdownWidth,
}: SearchableSelectProps) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const selected = options.find((o) => o.value === value);

  const filtered = options.filter((o) => {
    const q = search.toLowerCase();
    return o.label.toLowerCase().includes(q) || (o.sublabel?.toLowerCase().includes(q) ?? false);
  });

  // Close on outside click
  useEffect(() => {
    function onDown(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
        setSearch("");
      }
    }
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, []);

  // Focus search input when opened
  useEffect(() => {
    if (open) {
      setTimeout(() => inputRef.current?.focus(), 10);
    }
  }, [open]);

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      {/* Trigger */}
      <button
        type="button"
        disabled={disabled}
        className={cn(
          "flex h-9 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background",
          "placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2",
          "disabled:cursor-not-allowed disabled:opacity-50",
          open && "ring-2 ring-ring ring-offset-2"
        )}
        onClick={() => { if (!disabled) setOpen((o) => !o); }}
      >
        <span className={cn("truncate", !selected && "text-muted-foreground")}>
          {selected ? (
            <span>
              {selected.label}
              {selected.sublabel && (
                <span className="ml-1.5 text-xs text-slate-400">{selected.sublabel}</span>
              )}
            </span>
          ) : placeholder}
        </span>
        <ChevronDown size={14} className={cn("shrink-0 text-slate-400 transition-transform", open && "rotate-180")} />
      </button>

      {/* Dropdown */}
      {open && (
        <div className={cn(
          "absolute z-50 mt-1 rounded-md border bg-white shadow-lg",
          dropdownWidth ?? "w-full"
        )}>
          {/* Search box */}
          <div className="flex items-center gap-1.5 border-b px-2 py-1.5">
            <Search size={13} className="text-slate-400 shrink-0" />
            <input
              ref={inputRef}
              className="flex-1 text-xs outline-none placeholder:text-slate-400 bg-transparent"
              placeholder={searchPlaceholder}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") { setOpen(false); setSearch(""); }
                if (e.key === "Enter" && filtered.length === 1) {
                  onValueChange(filtered[0].value);
                  setOpen(false);
                  setSearch("");
                }
              }}
            />
          </div>

          {/* Options list */}
          <div className="max-h-60 overflow-y-auto py-1">
            {filtered.length === 0 ? (
              <div className="px-3 py-2 text-xs text-slate-400">No results found</div>
            ) : (
              filtered.map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  className={cn(
                    "flex w-full items-center gap-2 px-3 py-2 text-left text-xs hover:bg-slate-50 transition-colors",
                    opt.value === value && "bg-blue-50 text-blue-700"
                  )}
                  onClick={() => {
                    onValueChange(opt.value);
                    setOpen(false);
                    setSearch("");
                  }}
                >
                  <Check
                    size={12}
                    className={cn("shrink-0", opt.value === value ? "opacity-100 text-blue-600" : "opacity-0")}
                  />
                  <span className="truncate flex-1">
                    {opt.label}
                    {opt.sublabel && (
                      <span className="ml-1.5 text-[10px] text-slate-400">{opt.sublabel}</span>
                    )}
                  </span>
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
