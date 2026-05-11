// Right-rail inspector for a selected edge. Smaller surface than node
// inspector — just condition + error-edge flags + matchesErrorClass.

import { X } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { BoardEdgeData } from "@/lib/workflowSerializer";
import type { ErrorClass, TransitionTrigger } from "@/types/workflow";

const ERROR_CLASSES: ErrorClass[] = [
  "EXPRESSION",
  "VALIDATION",
  "TIMEOUT",
  "TOOL",
  "SUBFLOW",
  "UNCAUGHT",
];

const TRIGGERS: Array<{ value: TransitionTrigger; label: string }> = [
  { value: "ON_SUCCESS", label: "On success" },
  { value: "ON_NO_MATCH", label: "On no-match/default" },
  { value: "ON_ERROR", label: "On error" },
  { value: "ON_TIMEOUT", label: "On timeout" },
  { value: "ON_VALIDATION_ERROR", label: "On validation error" },
];

interface EdgeInspectorPanelProps {
  edgeId: string;
  data: BoardEdgeData;
  onChange: (next: BoardEdgeData) => void;
  onClose: () => void;
}

export function EdgeInspectorPanel({ edgeId, data, onChange, onClose }: EdgeInspectorPanelProps) {
  return (
    <aside className="w-[380px] border-l flex flex-col bg-background">
      <header className="border-b p-3 flex items-center gap-2">
        <div className="flex flex-col leading-tight overflow-hidden flex-1">
          <span className="text-sm font-medium truncate">Transition</span>
          <span className="text-[11px] text-muted-foreground truncate">{edgeId}</span>
        </div>
        <Button variant="ghost" size="sm" onClick={onClose} aria-label="Close inspector">
          <X className="w-4 h-4" />
        </Button>
      </header>
      <ScrollArea className="flex-1">
        <div className="p-3 flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium">Condition (SecureSpel)</label>
            <Input
              value={data.condition ?? ""}
              placeholder="e.g. #approved == true"
              onChange={(e) => onChange({ ...data, condition: e.target.value || null })}
            />
            <p className="text-xs text-muted-foreground">
              Leave empty for an unconditional transition. Multiple matching outgoing transitions
              from a single activity create an AND-split.
            </p>
          </div>

          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium">Trigger</label>
            <Select
              value={data.trigger ?? "ON_SUCCESS"}
              onValueChange={(v) =>
                onChange({
                  ...data,
                  trigger: v as TransitionTrigger,
                })
              }
            >
              <SelectTrigger>
                <SelectValue placeholder="On success" />
              </SelectTrigger>
              <SelectContent>
                {TRIGGERS.map((t) => (
                  <SelectItem key={t.value} value={t.value}>
                    {t.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium">Priority (optional)</label>
            <Input
              value={data.priority ?? ""}
              placeholder="lower value wins"
              type="number"
              onChange={(e) =>
                onChange({
                  ...data,
                  priority: e.target.value === "" ? null : Number(e.target.value),
                })
              }
            />
          </div>

          <label className="flex items-center gap-2 text-sm">
            <Checkbox
              checked={!!data.isDefault}
              onCheckedChange={(v) => onChange({ ...data, isDefault: v === true })}
            />
            <span className="text-muted-foreground">Use as default/no-match edge</span>
          </label>

          <label className="flex items-center gap-2 text-sm">
            <Checkbox
              checked={!!data.isErrorEdge}
              onCheckedChange={(v) => onChange({ ...data, isErrorEdge: v === true })}
            />
            <span className="text-muted-foreground">
              Error edge (taken only when the source activity fails)
            </span>
          </label>

          {data.isErrorEdge && (
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium">Match error class</label>
              <Select
                value={data.matchesErrorClass ?? "any"}
                onValueChange={(v) =>
                  onChange({
                    ...data,
                    matchesErrorClass: v === "any" ? null : (v as ErrorClass),
                  })
                }
              >
                <SelectTrigger>
                  <SelectValue placeholder="any error" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="any">any error</SelectItem>
                  {ERROR_CLASSES.map((c) => (
                    <SelectItem key={c} value={c}>
                      {c}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}
        </div>
      </ScrollArea>
    </aside>
  );
}
