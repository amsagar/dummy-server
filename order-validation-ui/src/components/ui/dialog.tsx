import { useEffect, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";

interface DialogProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  description?: string;
  children: ReactNode;
  className?: string;
}

export function Dialog({ open, onClose, title, description, children, className }: DialogProps) {
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
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div
        className="absolute inset-0 bg-black/40 supports-[backdrop-filter]:backdrop-blur-[2px]"
        onClick={onClose}
      />
      <div
        role="dialog"
        aria-modal="true"
        className={cn(
          "relative z-10 w-full max-w-sm rounded-xl bg-card border border-border shadow-xl p-5",
          className,
        )}
      >
        <button
          aria-label="Close"
          onClick={onClose}
          className="absolute top-3 right-3 inline-flex items-center justify-center size-7 rounded-md text-muted-foreground hover:bg-accent transition-colors"
        >
          <X className="size-4" />
        </button>
        {title && (
          <div className="mb-1 text-base font-semibold text-foreground pr-8">{title}</div>
        )}
        {description && (
          <div className="text-xs text-muted-foreground mb-4">{description}</div>
        )}
        {children}
      </div>
    </div>,
    document.body,
  );
}
