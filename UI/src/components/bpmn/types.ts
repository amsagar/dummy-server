export type BpmnElementRuntimeState = "running" | "completed" | "failed" | "warning";

export interface BpmnExecutionDecoration {
  stateByElementId: Record<string, BpmnElementRuntimeState>;
  badgeByElementId: Record<string, string>;
}

export interface BpmnDiagramProps {
  xml: string;
  className?: string;
  executionDecorations?: BpmnExecutionDecoration;
  selectedElementId?: string | null;
  onElementClick?: (elementId: string) => void;
  onElementHover?: (elementId: string | null) => void;
  /** Fired after a subprocess is drilled into via double-click. Optional —
   *  the legacy renderer handles drilldown internally with a breadcrumb;
   *  consumers can use this hook to log or sync UI state. */
  onSubProcessClick?: (elementId: string) => void;
}

export interface BpmnDiagramController {
  zoomIn(): void;
  zoomOut(): void;
  fit(): void;
  reset(): void;
}
