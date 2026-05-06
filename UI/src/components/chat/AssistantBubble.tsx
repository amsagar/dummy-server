import type { FC } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Brain, ChevronRight, Loader2, Copy, Check } from "lucide-react";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { useMarkdownComponents } from "./markdownComponents";
import type { ChatMessage } from "./types";

type Props = {
  message: ChatMessage;
  spinnerVerb?: string;
  hovered?: boolean;
  copied?: boolean;
  onMouseEnter?: () => void;
  onMouseLeave?: () => void;
  onCopy?: () => void;
  className?: string;
  bubbleClassName?: string;
  proseClassName?: string;
};

const DEFAULT_PROSE =
  "prose prose-sm max-w-none text-slate-900 prose-slate prose-p:my-1 prose-li:my-0.5 prose-headings:my-2 " +
  "prose-headings:text-slate-900 prose-p:text-slate-900 prose-li:text-slate-900 prose-strong:text-slate-900 " +
  "prose-table:text-slate-900 prose-th:text-slate-900 prose-td:text-slate-900 " +
  "prose-pre:my-2 prose-pre:max-h-[28rem] prose-pre:overflow-auto prose-pre:rounded-md prose-pre:border prose-pre:border-slate-300 prose-pre:bg-slate-100 prose-pre:text-slate-900 prose-pre:text-sm " +
  "prose-code:text-slate-900 prose-code:text-[13px] prose-code:bg-slate-100 prose-code:px-1 prose-code:rounded " +
  "[&_*]:!text-slate-900";

const AssistantBubble: FC<Props> = ({
  message,
  spinnerVerb = "Thinking",
  hovered = false,
  copied = false,
  onMouseEnter,
  onMouseLeave,
  onCopy,
  className,
  bubbleClassName,
  proseClassName,
}) => {
  const markdownComponents = useMarkdownComponents();

  return (
    <div
      className={cn("group relative flex justify-start", className)}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
    >
      <div
        className={cn(
          "max-w-[88%] rounded-2xl rounded-tl-sm border border-slate-300 bg-white px-4 py-3 text-sm text-slate-800 shadow-sm",
          bubbleClassName
        )}
      >
        {message.reasoning && (
          <details className="group/think mb-3 rounded-lg border border-slate-300 bg-slate-100 text-xs">
            <summary className="flex cursor-pointer list-none items-center gap-1.5 px-3 py-1.5 text-slate-800 [&::-webkit-details-marker]:hidden">
              <Brain size={11} className="shrink-0" />
              <span className="flex-1 font-medium">
                {message.isStreaming && !message.content ? "Thinking…" : "Reasoning"}
              </span>
              <ChevronRight size={10} className="shrink-0 text-slate-500 transition-transform group-open/think:rotate-90" />
            </summary>
            <div className="border-t border-slate-300 px-3 py-2 font-mono text-[11px] leading-relaxed text-slate-800 whitespace-pre-wrap">
              {message.reasoning}
            </div>
          </details>
        )}
        <div className={cn(proseClassName ?? DEFAULT_PROSE)}>
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
            {message.content}
          </ReactMarkdown>
        </div>
        {message.isStreaming && (
          <div className="mt-2 inline-flex items-center gap-1.5 text-xs text-slate-500">
            <Loader2 size={12} className="animate-spin" />
            <span>{spinnerVerb}...</span>
            <span className="inline-block h-4 w-1.5 animate-pulse rounded-sm bg-slate-400" />
          </div>
        )}
      </div>
      {hovered && !message.isStreaming && onCopy && (
        <div className="absolute -bottom-6 left-0 flex items-center gap-1">
          <Tooltip>
            <TooltipTrigger asChild>
              <button onClick={onCopy} className="rounded border bg-white p-1.5 text-slate-500 shadow-sm hover:bg-slate-50">
                {copied ? <Check size={11} className="text-green-500" /> : <Copy size={11} />}
              </button>
            </TooltipTrigger>
            <TooltipContent side="bottom"><p>{copied ? "Copied!" : "Copy"}</p></TooltipContent>
          </Tooltip>
        </div>
      )}
    </div>
  );
};

export default AssistantBubble;
