import * as React from "react";
import { useState, useRef, useEffect } from "react";
import { Check, ChevronDown, Search } from "lucide-react";
import { cn } from "@/lib/utils";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

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
  /** Optional title shown on hover for the trigger button. */
  triggerTitle?: string;
  /** Accessible label for the trigger button. */
  triggerAriaLabel?: string;
  /** Optional rich tooltip text shown over the trigger. */
  tooltip?: string;
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
  triggerTitle,
  triggerAriaLabel,
  tooltip,
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

  const triggerButton = (
    <button
      type="button"
      disabled={disabled}
      title={triggerTitle ?? selected?.label ?? placeholder}
      aria-label={triggerAriaLabel ?? placeholder}
      className={cn(
        "flex h-9 w-full items-center justify-between rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800 shadow-sm ring-offset-background transition-all",
        "hover:border-slate-400 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-[#005CB9]/30 focus:ring-offset-1",
        "disabled:cursor-not-allowed disabled:opacity-50",
        open && "border-[#005CB9]/50 ring-2 ring-[#005CB9]/25"
      )}
      onClick={() => { if (!disabled) setOpen((o) => !o); }}
    >
      <span className={cn("truncate", !selected && "text-muted-foreground")}>
        {selected ? selected.label : placeholder}
      </span>
      <ChevronDown size={14} className={cn("shrink-0 text-slate-400 transition-transform", open && "rotate-180")} />
    </button>
  );

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      {/* Trigger */}
      {tooltip ? (
        <Tooltip>
          <TooltipTrigger asChild>{triggerButton}</TooltipTrigger>
          <TooltipContent side="top"><p>{tooltip}</p></TooltipContent>
        </Tooltip>
      ) : triggerButton}

      {/* Dropdown */}
      {open && (
        <div className={cn(
          "absolute z-[70] mt-1 rounded-xl border border-slate-200 bg-white shadow-xl",
          dropdownWidth ?? "w-full"
        )}>
          {/* Search box */}
          <div className="flex items-center gap-1.5 border-b border-slate-100 px-2.5 py-2">
            <Search size={13} className="text-slate-400 shrink-0" />
            <input
              ref={inputRef}
              className="flex-1 bg-transparent text-xs text-slate-700 outline-none placeholder:text-slate-400"
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
          <div className="max-h-64 overflow-y-auto py-1.5">
            {filtered.length === 0 ? (
              <div className="px-3 py-3 text-xs text-slate-400">No results found</div>
            ) : (
              filtered.map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  className={cn(
                    "flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-xs transition-colors",
                    opt.value === value
                      ? "bg-blue-50 text-blue-700"
                      : "text-slate-700 hover:bg-slate-50"
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
