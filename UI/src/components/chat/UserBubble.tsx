import type { CSSProperties, FC } from "react";
import { Pencil, Copy, Check, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

type Props = {
  content: string;
  hovered?: boolean;
  copied?: boolean;
  isEditing?: boolean;
  editDraft?: string;
  isStreaming?: boolean;
  onMouseEnter?: () => void;
  onMouseLeave?: () => void;
  onCopy?: () => void;
  onResend?: () => void;
  onStartEdit?: () => void;
  onCancelEdit?: () => void;
  onChangeEditDraft?: (value: string) => void;
  onSubmitEdit?: () => void;
  className?: string;
  bubbleStyle?: CSSProperties;
  bubbleClassName?: string;
};

const UserBubble: FC<Props> = ({
  content,
  hovered = false,
  copied = false,
  isEditing = false,
  editDraft = "",
  isStreaming = false,
  onMouseEnter,
  onMouseLeave,
  onCopy,
  onResend,
  onStartEdit,
  onCancelEdit,
  onChangeEditDraft,
  onSubmitEdit,
  className,
  bubbleStyle,
  bubbleClassName,
}) => {
  return (
    <div
      className={cn("flex items-end justify-end gap-2", className)}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
    >
      {hovered && !isEditing && (
        <div className="flex shrink-0 items-center gap-1">
          {onStartEdit && (
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  onClick={onStartEdit}
                  className="rounded border bg-white p-1.5 text-slate-500 shadow-sm hover:bg-slate-50"
                >
                  <Pencil size={11} />
                </button>
              </TooltipTrigger>
              <TooltipContent side="top"><p>Edit &amp; resend</p></TooltipContent>
            </Tooltip>
          )}
          {onCopy && (
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  onClick={onCopy}
                  className="rounded border bg-white p-1.5 text-slate-500 shadow-sm hover:bg-slate-50"
                >
                  {copied ? <Check size={11} className="text-green-500" /> : <Copy size={11} />}
                </button>
              </TooltipTrigger>
              <TooltipContent side="top"><p>{copied ? "Copied!" : "Copy"}</p></TooltipContent>
            </Tooltip>
          )}
          {onResend && (
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  onClick={onResend}
                  disabled={isStreaming}
                  className="rounded border bg-white p-1.5 text-slate-500 shadow-sm hover:bg-slate-50 disabled:opacity-40"
                >
                  <RotateCcw size={11} />
                </button>
              </TooltipTrigger>
              <TooltipContent side="top"><p>Resend</p></TooltipContent>
            </Tooltip>
          )}
        </div>
      )}
      {isEditing ? (
        <div className="flex w-[82%] flex-col gap-2">
          <textarea
            value={editDraft}
            onChange={(e) => onChangeEditDraft?.(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                onSubmitEdit?.();
              }
              if (e.key === "Escape") onCancelEdit?.();
            }}
            rows={Math.max(2, editDraft.split("\n").length)}
            autoFocus
            className="w-full resize-none rounded-xl border border-[#005CB9] px-4 py-2.5 text-sm text-gray-800 outline-none focus:ring-2 focus:ring-[#005CB9]/30"
          />
          <div className="flex justify-end gap-2">
            <Button size="sm" variant="outline" onClick={onCancelEdit}>Cancel</Button>
            <Button
              size="sm"
              className="text-white"
              style={{ background: "#005CB9" }}
              onClick={onSubmitEdit}
              disabled={isStreaming || !editDraft.trim()}
            >
              Send
            </Button>
          </div>
        </div>
      ) : (
        <div
          className={cn(
            "max-w-[82%] rounded-2xl rounded-tr-sm px-4 py-2.5 text-sm text-white",
            bubbleClassName
          )}
          style={bubbleStyle ?? { background: "#005CB9" }}
        >
          {content}
        </div>
      )}
    </div>
  );
};

export default UserBubble;
