import { useState } from "react";
import { Bot, Check, Copy, RefreshCcw } from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

interface Props {
  content: string;
  streaming?: boolean;
  streamingVerb?: string;
  onRegenerate?: () => void;
}

export function AssistantBubble({ content, streaming, streamingVerb, onRegenerate }: Props) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    } catch {
      // ignore
    }
  };

  return (
    <div className="group flex gap-3 max-w-3xl">
      <div className="size-7 rounded-full bg-primary/15 flex items-center justify-center shrink-0">
        <Bot className="size-3.5 text-primary" />
      </div>
      <div className="flex-1 min-w-0">
        <div className="prose prose-sm dark:prose-invert max-w-none text-sm leading-relaxed text-foreground">
          {content ? (
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
          ) : streaming ? (
            <span className="text-muted-foreground italic">
              {(streamingVerb ?? "Thinking")}…
            </span>
          ) : null}
          {content && streaming && (
            <span className="inline-block ml-1 size-1.5 rounded-full bg-primary animate-pulse" />
          )}
        </div>
        {content && !streaming && (
          <div className="opacity-0 group-hover:opacity-100 flex items-center gap-2 mt-1.5 transition-opacity">
            <button
              onClick={copy}
              className="inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground transition-colors"
              title="Copy"
            >
              {copied ? <Check className="size-3" /> : <Copy className="size-3" />}
              {copied ? "Copied" : "Copy"}
            </button>
            {onRegenerate && (
              <button
                onClick={onRegenerate}
                className="inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground transition-colors"
                title="Regenerate"
              >
                <RefreshCcw className="size-3" /> Regenerate
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
