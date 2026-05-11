import { Input } from "@/components/ui/input";
import { ExpressionField } from "../ExpressionField";
import type { FieldProps } from "./StringField";

export function NumberField({ prop, value, onChange }: FieldProps) {
  return (
    <ExpressionField
      expressionAllowed={!!prop.expressionAllowed}
      value={value}
      onChange={(v) => {
        // In expression mode the wrapper passes back the wrapped string;
        // in literal mode we coerce empty -> null and otherwise -> Number.
        if (typeof v === "string" && v.startsWith("#{")) {
          onChange(v);
        } else if (v == null || v === "") {
          onChange(null);
        } else {
          const n = Number(v);
          onChange(Number.isFinite(n) ? n : v);
        }
      }}
    >
      {(literal, setLiteral) => (
        <Input
          type="number"
          value={literal}
          placeholder={prop.placeholder ?? undefined}
          onChange={(e) => setLiteral(e.target.value)}
        />
      )}
    </ExpressionField>
  );
}
