// JSON field — Monaco with JSON language. Stores either a string (when the
// model can't parse), or the parsed value (object/array/etc.) when valid.
// We persist the raw string so the user can keep editing in-flight invalid
// JSON without losing it; a parse error is shown beneath the editor.

import { useEffect, useState } from "react";
import MonacoEditor from "@monaco-editor/react";
import type { FieldProps } from "./StringField";

function toEditorString(v: unknown): string {
  if (v == null) return "";
  if (typeof v === "string") return v;
  try {
    return JSON.stringify(v, null, 2);
  } catch {
    return String(v);
  }
}

export function JsonField({ prop, value, onChange }: FieldProps) {
  const [text, setText] = useState<string>(() => toEditorString(value));
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Reset editor text when the underlying value changes from outside (e.g. node switch).
    setText(toEditorString(value));
    setError(null);
  }, [value]);

  return (
    <div className="flex flex-col gap-1">
      <div className="border rounded-md overflow-hidden">
        <MonacoEditor
          height="160px"
          language="json"
          theme="vs-dark"
          value={text}
          onChange={(v) => {
            const next = v ?? "";
            setText(next);
            // Best-effort parse on every keystroke. Persist the raw string
            // when it doesn't parse so we don't drop in-flight edits.
            const trimmed = next.trim();
            if (!trimmed) {
              setError(null);
              onChange(null);
              return;
            }
            try {
              const parsed = JSON.parse(trimmed);
              setError(null);
              onChange(parsed);
            } catch (e) {
              setError((e as Error).message);
              onChange(next);
            }
          }}
          options={{
            minimap: { enabled: false },
            fontSize: 12,
            scrollBeyondLastLine: false,
            tabSize: 2,
            wordWrap: "on",
          }}
        />
      </div>
      {prop.description && !error && (
        <p className="text-xs text-muted-foreground">{prop.description}</p>
      )}
      {error && <p className="text-xs text-red-600">JSON: {error}</p>}
    </div>
  );
}
