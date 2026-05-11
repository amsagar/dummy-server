// Evaluates n8n-style displayOptions show/hide rules against the current
// form state. A field is visible iff:
//   - every show entry's field value is in the allowed list (any-of), AND
//   - no hide entry's field value is in the disallowed list.
// When show / hide are both null/empty, the field is always visible.

import type { PluginPropertyDescriptor } from "@/types/workflow";

export function isFieldVisible(
  prop: PluginPropertyDescriptor,
  values: Record<string, unknown>,
): boolean {
  const opts = prop.displayOptions;
  if (!opts) return true;

  if (opts.show && Object.keys(opts.show).length > 0) {
    for (const [field, allowed] of Object.entries(opts.show)) {
      const v = stringify(values[field]);
      if (!allowed.includes(v)) return false;
    }
  }

  if (opts.hide && Object.keys(opts.hide).length > 0) {
    for (const [field, disallowed] of Object.entries(opts.hide)) {
      const v = stringify(values[field]);
      if (disallowed.includes(v)) return false;
    }
  }

  return true;
}

function stringify(v: unknown): string {
  if (v == null) return "";
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return JSON.stringify(v);
}
