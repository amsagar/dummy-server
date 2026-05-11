import { Checkbox } from "@/components/ui/checkbox";
import type { FieldProps } from "./StringField";

export function BooleanField({ prop, value, onChange }: FieldProps) {
  const checked = value === true || value === "true";
  return (
    <label className="flex items-center gap-2 text-sm">
      <Checkbox
        checked={checked}
        onCheckedChange={(v) => onChange(v === true)}
      />
      <span className="text-muted-foreground">{prop.description ?? prop.label ?? prop.name}</span>
    </label>
  );
}
