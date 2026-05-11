import type { PluginPropertyDescriptor } from "@/types/workflow";

export function NoticeField({ prop }: { prop: PluginPropertyDescriptor }) {
  return (
    <div className="text-xs text-muted-foreground bg-muted/50 border border-border rounded-md p-2">
      {prop.description ?? prop.label ?? prop.name}
    </div>
  );
}
