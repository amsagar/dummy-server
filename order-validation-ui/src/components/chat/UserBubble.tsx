import { useState } from "react";
import { Check, Copy, Pencil, RefreshCcw, User, X } from "lucide-react";
import { Button } from "@/components/ui/button";

interface Props {
  content: string;
  onResend?: () => void;
  onEditResend?: (newContent: string) => void;
}

export function UserBubble({ content, onResend, onEditResend }: Props) {
  const [copied, setCopied] = useState(false);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(content);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    } catch {
      // ignore
    }
  };

  if (editing) {
    return (
      <div className="flex gap-3 max-w-3xl ml-auto justify-end w-full">
        <div className="flex-1 max-w-[80%]">
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            rows={3}
            autoFocus
            className="w-full rounded-2xl border border-border bg-card text-foreground px-4 py-2.5 text-sm leading-relaxed outline-none focus-visible:ring-2 focus-visible:ring-ring/50 resize-none"
          />
          <div className="flex justify-end gap-1.5 mt-1.5">
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setDraft(content);
                setEditing(false);
              }}
            >
              <X className="size-3" /> Cancel
            </Button>
            <Button
              size="sm"
              onClick={() => {
                const next = draft.trim();
                if (!next || !onEditResend) return;
                onEditResend(next);
                setEditing(false);
              }}
              disabled={!draft.trim()}
              className="btn-primary-text"
            >
              <Check className="size-3" /> Send
            </Button>
          </div>
        </div>
        <div className="size-7 rounded-full bg-muted flex items-center justify-center shrink-0">
          <User className="size-3.5 text-muted-foreground" />
        </div>
      </div>
    );
  }

  return (
    <div className="group flex gap-3 max-w-3xl ml-auto justify-end">
      <div className="flex flex-col items-end max-w-[80%]">
        <div className="bg-primary text-white btn-primary-text rounded-2xl rounded-tr-sm px-4 py-2.5 text-sm leading-relaxed whitespace-pre-wrap break-words">
          {content}
        </div>
        <div className="opacity-0 group-hover:opacity-100 flex items-center gap-2 mt-1.5 transition-opacity">
          <button
            onClick={copy}
            className="inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground transition-colors"
            title="Copy"
          >
            {copied ? <Check className="size-3" /> : <Copy className="size-3" />}
            {copied ? "Copied" : "Copy"}
          </button>
          {onEditResend && (
            <button
              onClick={() => {
                setDraft(content);
                setEditing(true);
              }}
              className="inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground transition-colors"
              title="Edit and resend"
            >
              <Pencil className="size-3" /> Edit
            </button>
          )}
          {onResend && (
            <button
              onClick={onResend}
              className="inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground transition-colors"
              title="Resend"
            >
              <RefreshCcw className="size-3" /> Resend
            </button>
          )}
        </div>
      </div>
      <div className="size-7 rounded-full bg-muted flex items-center justify-center shrink-0">
        <User className="size-3.5 text-muted-foreground" />
      </div>
    </div>
  );
}
