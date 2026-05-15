import { Fragment, forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import BpmnViewer from "bpmn-js/lib/NavigatedViewer";
import { AlertTriangle, ChevronRight, CornerDownLeft } from "lucide-react";

import { layoutBpmn } from "@/lib/bpmnLayout";
import { inspectBpmnNode, type NodeInspection } from "@/lib/bpmnNodeInspect";
import { NodeInspectorPanel } from "@/components/bpmn/NodeInspectorPanel";
import type { BpmnDiagramController, BpmnDiagramProps } from "@/components/bpmn/types";

import "bpmn-js/dist/assets/diagram-js.css";
import "bpmn-js/dist/assets/bpmn-js.css";
import "bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css";

const MARKER_CLASSES = [
  "legacy-state-completed",
  "legacy-state-running",
  "legacy-state-failed",
  "legacy-state-warning",
  "legacy-state-selected",
] as const;

interface BreadcrumbEntry {
  id: string;
  label: string;
}

export const BpmnLegacyDiagram = forwardRef<BpmnDiagramController, BpmnDiagramProps>(
  function BpmnLegacyDiagram(
    {
      xml,
      className,
      executionDecorations,
      selectedElementId,
      onElementClick,
      onElementHover,
      onSubProcessClick,
    },
    ref,
  ) {
    const containerRef = useRef<HTMLDivElement>(null);
    const viewerRef = useRef<BpmnViewer | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [importGen, setImportGen] = useState(0);
    const [breadcrumbs, setBreadcrumbs] = useState<BreadcrumbEntry[]>([]);
    const [inspection, setInspection] = useState<NodeInspection | null>(null);
    const initialRootRef = useRef<BreadcrumbEntry | null>(null);
    const markerIdsRef = useRef<Set<string>>(new Set());
    const xmlRef = useRef(xml);
    const onElementClickRef = useRef(onElementClick);
    const onElementHoverRef = useRef(onElementHover);
    const onSubProcessClickRef = useRef(onSubProcessClick);

    xmlRef.current = xml;
    onElementClickRef.current = onElementClick;
    onElementHoverRef.current = onElementHover;
    onSubProcessClickRef.current = onSubProcessClick;

    useImperativeHandle(
      ref,
      () => ({
        zoomIn() {
          const canvas: any = viewerRef.current?.get("canvas");
          if (!canvas) return;
          canvas.zoom(canvas.zoom() + 0.2);
        },
        zoomOut() {
          const canvas: any = viewerRef.current?.get("canvas");
          if (!canvas) return;
          canvas.zoom(Math.max(0.2, canvas.zoom() - 0.2));
        },
        fit() {
          const canvas: any = viewerRef.current?.get("canvas");
          if (!canvas) return;
          canvas.zoom("fit-viewport", "auto");
        },
        reset() {
          const canvas: any = viewerRef.current?.get("canvas");
          if (!canvas) return;
          canvas.zoom(1);
        },
      }),
      [],
    );

    useEffect(() => {
      if (!containerRef.current) return;
      const viewer = new BpmnViewer({ container: containerRef.current });
      viewerRef.current = viewer;

      const eventBus: any = viewer.get("eventBus");
      const handleClick = (event: any) => {
        const id = event?.element?.id;
        if (id) onElementClickRef.current?.(id);
      };
      const handleHover = (event: any) => {
        const id = event?.element?.id ?? null;
        onElementHoverRef.current?.(id);
      };
      const handleOut = () => onElementHoverRef.current?.(null);
      const handleDoubleClick = (event: any) => {
        const el = event?.element;
        if (!el) return;
        // Prevent bpmn-js default dblclick behavior (which could open inline label edit).
        event?.preventDefault?.();

        if (el.type === "bpmn:SubProcess") {
          drillInto(el);
          onSubProcessClickRef.current?.(el.id);
          return;
        }

        // Skip connections and labels; they don't have a meaningful inspector.
        if (el.type === "bpmn:SequenceFlow" || el.type === "label") return;
        if (Array.isArray((el as any).waypoints)) return;

        const parsed = inspectBpmnNode(xmlRef.current, el.id);
        if (parsed) setInspection(parsed);
      };
      eventBus.on("element.click", handleClick);
      eventBus.on("element.hover", handleHover);
      eventBus.on("element.out", handleOut);
      eventBus.on("element.dblclick", handleDoubleClick);

      return () => {
        try {
          viewer.destroy();
        } catch {
          /* ignore */
        }
        viewerRef.current = null;
      };
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    /** Switch the canvas root to the given element's plane and record a breadcrumb. */
    function drillInto(element: any) {
      const viewer = viewerRef.current;
      if (!viewer) return;
      const canvas: any = viewer.get("canvas");
      try {
        canvas.setRootElement(element);
      } catch (e) {
        console.warn("[BpmnLegacyDiagram] setRootElement failed:", e);
        return;
      }
      const label = element.businessObject?.name || element.id;
      setBreadcrumbs((prev) => [...prev, { id: element.id, label }]);
      try {
        canvas.zoom("fit-viewport", "auto");
      } catch {
        /* ignore */
      }
    }

    /** Navigate to the given breadcrumb depth (0 = root). */
    function navigateToDepth(depth: number) {
      const viewer = viewerRef.current;
      if (!viewer) return;
      const canvas: any = viewer.get("canvas");
      const registry: any = viewer.get("elementRegistry");
      if (!canvas || !registry) return;

      const targetId =
        depth === 0 ? initialRootRef.current?.id : breadcrumbs[depth - 1]?.id;
      if (!targetId) return;
      const target = registry.get(targetId);
      if (!target) return;
      try {
        canvas.setRootElement(target);
        setBreadcrumbs(breadcrumbs.slice(0, depth));
        canvas.zoom("fit-viewport", "auto");
      } catch (e) {
        console.warn("[BpmnLegacyDiagram] navigateToDepth failed:", e);
      }
    }

    useEffect(() => {
      if (!viewerRef.current || !xml) return;
      let cancelled = false;
      setError(null);

      (async () => {
        try {
          const finalXml = await layoutBpmn(xml);
          if (cancelled) return;
          await viewerRef.current!.importXML(finalXml);
          if (cancelled) return;
          const canvas: any = viewerRef.current!.get("canvas");
          const root = canvas.getRootElement();
          initialRootRef.current = {
            id: root.id,
            label: root.businessObject?.name || "Process",
          };
          setBreadcrumbs([]);
          setInspection(null);
          canvas.zoom("fit-viewport", "auto");
          markerIdsRef.current.clear();
          setImportGen((n) => n + 1);
        } catch (e: any) {
          if (!cancelled) setError(e?.message || "Failed to render BPMN");
        }
      })();

      return () => {
        cancelled = true;
      };
    }, [xml]);

    useEffect(() => {
      const viewer = viewerRef.current;
      if (!viewer) return;
      if (importGen === 0) return;

      let canvas: any;
      let elementRegistry: any;
      try {
        canvas = viewer.get("canvas");
        elementRegistry = viewer.get("elementRegistry");
      } catch {
        return;
      }
      if (!canvas || !elementRegistry) return;

      const safe = (fn: () => void) => {
        try {
          fn();
        } catch {
          /* ignore */
        }
      };

      for (const id of markerIdsRef.current) {
        if (!elementRegistry.get(id)) continue;
        for (const cls of MARKER_CLASSES) {
          safe(() => canvas.removeMarker(id, cls));
        }
      }
      markerIdsRef.current.clear();

      const CONTAINER_TYPES = new Set([
        "bpmn:SubProcess",
        "bpmn:Process",
        "bpmn:Participant",
        "bpmn:Transaction",
        "bpmn:AdHocSubProcess",
      ]);

      // Recursively apply a state marker to every descendant flow node of the
      // given container. Skips connections (sequence flows), labels, and any
      // element that already received an explicit marker. This is what makes
      // drilling into a colored subprocess actually show colors on its inner
      // shapes — without it, the inner plane looks like an unrelated diagram.
      const applyToDescendants = (element: any, state: string) => {
        const children = element?.children ?? [];
        for (const child of children) {
          if (!child?.id || !child?.type) continue;
          if (child.type === "label") continue;
          if (child.waypoints) continue; // sequence flows / connections
          if (markerIdsRef.current.has(child.id)) continue;
          safe(() => canvas.addMarker(child.id, `legacy-state-${state}`));
          markerIdsRef.current.add(child.id);
          if (CONTAINER_TYPES.has(child.type)) {
            applyToDescendants(child, state);
          }
        }
      };

      for (const [id, state] of Object.entries(executionDecorations?.stateByElementId ?? {})) {
        const element = elementRegistry.get(id);
        if (!element) continue;
        safe(() => canvas.addMarker(id, `legacy-state-${state}`));
        markerIdsRef.current.add(id);
        if (CONTAINER_TYPES.has(element.type)) {
          applyToDescendants(element, state);
        }
      }

      if (selectedElementId && elementRegistry.get(selectedElementId)) {
        safe(() => canvas.addMarker(selectedElementId, "legacy-state-selected"));
        markerIdsRef.current.add(selectedElementId);
      }
    }, [executionDecorations, selectedElementId, importGen]);

    return (
      <div className={`bpmn-diagram-wrapper relative bg-white border rounded ${className ?? "min-h-[480px]"}`}>
        <div ref={containerRef} className="absolute inset-0" />

        {breadcrumbs.length > 0 && (
          <div className="absolute top-3 left-1/2 -translate-x-1/2 z-30 flex items-center gap-1 rounded border bg-white/95 px-3 py-1.5 text-xs shadow-sm">
            <button
              onClick={() => navigateToDepth(0)}
              className="flex items-center gap-1 font-medium text-[#123262] hover:underline"
              title="Back to top-level process"
            >
              <CornerDownLeft className="h-3 w-3" />
              {initialRootRef.current?.label ?? "Process"}
            </button>
            {breadcrumbs.map((b, i) => (
              <Fragment key={b.id}>
                <ChevronRight className="h-3 w-3 text-gray-400" />
                <button
                  onClick={() => navigateToDepth(i + 1)}
                  className={
                    i === breadcrumbs.length - 1
                      ? "font-semibold text-gray-900"
                      : "text-[#123262] hover:underline"
                  }
                  title={b.id}
                >
                  {b.label}
                </button>
              </Fragment>
            ))}
          </div>
        )}

        {inspection && (
          <NodeInspectorPanel inspection={inspection} onClose={() => setInspection(null)} />
        )}

        {error && (
          <div className="absolute inset-0 flex items-center justify-center p-4 pointer-events-none">
            <div className="rounded border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 flex items-start gap-2 max-w-xl pointer-events-auto">
              <AlertTriangle className="h-4 w-4 mt-0.5 shrink-0" />
              <div>
                <div className="font-medium">Couldn't render the BPMN diagram.</div>
                <div className="text-xs mt-1 break-words">{error}</div>
              </div>
            </div>
          </div>
        )}
      </div>
    );
  },
);
