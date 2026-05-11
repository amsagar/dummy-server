// Power-user JSON drawer. Slides in from the right, shows the current
// ProcessDef as pretty-printed JSON. Editable on toggle; Apply parses and
// replaces the canvas via the existing serializer.

import { useEffect, useState } from "react";
import MonacoEditor from "@monaco-editor/react";
import { Pencil, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { toast } from "sonner";
import type { ProcessDef } from "@/types/workflow";

interface JsonDrawerProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  current: ProcessDef;
  onApply: (def: ProcessDef) => void;
}

export function JsonDrawer({ open, onOpenChange, current, onApply }: JsonDrawerProps) {
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(() => JSON.stringify(current, null, 2));

  useEffect(() => {
    setText(JSON.stringify(current, null, 2));
  }, [current, open]);

  function apply() {
    try {
      const parsed = JSON.parse(text) as ProcessDef;
      onApply(parsed);
      toast.success("Applied JSON to canvas");
      setEditing(false);
    } catch (e) {
      toast.error("Invalid JSON: " + (e as Error).message);
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="!max-w-[600px] sm:!max-w-[600px] p-0 flex flex-col">
        <SheetHeader className="p-3 border-b flex flex-row items-center gap-2">
          <SheetTitle className="text-sm">Workflow JSON</SheetTitle>
          <div className="ml-auto flex gap-2">
            {editing ? (
              <>
                <Button variant="outline" size="sm" onClick={() => setEditing(false)}>
                  <X className="w-3.5 h-3.5 mr-1" /> Cancel
                </Button>
                <Button size="sm" onClick={apply}>
                  Apply
                </Button>
              </>
            ) : (
              <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
                <Pencil className="w-3.5 h-3.5 mr-1" /> Edit
              </Button>
            )}
          </div>
        </SheetHeader>
        <div className="flex-1">
          <MonacoEditor
            height="100%"
            language="json"
            theme="vs-dark"
            value={text}
            onChange={(v) => setText(v ?? "")}
            options={{
              minimap: { enabled: false },
              fontSize: 12,
              readOnly: !editing,
              scrollBeyondLastLine: false,
              wordWrap: "on",
            }}
          />
        </div>
      </SheetContent>
    </Sheet>
  );
}
