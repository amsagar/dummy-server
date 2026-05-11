import { Input } from "@/components/ui/input";
import { ExpressionField } from "../ExpressionField";
import type { PluginPropertyDescriptor } from "@/types/workflow";

export interface FieldProps {
  prop: PluginPropertyDescriptor;
  value: unknown;
  onChange: (value: unknown) => void;
}

export function StringField({ prop, value, onChange }: FieldProps) {
  return (
    <ExpressionField
      expressionAllowed={!!prop.expressionAllowed}
      value={value}
      onChange={onChange}
    >
      {(literal, setLiteral) => (
        <Input
          value={literal}
          placeholder={prop.placeholder ?? undefined}
          onChange={(e) => setLiteral(e.target.value)}
        />
      )}
    </ExpressionField>
  );
}
