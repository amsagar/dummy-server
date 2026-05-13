import type { FC } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Loader2, Copy, Check } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ChatMessage } from "./types";

const PROSE =
  "prose prose-sm max-w-none text-slate-900 prose-slate prose-p:my-1 prose-li:my-0.5 " +
  "prose-headings:my-2 prose-headings:text-slate-900 prose-p:text-slate-900 " +
  "prose-pre:my-2 prose-pre:max-h-96 prose-pre:overflow-auto prose-pre:rounded-md " +
  "prose-pre:border prose-pre:border-slate-200 prose-pre:bg-slate-50 prose-pre:text-sm " +
  "prose-code:text-slate-900 prose-code:text-[13px] prose-code:bg-slate-100 prose-code:px-1 prose-code:rounded";

type Props = {
  message: ChatMessage;
  spinnerVerb?: string;
  hovered?: boolean;
  copied?: boolean;
  onMouseEnter?: () => void;
  onMouseLeave?: () => void;
  onCopy?: () => void;
};

const AssistantBubble: FC<Props> = ({ message, spinnerVerb = "Thinking", hovered, copied, onMouseEnter, onMouseLeave, onCopy }) => (
  <div className="group relative flex justify-start" onMouseEnter={onMouseEnter} onMouseLeave={onMouseLeave}>
    <div className="max-w-[88%] rounded-2xl rounded-tl-sm border border-slate-200 bg-white px-4 py-3 text-sm text-slate-800 shadow-sm">
      <div className={PROSE}>
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown>
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
      <div className="absolute -bottom-6 left-0">
        <button onClick={onCopy} className="rounded border bg-white p-1.5 text-slate-500 shadow-sm hover:bg-slate-50">
          {copied ? <Check size={11} className="text-green-500" /> : <Copy size={11} />}
        </button>
      </div>
    )}
  </div>
);

export default AssistantBubble;
