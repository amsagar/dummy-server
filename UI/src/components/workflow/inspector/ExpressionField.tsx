// Wrapper that adds the n8n-style fixed/expression toggle to any field.
//
// When `expressionAllowed` is false, the wrapper is a no-op around children.
// When the user toggles to expression mode, the value is rewritten to
// "#{...}". Toggling back unwraps if the value is "#{<inner>}", otherwise
// keeps it (to avoid silently dropping work).
//
// Detection of expression mode is automatic: a string value starting with
// "#{" and ending with "}" is treated as an expression.

import { Code, Type as TypeIcon } from "lucide-react";
import { useEffect, useState } from "react";
import MonacoEditor from "@monaco-editor/react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface ExpressionFieldProps {
  expressionAllowed?: boolean;
  value: unknown;
  onChange: (value: unknown) => void;
  /** The literal-mode child (e.g. an <Input/>). Receives controlled value/onChange. */
  children: (literalValue: string, setLiteralValue: (v: string) => void) => React.ReactNode;
  /** Optional Monaco language for expression editor; defaults to "javascript". */
  expressionLanguage?: string;
}

const PREFIX = "#{";
const SUFFIX = "}";

function isExpression(v: unknown): v is string {
  return typeof v === "string" && v.startsWith(PREFIX) && v.endsWith(SUFFIX);
}
function unwrap(v: string): string {
  return v.slice(PREFIX.length, v.length - SUFFIX.length);
}
function wrap(inner: string): string {
  return PREFIX + inner + SUFFIX;
}

export function ExpressionField({
  expressionAllowed = false,
  value,
  onChange,
  children,
  expressionLanguage = "javascript",
}: ExpressionFieldProps) {
  const startsAsExpr = isExpression(value);
  const [exprMode, setExprMode] = useState<boolean>(!!startsAsExpr);

  // Keep the toggle in sync if the value is replaced from outside (e.g.
  // node selection switch).
  useEffect(() => {
    setExprMode(isExpression(value));
  }, [value]);

  if (!expressionAllowed) {
    return (
      <>{children(value == null ? "" : String(value), (v) => onChange(v === "" ? null : v))}</>
    );
  }

  const literalValue = exprMode ? "" : value == null ? "" : String(value);
  const exprInner = isExpression(value) ? unwrap(value) : "";

  return (
    <div className="flex flex-col gap-1">
      <div className="flex justify-end">
        <div className="inline-flex rounded-md border bg-muted p-0.5 text-xs">
          <Button
            type="button"
            size="sm"
            variant={exprMode ? "ghost" : "default"}
            className={cn("h-6 px-2 text-xs", !exprMode && "shadow-sm")}
            onClick={() => {
              if (exprMode) {
                // Switch to fixed: keep literal as the unwrapped inner if any.
                setExprMode(false);
                onChange(exprInner === "" ? null : exprInner);
              }
            }}
          >
            <TypeIcon className="w-3 h-3 mr-1" /> Fixed
          </Button>
          <Button
            type="button"
            size="sm"
            variant={exprMode ? "default" : "ghost"}
            className={cn("h-6 px-2 text-xs", exprMode && "shadow-sm")}
            onClick={() => {
              if (!exprMode) {
                setExprMode(true);
                // Wrap current literal as the expression seed.
                onChange(wrap(literalValue));
              }
            }}
          >
            <Code className="w-3 h-3 mr-1" /> Expression
          </Button>
        </div>
      </div>
      {exprMode ? (
        <div className="border rounded-md overflow-hidden">
          <MonacoEditor
            height="80px"
            language={expressionLanguage}
            theme="vs-dark"
            value={exprInner}
            onChange={(v) => onChange(wrap(v ?? ""))}
            options={{
              minimap: { enabled: false },
              lineNumbers: "off",
              scrollBeyondLastLine: false,
              fontSize: 12,
              padding: { top: 6, bottom: 6 },
              folding: false,
              renderLineHighlight: "none",
              overviewRulerLanes: 0,
              hideCursorInOverviewRuler: true,
              scrollbar: { vertical: "hidden", horizontal: "hidden" },
              wordWrap: "on",
            }}
          />
        </div>
      ) : (
        children(literalValue, (v) => onChange(v === "" ? null : v))
      )}
    </div>
  );
}
