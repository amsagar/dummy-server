import { useEffect, useRef, useState } from "react";
import BpmnViewer from "bpmn-js/lib/NavigatedViewer";
import { layoutBpmn } from "@/lib/bpmnLayout";
import { AlertTriangle, ZoomIn, ZoomOut, Maximize2 } from "lucide-react";

import "bpmn-js/dist/assets/diagram-js.css";
import "bpmn-js/dist/assets/bpmn-js.css";
import "bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css";

interface BpmnDiagramProps {
  xml: string;
  className?: string;
}

/**
 * Renders a BPMN XML document as an SVG diagram.
 *
 * Layout is done with our dagre-based {@link layoutBpmn}, which always emits
 * BPMN-DI for every flow node and every sequence flow — so arrows actually
 * show up, including across collapsed multi-instance subprocesses.
 */
export function BpmnDiagram({ xml, className }: BpmnDiagramProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<BpmnViewer | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;
    const viewer = new BpmnViewer({ container: containerRef.current });
    viewerRef.current = viewer;
    return () => {
      viewer.destroy();
      viewerRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!viewerRef.current || !xml) return;
    let cancelled = false;
    setError(null);
    (async () => {
      try {
        // Always regenerate DI — LLM-authored BPMN frequently ships shapes
        // without edges, which makes bpmn-js render disconnected tasks.
        // layoutBpmn() strips any pre-existing diagram and emits a fresh
        // one with both shapes and sequence-flow waypoints.
        const finalXml = await layoutBpmn(xml);
        if (cancelled) return;
        await viewerRef.current!.importXML(finalXml);
        if (cancelled) return;
        const canvas: any = viewerRef.current!.get("canvas");
        canvas.zoom("fit-viewport", "auto");
      } catch (e: any) {
        if (!cancelled) setError(e?.message || "Failed to render BPMN");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [xml]);

  const zoom = (delta: number) => {
    if (!viewerRef.current) return;
    const canvas: any = viewerRef.current.get("canvas");
    canvas.zoom(canvas.zoom() + delta);
  };
  const fit = () => {
    if (!viewerRef.current) return;
    const canvas: any = viewerRef.current.get("canvas");
    canvas.zoom("fit-viewport", "auto");
  };

  return (
    <div className={`bpmn-diagram-wrapper relative bg-white border rounded ${className ?? "min-h-[480px]"}`}>
      <div ref={containerRef} className="absolute inset-0" />
      {error && (
        <div className="absolute inset-0 flex items-center justify-center p-4 pointer-events-none">
          <div className="rounded border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 flex items-start gap-2 max-w-xl pointer-events-auto">
            <AlertTriangle className="h-4 w-4 mt-0.5 shrink-0" />
            <div>
              <div className="font-medium">Couldn't render the BPMN diagram.</div>
              <div className="text-xs mt-1 break-words">{error}</div>
              <div className="text-xs mt-2 text-amber-700">
                The raw XML is still available on the next tab.
              </div>
            </div>
          </div>
        </div>
      )}
      <div className="absolute bottom-3 right-3 flex flex-col gap-1 bg-white border rounded shadow-sm">
        <button onClick={() => zoom(0.2)} className="p-1.5 hover:bg-gray-100 text-gray-600" title="Zoom in">
          <ZoomIn className="h-4 w-4" />
        </button>
        <button
          onClick={() => zoom(-0.2)}
          className="p-1.5 hover:bg-gray-100 text-gray-600 border-t"
          title="Zoom out"
        >
          <ZoomOut className="h-4 w-4" />
        </button>
        <button
          onClick={fit}
          className="p-1.5 hover:bg-gray-100 text-gray-600 border-t"
          title="Fit to viewport"
        >
          <Maximize2 className="h-4 w-4" />
        </button>
      </div>
    </div>
  );
}
