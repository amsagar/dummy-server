// CODE field — Monaco editor whose language follows a sibling 'language'
// field when present (used by CodeExecPlugin). Falls back to javascript.

import MonacoEditor from "@monaco-editor/react";
import type { FieldProps } from "./StringField";

interface CodeFieldProps extends FieldProps {
  /** Sibling form values, so CODE can pick up the chosen 'language'. */
  siblingValues?: Record<string, unknown>;
}

const SUPPORTED = new Set(["javascript", "typescript", "python", "java"]);

export function CodeField({ prop, value, onChange, siblingValues }: CodeFieldProps) {
  const langRaw = siblingValues?.language;
  const lang = typeof langRaw === "string" && SUPPORTED.has(langRaw) ? langRaw : "javascript";

  return (
    <div className="border rounded-md overflow-hidden">
      <MonacoEditor
        height="220px"
        language={lang}
        theme="vs-dark"
        value={value == null ? "" : String(value)}
        onChange={(v) => onChange(v ?? "")}
        options={{
          minimap: { enabled: false },
          fontSize: 12,
          scrollBeyondLastLine: false,
          tabSize: 2,
          wordWrap: "on",
        }}
      />
      {prop.description && (
        <p className="text-xs text-muted-foreground p-2 border-t bg-muted/40">
          {prop.description}
        </p>
      )}
    </div>
  );
}
