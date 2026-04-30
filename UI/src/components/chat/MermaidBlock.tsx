import * as React from "react";
import mermaid from "mermaid";
import { Loader2, Maximize2, Code2, ZoomIn, ZoomOut, Copy } from "lucide-react";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";

let mermaidInitialized = false;

export function ensureMermaidInitialized() {
  if (mermaidInitialized) return;
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: "loose",
    theme: "default",
    suppressErrorRendering: true,
  });
  (mermaid as any).parseError = () => {};
  mermaidInitialized = true;
}

export function MermaidBlock({ chart }: { chart: string }) {
  const [svg, setSvg] = React.useState("");
  const [error, setError] = React.useState("");
  const [zoom, setZoom] = React.useState(1);
  const [expanded, setExpanded] = React.useState(false);
  const [showRawCode, setShowRawCode] = React.useState(false);
  const [copied, setCopied] = React.useState(false);
  const [panX, setPanX] = React.useState(0);
  const [panY, setPanY] = React.useState(0);
  const [isDragging, setIsDragging] = React.useState(false);
  const dragStartRef = React.useRef<{ mouseX: number; mouseY: number; panX: number; panY: number } | null>(null);
  const renderId = React.useMemo(() => `mermaid-${Math.random().toString(36).slice(2)}`, []);

  React.useEffect(() => {
    let mounted = true;
    ensureMermaidInitialized();

    const render = async () => {
      try {
        const { svg } = await mermaid.render(renderId, chart);
        if (!mounted) return;
        setError("");
        setSvg(svg);
      } catch (err) {
        if (!mounted) return;
        const message = err instanceof Error ? err.message : "Invalid Mermaid chart";
        setSvg("");
        setError(message);
      }
    };

    void render();
    return () => {
      mounted = false;
    };
  }, [chart, renderId]);

  if (error) {
    return (
      <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800">
        Mermaid render error: {error}
      </div>
    );
  }

  if (!svg) {
    return (
      <div className="inline-flex items-center gap-2 rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-500">
        <Loader2 size={12} className="animate-spin" />
        Rendering Mermaid chart...
      </div>
    );
  }

  const zoomIn = () => setZoom((z) => Math.min(3, +(z + 0.2).toFixed(2)));
  const zoomOut = () => setZoom((z) => Math.max(0.4, +(z - 0.2).toFixed(2)));
  const resetView = () => {
    setZoom(1);
    setPanX(0);
    setPanY(0);
  };
  const copyRawCode = async () => {
    try {
      await navigator.clipboard.writeText(chart);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1200);
    } catch {
      setCopied(false);
    }
  };
  const handleCanvasWheel = (event: React.WheelEvent<HTMLDivElement>) => {
    event.preventDefault();
    const delta = event.deltaY < 0 ? 0.1 : -0.1;
    setZoom((z) => Math.max(0.4, Math.min(3, +(z + delta).toFixed(2))));
  };
  const handleCanvasMouseDown = (event: React.MouseEvent<HTMLDivElement>) => {
    if (event.button !== 0) return;
    setIsDragging(true);
    dragStartRef.current = {
      mouseX: event.clientX,
      mouseY: event.clientY,
      panX,
      panY,
    };
  };
  const handleCanvasMouseMove = (event: React.MouseEvent<HTMLDivElement>) => {
    if (!isDragging || !dragStartRef.current) return;
    const dx = event.clientX - dragStartRef.current.mouseX;
    const dy = event.clientY - dragStartRef.current.mouseY;
    setPanX(dragStartRef.current.panX + dx);
    setPanY(dragStartRef.current.panY + dy);
  };
  const stopDragging = () => {
    setIsDragging(false);
    dragStartRef.current = null;
  };

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-1">
        <button
          type="button"
          className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-[11px] text-slate-600 hover:bg-slate-50"
          onClick={() => setExpanded(true)}
        >
          <Maximize2 size={12} />
          Expand
        </button>
        <button
          type="button"
          className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-[11px] text-slate-600 hover:bg-slate-50"
          onClick={() => setShowRawCode((v) => !v)}
        >
          <Code2 size={12} />
          {showRawCode ? "Hide Code" : "View Code"}
        </button>
      </div>
      {showRawCode ? (
        <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-words rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-700">
          {chart}
        </pre>
      ) : null}
      <div
        className="overflow-auto rounded-md border border-slate-200 bg-white p-2"
        dangerouslySetInnerHTML={{ __html: svg }}
      />

      <Dialog open={expanded} onOpenChange={setExpanded}>
        <DialogContent className="h-[92vh] w-[96vw] max-w-[96vw] bg-white p-4 sm:max-w-[96vw]">
          <DialogHeader>
            <DialogTitle>Mermaid Diagram</DialogTitle>
          </DialogHeader>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
              onClick={zoomOut}
            >
              <ZoomOut size={14} />
              Zoom Out
            </button>
            <button
              type="button"
              className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
              onClick={zoomIn}
            >
              <ZoomIn size={14} />
              Zoom In
            </button>
            <button
              type="button"
              className="rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
              onClick={resetView}
            >
              Reset
            </button>
            <button
              type="button"
              className="rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
              onClick={() => setShowRawCode((v) => !v)}
            >
              {showRawCode ? "Hide Code" : "Show Code"}
            </button>
            <button
              type="button"
              className="inline-flex items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
              onClick={copyRawCode}
            >
              <Copy size={12} />
              {copied ? "Copied" : "Copy Code"}
            </button>
            <span className="text-xs text-slate-500">Zoom: {Math.round(zoom * 100)}%</span>
          </div>

          {showRawCode ? (
            <pre className="max-h-56 overflow-auto whitespace-pre-wrap break-words rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-700">
              {chart}
            </pre>
          ) : null}

          <div
            className="h-[78vh] overflow-hidden rounded-md border border-slate-200 bg-white p-3"
            onWheel={handleCanvasWheel}
            onMouseDown={handleCanvasMouseDown}
            onMouseMove={handleCanvasMouseMove}
            onMouseUp={stopDragging}
            onMouseLeave={stopDragging}
            onDoubleClick={resetView}
            style={{ cursor: isDragging ? "grabbing" : "grab" }}
          >
            <div
              style={{ transform: `translate(${panX}px, ${panY}px) scale(${zoom})`, transformOrigin: "top left" }}
              dangerouslySetInnerHTML={{ __html: svg }}
            />
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export default MermaidBlock;
