import { useEffect, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";

interface SheetProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  description?: string;
  children: ReactNode;
  className?: string;
  width?: string;
}

export function Sheet({
  open,
  onClose,
  title,
  description,
  children,
  className,
  width = "w-[640px] max-w-[90vw]",
}: SheetProps) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;
  return createPortal(
    <div className="fixed inset-0 z-50">
      <div
        className="absolute inset-0 bg-black/40 supports-[backdrop-filter]:backdrop-blur-[2px]"
        onClick={onClose}
      />
      <div
        role="dialog"
        aria-modal="true"
        className={cn(
          "absolute right-0 top-0 h-full bg-card border-l border-border shadow-2xl flex flex-col",
          width,
          className,
        )}
      >
        <div className="flex items-start justify-between gap-4 p-5 border-b border-border">
          <div className="min-w-0">
            {title && <div className="text-base font-semibold text-foreground truncate">{title}</div>}
            {description && (
              <div className="text-xs text-muted-foreground mt-0.5">{description}</div>
            )}
          </div>
          <button
            aria-label="Close"
            onClick={onClose}
            className="shrink-0 inline-flex items-center justify-center size-8 rounded-md text-muted-foreground hover:bg-accent transition-colors"
          >
            <X className="size-4" />
          </button>
        </div>
        <div className="flex-1 overflow-auto p-5">{children}</div>
      </div>
    </div>,
    document.body,
  );
}
