import { useRef } from "react";
import { Maximize2, RotateCcw, ZoomIn, ZoomOut } from "lucide-react";

import { BpmnLegacyDiagram } from "@/components/BpmnLegacyDiagram";
import type { BpmnDiagramController, BpmnDiagramProps } from "@/components/bpmn/types";

/**
 * Top-level BPMN diagram component. Wraps the legacy bpmn-js renderer with a
 * zoom/fit toolbar. Subprocess drilldown is handled inside the renderer via
 * double-click + breadcrumb navigation.
 */
export function BpmnDiagram(props: BpmnDiagramProps) {
  const controllerRef = useRef<BpmnDiagramController | null>(null);

  return (
    <div className="relative">
      <div className="absolute right-3 top-3 z-20 flex items-center gap-1 rounded border bg-white/95 px-1.5 py-1 text-xs shadow-sm">
        <button
          onClick={() => controllerRef.current?.zoomIn()}
          className="rounded p-1 hover:bg-gray-100"
          title="Zoom in"
        >
          <ZoomIn className="h-3.5 w-3.5" />
        </button>
        <button
          onClick={() => controllerRef.current?.zoomOut()}
          className="rounded p-1 hover:bg-gray-100"
          title="Zoom out"
        >
          <ZoomOut className="h-3.5 w-3.5" />
        </button>
        <button onClick={() => controllerRef.current?.fit()} className="rounded p-1 hover:bg-gray-100" title="Fit">
          <Maximize2 className="h-3.5 w-3.5" />
        </button>
        <button
          onClick={() => controllerRef.current?.reset()}
          className="rounded p-1 hover:bg-gray-100"
          title="Reset view"
        >
          <RotateCcw className="h-3.5 w-3.5" />
        </button>
      </div>

      <BpmnLegacyDiagram ref={controllerRef} {...props} />
    </div>
  );
}
