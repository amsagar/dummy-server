import { useState } from "react";
import { Check, Copy } from "lucide-react";

interface Props {
  value: unknown;
  label?: string;
  maxHeight?: string;
}

export function JsonBlock({ value, label, maxHeight = "max-h-[40vh]" }: Props) {
  const [copied, setCopied] = useState(false);
  const text = value === undefined ? "" : JSON.stringify(value, null, 2);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    } catch {
      // no-op — clipboard API may be blocked in non-https; fail silent
    }
  };

  return (
    <div className="space-y-1.5">
      {(label || text) && (
        <div className="flex items-center justify-between">
          {label && <div className="text-xs font-medium text-foreground/80">{label}</div>}
          {text && (
            <button
              onClick={copy}
              className="inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground transition-colors"
            >
              {copied ? <Check className="size-3" /> : <Copy className="size-3" />}
              {copied ? "Copied" : "Copy"}
            </button>
          )}
        </div>
      )}
      <pre className={`text-xs font-mono bg-muted border border-border rounded-md p-3 overflow-auto ${maxHeight}`}>
        {text || <span className="text-muted-foreground">—</span>}
      </pre>
    </div>
  );
}
