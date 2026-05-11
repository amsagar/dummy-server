// Side-by-side diff of two workflow versions. Both panes use Monaco's diff
// editor with read-only JSON. The user picks two versions from the same
// workflow's `versions` endpoint.

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { DiffEditor } from "@monaco-editor/react";
import { ArrowLeft, Loader2 } from "lucide-react";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { workflowApi } from "@/services/workflowApi";
import type { ProcessDef } from "@/types/workflow";

function describe(v: ProcessDef): string {
  return `v${v.version}${v.id ? ` · ${v.id.slice(0, 8)}` : ""}`;
}

export default function WorkflowVersionDiffPage() {
  const { id } = useParams<{ id: string }>();

  const versions = useQuery<ProcessDef[]>({
    queryKey: ["workflow-versions", id],
    queryFn: () => workflowApi.processes.versions(id!),
    enabled: !!id,
  });

  const [leftId, setLeftId] = useState<string>("");
  const [rightId, setRightId] = useState<string>("");

  // Default selection: oldest on the left, newest on the right.
  useEffect(() => {
    const list = versions.data ?? [];
    if (list.length < 1) return;
    if (!leftId) setLeftId(list[list.length - 1]?.id ?? "");
    if (!rightId) setRightId(list[0]?.id ?? "");
  }, [versions.data, leftId, rightId]);

  const left = versions.data?.find((v) => v.id === leftId);
  const right = versions.data?.find((v) => v.id === rightId);

  if (versions.isLoading) {
    return (
      <div className="p-6 flex items-center gap-2 text-muted-foreground">
        <Loader2 className="w-4 h-4 animate-spin" /> Loading versions…
      </div>
    );
  }

  const list = versions.data ?? [];

  return (
    <div className="flex flex-col h-screen">
      <header className="border-b p-3 flex items-center gap-3">
        <Link
          to={`/workflows/${id}/designer`}
          className="inline-flex items-center text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="w-4 h-4 mr-1" /> Back to designer
        </Link>
        <h1 className="text-base font-semibold">Compare versions</h1>
        <span className="text-xs text-muted-foreground">{list.length} version(s)</span>
        <div className="ml-auto flex gap-2 items-center">
          <Select value={leftId} onValueChange={setLeftId}>
            <SelectTrigger className="h-8 text-xs w-[220px]">
              <SelectValue placeholder="Left version…" />
            </SelectTrigger>
            <SelectContent>
              {list.map((v) =>
                v.id ? (
                  <SelectItem key={v.id} value={v.id}>
                    {describe(v)}
                  </SelectItem>
                ) : null,
              )}
            </SelectContent>
          </Select>
          <span className="text-muted-foreground">→</span>
          <Select value={rightId} onValueChange={setRightId}>
            <SelectTrigger className="h-8 text-xs w-[220px]">
              <SelectValue placeholder="Right version…" />
            </SelectTrigger>
            <SelectContent>
              {list.map((v) =>
                v.id ? (
                  <SelectItem key={v.id} value={v.id}>
                    {describe(v)}
                  </SelectItem>
                ) : null,
              )}
            </SelectContent>
          </Select>
        </div>
      </header>

      <div className="flex-1 overflow-hidden">
        {!left || !right ? (
          <div className="p-10 text-sm text-muted-foreground text-center">
            Pick two versions to compare.
          </div>
        ) : (
          <DiffEditor
            height="100%"
            theme="vs-dark"
            language="json"
            original={JSON.stringify(left, null, 2)}
            modified={JSON.stringify(right, null, 2)}
            options={{
              readOnly: true,
              minimap: { enabled: false },
              renderSideBySide: true,
              fontSize: 12,
            }}
          />
        )}
      </div>
    </div>
  );
}
