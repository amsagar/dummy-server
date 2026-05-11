// Full run JSON: a single object combining summary + activities + audit so
// it can be downloaded for offline debugging or attached to a bug report.

import MonacoEditor from "@monaco-editor/react";
import { Copy, Download } from "lucide-react";
import { Button } from "@/components/ui/button";
import { toast } from "sonner";
import type { ActivityInst, AuditTrailEntry, RunSummary } from "@/types/workflow";

interface JsonTabProps {
  summary: RunSummary;
  activities: ActivityInst[];
  audit: AuditTrailEntry[];
  runId: string;
}

export function JsonTab({ summary, activities, audit, runId }: JsonTabProps) {
  const dump = {
    runId,
    summary,
    activities,
    audit,
    exportedAt: new Date().toISOString(),
  };
  const text = JSON.stringify(dump, null, 2);

  function download() {
    const blob = new Blob([text], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `run-${runId}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <div className="flex flex-col gap-2 h-[600px]">
      <div className="flex justify-end gap-2">
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            navigator.clipboard.writeText(text);
            toast.success("Copied");
          }}
        >
          <Copy className="w-3.5 h-3.5 mr-1" /> Copy
        </Button>
        <Button variant="outline" size="sm" onClick={download}>
          <Download className="w-3.5 h-3.5 mr-1" /> Download
        </Button>
      </div>
      <div className="flex-1 border rounded-md overflow-hidden">
        <MonacoEditor
          height="100%"
          language="json"
          theme="vs-dark"
          value={text}
          options={{
            readOnly: true,
            minimap: { enabled: false },
            fontSize: 12,
            wordWrap: "on",
          }}
        />
      </div>
    </div>
  );
}
