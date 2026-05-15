import { AlertTriangle } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface ConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: React.ReactNode;
  /** Optional secondary detail block rendered above the description in
   *  a muted card — good for "you're about to delete X" identifying info. */
  detail?: React.ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  /** "danger" gives the confirm button a red treatment; default is neutral. */
  tone?: "danger" | "default";
  /** Set to true to disable the confirm button (e.g. while the mutation is in flight). */
  busy?: boolean;
  onConfirm: () => void;
}

export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  detail,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  tone = "default",
  busy = false,
  onConfirm,
}: ConfirmDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            {tone === "danger" && <AlertTriangle className="h-5 w-5 text-red-600" />}
            {title}
          </DialogTitle>
          {description && (
            <DialogDescription className="pt-1 text-sm text-gray-600">
              {description}
            </DialogDescription>
          )}
        </DialogHeader>

        {detail && (
          <div className="rounded border bg-gray-50 px-3 py-2 text-xs text-gray-700">
            {detail}
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            {cancelLabel}
          </Button>
          <Button
            onClick={onConfirm}
            disabled={busy}
            className={
              tone === "danger"
                ? "bg-red-600 text-white hover:bg-red-700"
                : undefined
            }
          >
            {busy ? "Working…" : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
