// n8n-style three-pane workflow designer:
//   ┌──────────┬───────────────────────────┬──────────────┐
//   │ Library  │   xyflow canvas           │  Inspector   │
//   │ rail     │                           │  (form)      │
//   └──────────┴───────────────────────────┴──────────────┘
//
// The right pane is mounted only when something is selected. The JSON
// textarea pane is gone — power-users get a slide-in drawer behind a button.

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  Background,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  useReactFlow,
  type Edge,
  type Node,
  type OnConnect,
  type OnEdgesChange,
  type OnNodesChange,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { AlertTriangle, Braces, Check, CircleDashed, Code as CodeIcon, Loader2, Play, Save } from "lucide-react";
import { Button } from "@/components/ui/button";
import { workflowApi } from "@/services/workflowApi";
import {
  deserializeProcessDef,
  serializeBoard,
  type BoardEdgeData,
  type BoardNodeData,
} from "@/lib/workflowSerializer";
import { loadLayout, saveLayout } from "@/lib/workflowLayout";
import type { ActivityType, ProcessDef, VariableSpec } from "@/types/workflow";
import {
  DRAG_MIME,
  NodeLibraryRail,
  type LibraryDragPayload,
} from "@/components/workflow/designer/NodeLibraryRail";
import { NodeInspectorPanel } from "@/components/workflow/designer/NodeInspectorPanel";
import { EdgeInspectorPanel } from "@/components/workflow/designer/EdgeInspectorPanel";
import { JsonDrawer } from "@/components/workflow/designer/JsonDrawer";
import { VariablesPanel } from "@/components/workflow/designer/VariablesPanel";
import { workflowNodeTypes } from "@/components/workflow/designer/nodes";
import { usePluginCatalog } from "@/hooks/usePluginCatalog";
import { toast } from "sonner";

function defaultsFromDescriptor(
  descriptor: ReturnType<ReturnType<typeof usePluginCatalog>["get"]>,
): Record<string, unknown> {
  if (!descriptor) return {};
  const out: Record<string, unknown> = {};
  for (const p of descriptor.properties) {
    if (p.defaultValue !== undefined && p.defaultValue !== null) {
      out[p.name] = p.defaultValue;
    }
  }
  return out;
}

interface DesignerInnerProps {
  initialDef: ProcessDef | undefined;
  id: string | undefined;
}

function DesignerInner({ initialDef, id }: DesignerInnerProps) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { get: getDescriptor } = usePluginCatalog();
  const flow = useReactFlow();
  const containerRef = useRef<HTMLDivElement>(null);

  const [nodes, setNodes] = useState<Node<BoardNodeData>[]>([]);
  const [edges, setEdges] = useState<Edge<BoardEdgeData>[]>([]);
  const [name, setName] = useState("");
  const [version, setVersion] = useState("1");
  const [variables, setVariables] = useState<VariableSpec[]>([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [jsonOpen, setJsonOpen] = useState(false);
  const [varsOpen, setVarsOpen] = useState(false);

  // Hydrate once the definition loads. Layout x/y is restored from
  // localStorage (per-workflow); fresh nodes fall back to a horizontal lane.
  useEffect(() => {
    if (!initialDef) return;
    const { nodes: n, edges: e } = deserializeProcessDef(initialDef);
    const layout = loadLayout(id);
    setNodes(
      n.map((node, i) => ({
        ...node,
        position: layout[node.id] ?? { x: 80 + i * 220, y: 120 },
      })),
    );
    setEdges(e);
    setName(initialDef.name);
    setVersion(initialDef.version);
    setVariables(initialDef.variables ?? []);
  }, [initialDef, id]);

  // Persist layout positions on every node change (debounced via the
  // browser's microtask queue; xyflow already batches drag events).
  useEffect(() => {
    if (!id || nodes.length === 0) return;
    const positions: Record<string, { x: number; y: number }> = {};
    for (const n of nodes) positions[n.id] = { x: n.position.x, y: n.position.y };
    saveLayout(id, positions);
  }, [nodes, id]);

  const onNodesChange: OnNodesChange = useCallback(
    (changes) => setNodes((cur) => applyNodeChanges(changes, cur) as Node<BoardNodeData>[]),
    [],
  );
  const onEdgesChange: OnEdgesChange = useCallback(
    (changes) => setEdges((cur) => applyEdgeChanges(changes, cur) as Edge<BoardEdgeData>[]),
    [],
  );
  const onConnect: OnConnect = useCallback(
    (c) =>
      setEdges((cur) =>
        addEdge(
          {
            ...c,
            id: `e-${cur.length + 1}-${Date.now()}`,
            data: { isErrorEdge: false, trigger: "ON_SUCCESS" } satisfies BoardEdgeData,
          },
          cur,
        ) as Edge<BoardEdgeData>[],
      ),
    [],
  );

  function pushNode(activityType: ActivityType, data: Partial<BoardNodeData>, position: { x: number; y: number }) {
    const newId = `${activityType}-${Date.now()}`;
    setNodes((cur) => [
      ...cur,
      {
        id: newId,
        type: activityType,
        position,
        data: {
          activityType,
          properties: {},
          isStart: false,
          isEnd: false,
          ...data,
        } as BoardNodeData,
      },
    ]);
    setSelectedNodeId(newId);
    setSelectedEdgeId(null);
  }

  function addFromLibrary(payload: LibraryDragPayload, position: { x: number; y: number }) {
    if (payload.kind === "plugin") {
      const d = getDescriptor(payload.pluginName);
      pushNode(
        "tool",
        {
          pluginName: payload.pluginName,
          label: d?.label ?? payload.pluginName,
          properties: defaultsFromDescriptor(d),
        },
        position,
      );
    } else if (payload.kind === "route") {
      pushNode(
        "route",
        {
          label: payload.flag === "start" ? "Start" : "End",
          isStart: payload.flag === "start",
          isEnd: payload.flag === "end",
        },
        position,
      );
    } else if (payload.kind === "subflow") {
      pushNode("subflow", { label: "Subflow" }, position);
    } else if (payload.kind === "loop") {
      pushNode(payload.loopType, { label: payload.loopType.toUpperCase() }, position);
    }
  }

  // Drag-and-drop add from the library rail.
  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();
      const raw = event.dataTransfer.getData(DRAG_MIME);
      if (!raw) return;
      let payload: LibraryDragPayload;
      try {
        payload = JSON.parse(raw) as LibraryDragPayload;
      } catch {
        return;
      }
      const position = flow.screenToFlowPosition({ x: event.clientX, y: event.clientY });
      addFromLibrary(payload, position);
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [flow],
  );

  const onDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = "copy";
  }, []);

  // Click-to-add (no drag): drop in the visible center of the canvas.
  function clickAdd(payload: LibraryDragPayload) {
    const rect = containerRef.current?.getBoundingClientRect();
    const cx = rect ? rect.left + rect.width / 2 : 400;
    const cy = rect ? rect.top + rect.height / 2 : 200;
    const position = flow.screenToFlowPosition({ x: cx, y: cy });
    addFromLibrary(payload, position);
  }

  // Save status state machine. Drives the header indicator and the
  // auto-save debounce. `idle` is the post-hydrate quiet state — auto-save
  // hasn't run yet because nothing has changed.
  const [saveStatus, setSaveStatus] = useState<"idle" | "dirty" | "saving" | "saved" | "error">("idle");
  const [lastSavedAt, setLastSavedAt] = useState<number | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: ({ dto, kind }: { dto: ProcessDef; kind: "manual" | "auto" }) =>
      (id ? workflowApi.processes.update(id, dto) : workflowApi.processes.create(dto))
        .then((saved) => ({ saved, kind })),
    onMutate: () => setSaveStatus("saving"),
    onSuccess: ({ saved, kind }) => {
      if (kind === "manual") toast.success("Saved");
      qc.invalidateQueries({ queryKey: ["workflow", saved.id ?? id] });
      qc.invalidateQueries({ queryKey: ["workflows"] });
      setSaveStatus("saved");
      setLastSavedAt(Date.now());
      setSaveError(null);
      if (!id && saved.id) navigate(`/workflows/${saved.id}/designer`, { replace: true });
    },
    onError: (e: Error, vars) => {
      setSaveStatus("error");
      setSaveError(e.message ?? "Save failed");
      if (vars?.kind === "manual") toast.error(e.message ?? "Save failed");
    },
  });

  const startRun = useMutation({
    mutationFn: () => workflowApi.runs.start({ processDefId: id! }),
    onSuccess: (r) => navigate(`/workflows/runs/${r.instanceId}`),
    onError: (e: Error) => toast.error(e.message ?? "Run failed"),
  });

  function handleSave() {
    try {
      const dto = serializeBoard(nodes, edges, { id, name, version, variables });
      save.mutate({ dto, kind: "manual" });
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  // ── Auto-save ──────────────────────────────────────────────────────────
  // Debounced backend save on any canvas change. Skipped until the page has
  // hydrated from `initialDef` (so we don't immediately POST what we just
  // GET'd) and only runs once the workflow has an id (new workflows still
  // need one explicit Save click to create the row). Manual cmd-S still
  // works and goes through the same mutation.
  const hasHydratedRef = useRef(false);
  const autoSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (initialDef) hasHydratedRef.current = true;
  }, [initialDef]);

  useEffect(() => {
    // First effect tick: nothing has changed yet — leave status idle.
    if (!hasHydratedRef.current) return;
    if (!id) {
      // New workflow: skip auto-save until first explicit save assigns an id.
      setSaveStatus("dirty");
      return;
    }
    setSaveStatus("dirty");
    if (autoSaveTimerRef.current) clearTimeout(autoSaveTimerRef.current);
    autoSaveTimerRef.current = setTimeout(() => {
      if (save.isPending) return; // a save is already in flight; the next change will retry
      try {
        const dto = serializeBoard(nodes, edges, { id, name, version, variables });
        save.mutate({ dto, kind: "auto" });
      } catch (e) {
        // Graph is malformed mid-edit — keep status "dirty", surface error.
        setSaveStatus("error");
        setSaveError((e as Error).message);
      }
    }, 1500);
    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
        autoSaveTimerRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodes, edges, name, version, variables, id]);

  // Tick a counter every 30s so "Saved · Ns ago" stays roughly fresh.
  const [, forceTick] = useState(0);
  useEffect(() => {
    if (saveStatus !== "saved") return;
    const t = setInterval(() => forceTick((n) => n + 1), 30_000);
    return () => clearInterval(t);
  }, [saveStatus]);

  // cmd-S / ctrl-S → save. Stop the browser's default save dialog.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const isSave = (e.metaKey || e.ctrlKey) && (e.key === "s" || e.key === "S");
      if (isSave) {
        e.preventDefault();
        handleSave();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodes, edges, id, name, version, variables]);

  function applyJson(def: ProcessDef) {
    const { nodes: n, edges: e } = deserializeProcessDef(def);
    setNodes(n.map((nd, i) => ({ ...nd, position: { x: 80 + i * 220, y: 120 } })));
    setEdges(e);
    if (def.name) setName(def.name);
    if (def.version) setVersion(def.version);
    setVariables(def.variables ?? []);
    setSelectedNodeId(null);
    setSelectedEdgeId(null);
  }

  const currentDef: ProcessDef = useMemo(() => {
    try {
      return serializeBoard(nodes, edges, { id, name, version, variables });
    } catch {
      return {
        id,
        name: name || "",
        version: version || "1",
        variables,
        activities: [],
        transitions: [],
      };
    }
  }, [nodes, edges, id, name, version, variables]);

  const selectedNode = selectedNodeId ? nodes.find((n) => n.id === selectedNodeId) ?? null : null;
  const selectedEdge = selectedEdgeId ? edges.find((e) => e.id === selectedEdgeId) ?? null : null;

  function updateNodeData(nodeId: string, next: BoardNodeData) {
    setNodes((cur) => cur.map((n) => (n.id === nodeId ? { ...n, data: next } : n)));
  }
  function updateEdgeData(edgeId: string, next: BoardEdgeData) {
    setEdges((cur) => cur.map((e) => (e.id === edgeId ? { ...e, data: next } : e)));
  }
  function deleteSelectedNode(nodeId: string) {
    setNodes((cur) => cur.filter((n) => n.id !== nodeId));
    setEdges((cur) => cur.filter((e) => e.source !== nodeId && e.target !== nodeId));
    setSelectedNodeId(null);
  }

  return (
    <div className="flex flex-col h-screen">
      <header className="border-b p-3 flex items-center gap-3">
        <input
          className="text-lg font-medium border-b border-transparent hover:border-border focus:border-primary outline-none px-1"
          value={name}
          placeholder="Untitled workflow"
          onChange={(e) => setName(e.target.value)}
        />
        <input
          className="text-sm text-muted-foreground w-16 border-b border-transparent hover:border-border focus:border-primary outline-none px-1"
          value={version}
          onChange={(e) => setVersion(e.target.value)}
        />
        <div className="ml-auto flex gap-2">
          <Button variant="outline" size="sm" onClick={() => setVarsOpen((o) => !o)}>
            <Braces className="w-4 h-4 mr-1" /> Variables
            {variables.length > 0 && (
              <span className="ml-1 text-[10px] bg-muted rounded-full px-1.5">
                {variables.length}
              </span>
            )}
          </Button>
          <Button variant="outline" size="sm" onClick={() => setJsonOpen(true)}>
            <CodeIcon className="w-4 h-4 mr-1" /> JSON
          </Button>
          <SaveStatusPill
            status={saveStatus}
            lastSavedAt={lastSavedAt}
            error={saveError}
            hasId={!!id}
            onForceSave={handleSave}
            isPending={save.isPending}
          />
          <Button
            onClick={handleSave}
            disabled={save.isPending}
            size="sm"
            variant={saveStatus === "error" || !id ? "default" : "outline"}
          >
            {save.isPending ? (
              <Loader2 className="w-4 h-4 mr-1 animate-spin" />
            ) : (
              <Save className="w-4 h-4 mr-1" />
            )}
            Save
          </Button>
          {id && (
            <Link
              to={`/workflows/${id}/runs`}
              className="inline-flex items-center gap-1 rounded-md border border-input bg-background px-3 h-8 text-sm hover:bg-accent"
            >
              Runs
            </Link>
          )}
          {id && (
            <Link
              to={`/workflows/${id}/diff`}
              className="inline-flex items-center gap-1 rounded-md border border-input bg-background px-3 h-8 text-sm hover:bg-accent"
            >
              Versions
            </Link>
          )}
          {id && (
            <Button onClick={() => startRun.mutate()} disabled={startRun.isPending} size="sm">
              <Play className="w-4 h-4 mr-1" /> Run
            </Button>
          )}
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        <NodeLibraryRail onAdd={clickAdd} />

        <div className="flex-1 relative" ref={containerRef} onDrop={onDrop} onDragOver={onDragOver}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={(_, n) => {
              setSelectedNodeId(n.id);
              setSelectedEdgeId(null);
            }}
            onEdgeClick={(_, e) => {
              setSelectedEdgeId(e.id);
              setSelectedNodeId(null);
            }}
            onPaneClick={() => {
              setSelectedNodeId(null);
              setSelectedEdgeId(null);
            }}
            nodeTypes={workflowNodeTypes}
            fitView
          >
            <Background />
            <Controls />
          </ReactFlow>
        </div>

        {selectedNode && (
          <NodeInspectorPanel
            nodeId={selectedNode.id}
            data={selectedNode.data ?? {}}
            onChange={(next) => updateNodeData(selectedNode.id, next)}
            onClose={() => setSelectedNodeId(null)}
            onDelete={() => deleteSelectedNode(selectedNode.id)}
          />
        )}
        {selectedEdge && (
          <EdgeInspectorPanel
            edgeId={selectedEdge.id}
            data={selectedEdge.data ?? {}}
            onChange={(next) => updateEdgeData(selectedEdge.id, next)}
            onClose={() => setSelectedEdgeId(null)}
          />
        )}
      </div>

      <VariablesPanel
        open={varsOpen}
        onClose={() => setVarsOpen(false)}
        variables={variables}
        setVariables={setVariables}
        nodes={nodes}
      />

      <JsonDrawer
        open={jsonOpen}
        onOpenChange={setJsonOpen}
        current={currentDef}
        onApply={applyJson}
      />
    </div>
  );
}

function relativeTime(ms: number): string {
  const diff = Date.now() - ms;
  if (diff < 5_000) return "just now";
  if (diff < 60_000) return `${Math.floor(diff / 1000)}s ago`;
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return `${Math.floor(diff / 86_400_000)}d ago`;
}

interface SaveStatusPillProps {
  status: "idle" | "dirty" | "saving" | "saved" | "error";
  lastSavedAt: number | null;
  error: string | null;
  hasId: boolean;
  isPending: boolean;
  onForceSave: () => void;
}

function SaveStatusPill({
  status,
  lastSavedAt,
  error,
  hasId,
  isPending,
  onForceSave,
}: SaveStatusPillProps) {
  if (!hasId) {
    return (
      <span className="inline-flex items-center gap-1 text-xs text-muted-foreground px-2">
        <CircleDashed className="w-3.5 h-3.5" />
        Click <strong className="font-medium">Save</strong> to create
      </span>
    );
  }
  if (status === "saving" || isPending) {
    return (
      <span className="inline-flex items-center gap-1 text-xs text-blue-600 px-2">
        <Loader2 className="w-3.5 h-3.5 animate-spin" /> Saving…
      </span>
    );
  }
  if (status === "error") {
    return (
      <button
        type="button"
        onClick={onForceSave}
        title={error ?? "Save failed — click to retry"}
        className="inline-flex items-center gap-1 text-xs text-rose-600 hover:underline px-2"
      >
        <AlertTriangle className="w-3.5 h-3.5" /> Save failed — retry
      </button>
    );
  }
  if (status === "dirty") {
    return (
      <span className="inline-flex items-center gap-1 text-xs text-muted-foreground px-2">
        <CircleDashed className="w-3.5 h-3.5" /> Unsaved changes
      </span>
    );
  }
  // saved or idle
  return (
    <span className="inline-flex items-center gap-1 text-xs text-emerald-600 px-2">
      <Check className="w-3.5 h-3.5" />
      {lastSavedAt ? `Saved · ${relativeTime(lastSavedAt)}` : "Saved"}
    </span>
  );
}

export default function WorkflowDesignerPage() {
  const { id } = useParams<{ id: string }>();
  const def = useQuery<ProcessDef>({
    queryKey: ["workflow", id],
    queryFn: () => workflowApi.processes.get(id!),
    enabled: !!id,
  });

  return (
    <ReactFlowProvider>
      <DesignerInner initialDef={def.data} id={id} />
    </ReactFlowProvider>
  );
}
