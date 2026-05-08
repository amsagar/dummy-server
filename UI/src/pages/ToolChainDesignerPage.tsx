import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { ReactFlow, Background, Controls, MiniMap, addEdge, applyEdgeChanges, applyNodeChanges, useEdgesState, useNodesState } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { api, getAuthToken } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { toast } from "sonner";
import ToolChainSessionList from "@/components/toolchain/ToolChainSessionList";
import ToolChainConfigChatPanel from "@/components/toolchain/ToolChainConfigChatPanel";
import NodeBottomInspectorPanel from "@/components/toolchain/NodeBottomInspectorPanel";
import NodeEditorDialog from "@/components/toolchain/NodeEditorDialog";
import {
  type ChatMessage,
  type InteractionAction,
  type PendingInteraction,
  normalizeQuestionMetadata,
} from "@/components/chat";
import { cn } from "@/lib/utils";
import { ArrowLeft, Maximize2, Minimize2, PanelRight } from "lucide-react";
import { SearchableSelect } from "@/components/ui/searchable-select";
import { modelRefKey, parseModelRefKey } from "@/types";
import CapabilityFlowNode from "@/components/toolchain/CapabilityFlowNode";
import CapabilityFlowEdge from "@/components/toolchain/CapabilityFlowEdge";
import {
  type ToolGraph,
  type ToolGraphNode,
  graphToFlow,
  normalizeGraph,
  canonicalizeGraphToolNodeTypes,
  parseGraphJson,
  flowEdgesToGraph,
  collectGraphWarnings,
} from "@/components/toolchain/graphCapabilities";

const starterGraph = {
  nodes: [
    { id: "start", type: "input", data: { label: "Start" }, position: { x: 40, y: 100 } },
    { id: "task_1", type: "default", data: { label: "Tool Step", config: { toolName: "task" } }, position: { x: 280, y: 100 } },
    { id: "end", type: "output", data: { label: "End" }, position: { x: 540, y: 100 } },
  ],
  edges: [
    { id: "e-start-task", source: "start", target: "task_1" },
    { id: "e-task-end", source: "task_1", target: "end" },
  ],
};

function genId() {
  return Math.random().toString(36).slice(2);
}

const CHAT_PANEL_WIDTH_STORAGE_KEY = "toolchain-designer.chat-panel-width";
const CHAT_PANEL_COLLAPSED_STORAGE_KEY = "toolchain-designer.chat-panel-collapsed";
const LAYOUT_VIEWPORT_GRAPH_KEY = "__draftGraphJson";
const CHAT_PANEL_WIDTH_MIN = 320;
const CHAT_PANEL_WIDTH_DEFAULT = 380;

function readInitialChatPanelCollapsed(): boolean {
  if (typeof window === "undefined") return false;
  return window.localStorage.getItem(CHAT_PANEL_COLLAPSED_STORAGE_KEY) === "1";
}

function clampChatPanelWidthValue(candidateWidth: number): number {
  const width = Number.isFinite(candidateWidth) ? candidateWidth : CHAT_PANEL_WIDTH_DEFAULT;
  const max = typeof window === "undefined"
    ? 900
    : Math.max(CHAT_PANEL_WIDTH_MIN, Math.floor(window.innerWidth * 0.55));
  return Math.max(CHAT_PANEL_WIDTH_MIN, Math.min(max, Math.round(width)));
}

function readInitialChatPanelWidth(): number {
  if (typeof window === "undefined") return CHAT_PANEL_WIDTH_DEFAULT;
  const raw = window.localStorage.getItem(CHAT_PANEL_WIDTH_STORAGE_KEY);
  if (!raw) return clampChatPanelWidthValue(CHAT_PANEL_WIDTH_DEFAULT);
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) return clampChatPanelWidthValue(CHAT_PANEL_WIDTH_DEFAULT);
  return clampChatPanelWidthValue(parsed);
}

function toFiniteNumber(value: any): number | null {
  const out = typeof value === "number" ? value : Number(value);
  return Number.isFinite(out) ? out : null;
}

function parseMetadata(raw: any): Record<string, any> {
  if (!raw) return {};
  if (typeof raw === "object") return raw as Record<string, any>;
  if (typeof raw !== "string") return {};
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? (parsed as Record<string, any>) : {};
  } catch {
    return {};
  }
}

function toTitleCase(value: string): string {
  return value
    .replace(/[_-]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

function parseSchemaObject(raw: any): { type?: string; properties?: Record<string, any>; required?: string[] } | null {
  if (!raw) return null;
  if (typeof raw === "object") {
    return {
      type: String((raw as any).type || "object"),
      properties: typeof (raw as any).properties === "object" && (raw as any).properties ? (raw as any).properties : {},
      required: Array.isArray((raw as any).required) ? (raw as any).required.map((v: any) => String(v)) : [],
    };
  }
  if (typeof raw !== "string" || !raw.trim()) return null;
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return null;
    return {
      type: String((parsed as any).type || "object"),
      properties: typeof (parsed as any).properties === "object" && (parsed as any).properties ? (parsed as any).properties : {},
      required: Array.isArray((parsed as any).required) ? (parsed as any).required.map((v: any) => String(v)) : [],
    };
  } catch {
    return null;
  }
}

function normalizeLayoutPositions(raw: any): Record<string, { x: number; y: number }> {
  if (!raw || typeof raw !== "object") return {};
  const normalized: Record<string, { x: number; y: number }> = {};
  for (const [nodeId, coords] of Object.entries(raw as Record<string, any>)) {
    if (!nodeId) continue;
    const x = toFiniteNumber((coords as any)?.x);
    const y = toFiniteNumber((coords as any)?.y);
    if (x === null || y === null) continue;
    normalized[nodeId] = { x, y };
  }
  return normalized;
}

function mergeLayoutPositions(
  userPositions: Record<string, { x: number; y: number }>,
  sessionPositions: Record<string, { x: number; y: number }>,
  preferSession: boolean
): Record<string, { x: number; y: number }> {
  // Active config sessions should prefer session-scoped coordinates so stale
  // user-scoped coordinates don't mask recent in-session drags.
  return preferSession
    ? { ...userPositions, ...sessionPositions }
    : { ...sessionPositions, ...userPositions };
}

function readViewport(raw: any): { x: number; y: number; zoom: number } | null {
  if (!raw || typeof raw !== "object") return null;
  const x = toFiniteNumber((raw as any).x);
  const y = toFiniteNumber((raw as any).y);
  const zoom = toFiniteNumber((raw as any).zoom);
  if (x === null || y === null || zoom === null) return null;
  return { x, y, zoom };
}

function readGraphFromLayoutViewport(raw: any): ToolGraph | null {
  const embedded = raw && typeof raw === "object" ? (raw as any)[LAYOUT_VIEWPORT_GRAPH_KEY] : null;
  if (typeof embedded !== "string" || !embedded.trim()) return null;
  return parseGraphJson(embedded);
}

function shouldHideSystemEventForDesigner(message: any): boolean {
  const eventType = String(message?.eventType || "").toLowerCase();
  return eventType.startsWith("task.")
    || eventType.startsWith("tool.")
    || eventType.startsWith("step.")
    || eventType === "model_step";
}

function shouldRenderDraftGraph(stateLike: any): boolean {
  if (!stateLike) return false;
  const graphJson = typeof stateLike.graphJson === "string" ? stateLike.graphJson : "";
  if (!graphJson.trim()) return false;
  if (stateLike.graphAvailable === false) return false;
  return true;
}

export default function ToolChainDesignerPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const routeToolChainId = id ?? "";
  const [toolChainId, setToolChainId] = useState<string>(routeToolChainId);
  const [versionsOpen, setVersionsOpen] = useState(false);
  // Version switcher: null = show the live editing draft (active session's graph,
  // or the latest version when no session is active). A number switches the board
  // to a read-only view of that version's graph.
  const [viewedVersionNumber, setViewedVersionNumber] = useState<number | null>(null);
  // Whole-page fullscreen was removed in favour of the board-only fullscreen icon
  // on the React Flow surface — kept boardOnlyFullscreen below as the single mode.
  const [nodes, setNodes] = useNodesState<any>(starterGraph.nodes as any);
  const [edges, setEdges] = useEdgesState<any>(starterGraph.edges as any);
  const [draftGraph, setDraftGraph] = useState<ToolGraph>(normalizeGraph(starterGraph));
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null);
  const [pendingQuestion, setPendingQuestion] = useState<any | null>(null);
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [pendingInteractions, setPendingInteractions] = useState<PendingInteraction[]>([]);
  const [interactionDrafts, setInteractionDrafts] = useState<Record<string, string>>({});
  const [interactionSelections, setInteractionSelections] = useState<Record<string, string[]>>({});
  const [isConfigStreaming, setIsConfigStreaming] = useState(false);
  const [sessionBootstrapPending, setSessionBootstrapPending] = useState(false);
  const [sessionStatus, setSessionStatus] = useState<string>("draft");
  const [showSessionsMenu, setShowSessionsMenu] = useState(false);
  const [rollbackOpen, setRollbackOpen] = useState(false);
  const [rollbackVerifyOpen, setRollbackVerifyOpen] = useState(false);
  const [rollbackText, setRollbackText] = useState("");
  const [rollbackVersion, setRollbackVersion] = useState<number | null>(null);
  const [modelSelectionMode, setModelSelectionMode] = useState<"manual" | "auto">("manual");
  const [selectedModel, setSelectedModel] = useState<string>(localStorage.getItem("lastModelId") || "");
  const [selectedProvider, setSelectedProvider] = useState<string>(
    localStorage.getItem("lastModelId")?.split("/")[0] || ""
  );
  const [chatPanelWidth, setChatPanelWidth] = useState<number>(() => readInitialChatPanelWidth());
  const [isResizingChatPanel, setIsResizingChatPanel] = useState(false);
  const [chatPanelCollapsed, setChatPanelCollapsed] = useState<boolean>(() => readInitialChatPanelCollapsed());

  useEffect(() => {
    if (typeof window === "undefined") return;
    window.localStorage.setItem(CHAT_PANEL_COLLAPSED_STORAGE_KEY, chatPanelCollapsed ? "1" : "0");
  }, [chatPanelCollapsed]);
  const [inspectedNodeId, setInspectedNodeId] = useState<string | null>(null);
  const [editingNodeId, setEditingNodeId] = useState<string | null>(null);
  const [boardOnlyFullscreen, setBoardOnlyFullscreen] = useState(false);
  const activeSessionRef = useRef<string | null>(null);
  const activeToolChainRef = useRef<string>("");
  const layoutPositionsRef = useRef<Record<string, { x: number; y: number }>>({});
  const layoutViewportRef = useRef<Record<string, any>>({});
  const reactFlowRef = useRef<any>(null);
  const layoutSaveTimerRef = useRef<number | null>(null);
  const modelInitializedRef = useRef<string>("");
  const streamedAssistantIdRef = useRef<string | null>(null);
  const seenQuestionRequestIdsRef = useRef<Set<string>>(new Set());
  const chatResizeStartRef = useRef<{ startX: number; startWidth: number } | null>(null);
  // AbortController for the in-flight SSE stream — captured so the Stop button
  // in the composer can abort the fetch and call the backend cancel endpoint.
  const streamAbortControllerRef = useRef<AbortController | null>(null);
  // Keep track of the session id that the current stream is registered against
  // so cancelStream knows which sessionId to send to /stop.
  const streamSessionIdRef = useRef<string | null>(null);
  const hasHydratedWidthRef = useRef(false);
  const nodeTypes = useMemo(() => ({ capabilityNode: CapabilityFlowNode }), []);
  const edgeTypes = useMemo(() => ({ capabilityEdge: CapabilityFlowEdge }), []);

  useEffect(() => {
    setToolChainId(routeToolChainId);
  }, [routeToolChainId]);

  useEffect(() => {
    activeSessionRef.current = activeSessionId;
  }, [activeSessionId]);

  useEffect(() => {
    activeToolChainRef.current = toolChainId || "";
  }, [toolChainId]);

  const { data: versions = [] } = useQuery<any[]>({
    queryKey: ["toolchain-versions", toolChainId],
    queryFn: () => api.toolchains.versions(toolChainId),
    enabled: !!toolChainId,
  });

  const { data: allToolChains = [] } = useQuery<any[]>({
    queryKey: ["toolchains"],
    queryFn: () => api.toolchains.list(),
  });

  const currentToolChain = useMemo(
    () => (toolChainId ? allToolChains.find((row: any) => row.id === toolChainId) ?? null : null),
    [allToolChains, toolChainId]
  );

  const toolChainDefaultModel = useMemo(() => {
    const metadata = parseMetadata(currentToolChain?.metadataJson);
    const modelRef = metadata?.defaultModelRef;
    if (!modelRef || typeof modelRef !== "object") return null;
    const providerID = String((modelRef as any).providerID || "").trim();
    const modelID = String((modelRef as any).modelID || "").trim();
    if (!providerID || !modelID) return null;
    return { providerID, modelID };
  }, [currentToolChain]);

  const systemSuggestionInfo = useMemo(() => {
    const origin = String(currentToolChain?.origin || "").toLowerCase();
    if (origin !== "system_suggested") return null;
    const approvalStatus = String(currentToolChain?.approvalStatus || "pending");
    const metadata = parseMetadata(currentToolChain?.metadataJson);
    const mappingConfidence = String(metadata.mappingConfidence || "inferred");
    return { approvalStatus, mappingConfidence };
  }, [currentToolChain]);

  const latest = useMemo(() => (Array.isArray(versions) && versions.length > 0 ? versions[0] : null), [versions]);
  const canPublishDraft = useMemo(
    () => Boolean(toolChainId) && Boolean(activeSessionId),
    [toolChainId, activeSessionId]
  );
  const publishDraftDisabledReason = useMemo(() => {
    if (!toolChainId) return "ToolChain is not yet bound to this designer session.";
    if (!activeSessionId) return "No active config session selected.";
    return "";
  }, [toolChainId, activeSessionId]);

  useEffect(() => {
    if (!boardOnlyFullscreen) return;
    const onEsc = (event: KeyboardEvent) => {
      if (event.key === "Escape") setBoardOnlyFullscreen(false);
    };
    document.body.style.overflow = "hidden";
    window.addEventListener("keydown", onEsc);
    return () => {
      document.body.style.overflow = "";
      window.removeEventListener("keydown", onEsc);
    };
  }, [boardOnlyFullscreen]);

  const clampChatPanelWidth = useCallback((candidateWidth: number) => {
    return clampChatPanelWidthValue(candidateWidth);
  }, []);

  useEffect(() => {
    if (!hasHydratedWidthRef.current) {
      hasHydratedWidthRef.current = true;
      return;
    }
    window.localStorage.setItem(CHAT_PANEL_WIDTH_STORAGE_KEY, String(clampChatPanelWidth(chatPanelWidth)));
  }, [chatPanelWidth, clampChatPanelWidth]);

  useEffect(() => {
    const onResize = () => {
      setChatPanelWidth((prev) => clampChatPanelWidth(prev));
    };
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, [clampChatPanelWidth]);

  useEffect(() => {
    if (!isResizingChatPanel) return;
    const onMouseMove = (event: MouseEvent) => {
      const start = chatResizeStartRef.current;
      if (!start) return;
      const delta = start.startX - event.clientX;
      setChatPanelWidth(clampChatPanelWidth(start.startWidth + delta));
    };
    const onMouseUp = () => {
      setIsResizingChatPanel(false);
      chatResizeStartRef.current = null;
    };
    window.addEventListener("mousemove", onMouseMove);
    window.addEventListener("mouseup", onMouseUp);
    return () => {
      window.removeEventListener("mousemove", onMouseMove);
      window.removeEventListener("mouseup", onMouseUp);
    };
  }, [isResizingChatPanel, clampChatPanelWidth]);

  const { data: sessionsData } = useQuery<any>({
    queryKey: ["toolchain-config-sessions", toolChainId],
    queryFn: () => api.toolchains.configSessions(toolChainId),
    enabled: !!toolChainId,
  });

  const sessions = sessionsData?.sessions || [];

  const { data: modelsData = [] } = useQuery<any[]>({
    queryKey: ["models-enabled"],
    queryFn: () => api.get("/models/enabled"),
  });

  const modelsByProvider = useMemo(() => {
    const map = new Map<string, { providerName?: string; models: any[] }>();
    for (const m of modelsData || []) {
      if (m.modelKind === "embedding") continue;
      const pid = m.providerID as string;
      if (!map.has(pid)) map.set(pid, { providerName: m.providerName, models: [] });
      map.get(pid)!.models.push(m);
    }
    return map;
  }, [modelsData]);
  const providerList = useMemo(() => Array.from(modelsByProvider.keys()), [modelsByProvider]);

  useEffect(() => {
    if (!activeSessionId && sessions.length > 0 && !sessionBootstrapPending) {
      setActiveSessionId(sessions[0].id);
    }
  }, [sessions, activeSessionId, sessionBootstrapPending]);

  useEffect(() => {
    const initKey = toolChainId || "__global__";
    if (modelInitializedRef.current !== initKey) {
      modelInitializedRef.current = "";
    }
  }, [toolChainId]);

  useEffect(() => {
    if (!modelsData?.length) return;
    const initKey = toolChainId || "__global__";
    if (modelInitializedRef.current === initKey) return;
    const availableModelKeys = new Set(
      modelsData
        .filter((m) => m.modelKind !== "embedding")
        .map((m) => modelRefKey({ providerID: m.providerID, modelID: m.modelID }))
    );
    const fromToolChain = toolChainDefaultModel
      ? modelRefKey({ providerID: toolChainDefaultModel.providerID, modelID: toolChainDefaultModel.modelID })
      : "";
    const fromLocal = localStorage.getItem("lastModelId") || "";
    const first = modelsData.find((m) => m.modelKind !== "embedding");
    const firstKey = first ? modelRefKey({ providerID: first.providerID, modelID: first.modelID }) : "";
    const chosen = [fromToolChain, fromLocal, firstKey].find((value) => value && availableModelKeys.has(value)) || "";
    if (!chosen) return;
    const parsed = parseModelRefKey(chosen);
    if (!parsed) return;
    setSelectedProvider(parsed.providerID);
    setSelectedModel(chosen);
    localStorage.setItem("lastModelId", chosen);
    modelInitializedRef.current = initKey;
  }, [modelsData, toolChainDefaultModel, toolChainId]);

  const persistDefaultModelMutation = useMutation({
    mutationFn: async (nextModelKey: string) => {
      if (!toolChainId || !currentToolChain) return null;
      const modelRef = parseModelRefKey(nextModelKey);
      const metadata = parseMetadata(currentToolChain.metadataJson);
      if (modelRef) {
        metadata.defaultModelRef = { providerID: modelRef.providerID, modelID: modelRef.modelID };
      } else {
        delete metadata.defaultModelRef;
      }
      return api.toolchains.update(toolChainId, {
        name: currentToolChain.name,
        description: currentToolChain.description,
        enabled: currentToolChain.enabled,
        metadata,
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["toolchains"] });
    },
    onError: (e: any) => toast.error(e.message || "Failed to save default model"),
  });

  const { data: sessionDetail } = useQuery<any>({
    queryKey: ["toolchain-config-session-detail", toolChainId, activeSessionId],
    queryFn: () => api.toolchains.configSessionDetail(toolChainId, activeSessionId!),
    enabled: !!toolChainId && !!activeSessionId,
  });

  const { data: sessionLayout } = useQuery<any>({
    queryKey: ["toolchain-config-session-layout", toolChainId, activeSessionId],
    queryFn: () => api.toolchains.configSessionLayout(toolChainId, activeSessionId!),
    enabled: !!toolChainId && !!activeSessionId,
  });

  const { data: userLayout } = useQuery<any>({
    queryKey: ["toolchain-user-layout", toolChainId],
    queryFn: () => api.toolchains.userLayout(toolChainId),
    enabled: !!toolChainId,
  });

  const catalogTools = useMemo<any[]>(() => {
    const fromBundle = sessionDetail?.contextBundle?.toolsCatalog;
    if (Array.isArray(fromBundle)) return fromBundle;
    return [];
  }, [sessionDetail?.contextBundle?.toolsCatalog]);

  const catalogMcp = useMemo<any[]>(() => {
    const fromBundle = sessionDetail?.contextBundle?.mcpCatalog;
    if (Array.isArray(fromBundle)) return fromBundle;
    return [];
  }, [sessionDetail?.contextBundle?.mcpCatalog]);

  const catalogToolNames = useMemo(() => new Set(catalogTools.map((row: any) => String(row?.name || "").trim()).filter(Boolean)), [catalogTools]);
  const catalogMcpNames = useMemo(() => new Set(catalogMcp.map((row: any) => String(row?.name || "").trim()).filter(Boolean)), [catalogMcp]);

  const canonicalizeGraphForDisplay = useCallback((graph: ToolGraph) => {
    return canonicalizeGraphToolNodeTypes(graph, catalogToolNames, catalogMcpNames);
  }, [catalogToolNames, catalogMcpNames]);

  const mergedLayoutPositions = useMemo(() => {
    const userPositions = normalizeLayoutPositions(userLayout?.positions);
    const sessionPositions = normalizeLayoutPositions(sessionLayout?.positions);
    return mergeLayoutPositions(userPositions, sessionPositions, Boolean(activeSessionId));
  }, [userLayout?.positions, sessionLayout?.positions, activeSessionId]);

  const mergedLayoutViewport = useMemo(() => {
    const sessionViewport = sessionLayout?.viewport && typeof sessionLayout.viewport === "object"
      ? sessionLayout.viewport
      : {};
    const userViewport = userLayout?.viewport && typeof userLayout.viewport === "object"
      ? userLayout.viewport
      : {};
    return activeSessionId
      ? { ...userViewport, ...sessionViewport }
      : { ...sessionViewport, ...userViewport };
  }, [activeSessionId, sessionLayout?.viewport, userLayout?.viewport]);
  const persistedViewport = useMemo(() => readViewport(mergedLayoutViewport), [mergedLayoutViewport]);

  useEffect(() => {
    layoutPositionsRef.current = mergedLayoutPositions;
    layoutViewportRef.current = mergedLayoutViewport;
  }, [mergedLayoutPositions, mergedLayoutViewport]);

  useEffect(() => {
    if (!persistedViewport || !reactFlowRef.current?.setViewport) return;
    reactFlowRef.current.setViewport(persistedViewport, { duration: 0 });
  }, [persistedViewport]);

  useEffect(() => {
    if (activeSessionId) return;
    if (!latest?.graphJson) return;
    try {
      const parsed = normalizeGraph(JSON.parse(latest.graphJson));
      const canonical = canonicalizeGraphForDisplay(parsed);
      if (Array.isArray(canonical.nodes) && Array.isArray(canonical.edges)) {
        setDraftGraph(canonical);
        const { rfNodes, rfEdges } = graphToFlow(canonical, mergedLayoutPositions, 110);
        setNodes(rfNodes);
        setEdges(rfEdges);
      }
    } catch {
      // no-op
    }
  }, [latest, setEdges, setNodes, activeSessionId, mergedLayoutPositions, canonicalizeGraphForDisplay]);

  // Version switcher: when the user picks a specific version from the dropdown,
  // load that version's graphJson into the board. Selecting "Current draft"
  // (null) reverts to the active-session / latest behaviour above.
  useEffect(() => {
    if (viewedVersionNumber == null) return;
    const target = (Array.isArray(versions) ? versions : []).find(
      (v: any) => v.version === viewedVersionNumber
    );
    if (!target?.graphJson) return;
    try {
      const parsed = normalizeGraph(JSON.parse(target.graphJson));
      const canonical = canonicalizeGraphForDisplay(parsed);
      if (Array.isArray(canonical.nodes) && Array.isArray(canonical.edges)) {
        setDraftGraph(canonical);
        const { rfNodes, rfEdges } = graphToFlow(canonical, mergedLayoutPositions, 110);
        setNodes(rfNodes);
        setEdges(rfEdges);
      }
    } catch {
      // no-op
    }
  }, [viewedVersionNumber, versions, setEdges, setNodes, mergedLayoutPositions, canonicalizeGraphForDisplay]);

  const parsedGraph = useMemo<{ nodes: any[]; edges: any[] } | null>(() => {
    if (!draftGraph?.nodes?.length) return null;
    return canonicalizeGraphForDisplay(draftGraph);
  }, [draftGraph, canonicalizeGraphForDisplay]);

  const inspectedNode = useMemo(() => {
    if (!parsedGraph || !inspectedNodeId) return null;
    return parsedGraph.nodes.find((n) => String(n.id) === inspectedNodeId) || null;
  }, [parsedGraph, inspectedNodeId]);

  const editingNode = useMemo(() => {
    if (!parsedGraph || !editingNodeId) return null;
    return parsedGraph.nodes.find((n) => String(n.id) === editingNodeId) || null;
  }, [parsedGraph, editingNodeId]);

  const workflowInputSchema = useMemo(() => {
    if (viewedVersionNumber != null) {
      const target = (Array.isArray(versions) ? versions : []).find((v: any) => v.version === viewedVersionNumber);
      return parseSchemaObject(target?.inputSchema);
    }
    const fromContext = parseSchemaObject(sessionDetail?.contextBundle?.latestInputSchema);
    if (fromContext) return fromContext;
    return parseSchemaObject(latest?.inputSchema);
  }, [viewedVersionNumber, versions, sessionDetail?.contextBundle?.latestInputSchema, latest?.inputSchema]);

  const workflowSynthesisPrompt = useMemo(() => {
    if (viewedVersionNumber != null) {
      const target = (Array.isArray(versions) ? versions : []).find((v: any) => v.version === viewedVersionNumber);
      return String(target?.synthesisPrompt || "").trim();
    }
    const fromSession = String(sessionDetail?.synthesisPrompt || "").trim();
    if (fromSession) return fromSession;
    return String(latest?.synthesisPrompt || "").trim();
  }, [viewedVersionNumber, versions, sessionDetail?.synthesisPrompt, latest?.synthesisPrompt]);

  useEffect(() => {
    return () => {
      if (layoutSaveTimerRef.current !== null) {
        window.clearTimeout(layoutSaveTimerRef.current);
      }
    };
  }, []);

  useEffect(() => {
    if (!sessionDetail) return;
    // While a stream is in flight, the SSE handler is the source of truth for
    // chatMessages, pendingQuestion, status, and the graph. A mid-stream
    // sessionDetail refetch with stale data would otherwise clobber the
    // rendered toolchain graph and the question/answer cards.
    if (isConfigStreaming) return;
    setSessionStatus(sessionDetail.status || "draft");
    if (sessionDetail.pendingQuestion?.question) {
      setPendingQuestion(sessionDetail.pendingQuestion);
    } else {
      setPendingQuestion(null);
    }
    if (shouldRenderDraftGraph(sessionDetail)) {
      try {
        const parsed =
          readGraphFromLayoutViewport(sessionLayout?.viewport) ||
          normalizeGraph(JSON.parse(sessionDetail.graphJson));
        const canonical = canonicalizeGraphForDisplay(parsed);
        const { rfNodes, rfEdges } = graphToFlow(canonical, mergedLayoutPositions, 140);
        if (rfNodes.length > 0) {
          setDraftGraph(canonical);
          setNodes(rfNodes);
          setEdges(rfEdges);
        }
      } catch {
        // ignore parse failures
      }
    }
    // Intentionally DO NOT reset to starterGraph here when sessionDetail has no
    // graphJson — a stale post-stream refetch would otherwise wipe the graph
    // the SSE handler just rendered. New-session reset goes through
    // startNewConfigSession() instead.
    const hydrated: ChatMessage[] = (sessionDetail.messages || [])
      .filter((m: any) => !shouldHideSystemEventForDesigner(m))
      .map((m: any) => ({
      id: String(m.id ?? genId()),
      type: (m.type ?? m.role ?? "assistant") as ChatMessage["type"],
      content: String(m.content ?? ""),
      reasoning: "",
      createdAt: m.createdAt,
      eventType: m.eventType,
      requestId: m.requestId,
      hitlStatus: m.hitlStatus,
      hitlResponse: m.hitlResponse,
      eventPayload: m.eventPayload,
      }));
    setChatMessages(hydrated);
    // Seed pendingInteractions from any hydrated question rows still in
    // "pending" state so the inline answer UI works after a reload.
    const hydratedPending: PendingInteraction[] = hydrated
      .filter((m) => m.type === "system" && m.eventType === "question" && m.hitlStatus === "pending" && !!m.requestId)
      .map((m) => ({
        requestId: m.requestId as string,
        type: "question",
        prompt: m.content,
        metadata: normalizeQuestionMetadata(m.eventPayload?.metadata || m.eventPayload),
      }));
    setPendingInteractions(hydratedPending);
    setInteractionDrafts({});
    setInteractionSelections({});
  }, [sessionDetail, setEdges, setNodes, isConfigStreaming, sessionLayout?.viewport, sessionLayout?.positions, userLayout?.positions, mergedLayoutPositions, canonicalizeGraphForDisplay]);

  const saveLayoutMutation = useMutation({
    mutationFn: ({
      sessionId,
      positions,
      viewport,
    }: {
      sessionId: string;
      positions: Record<string, { x: number; y: number }>;
      viewport?: Record<string, any>;
    }) =>
      api.toolchains.saveConfigSessionLayout(toolChainId, sessionId, { positions, viewport: viewport || {} }),
    onError: (e: any) => toast.error(e.message || "Failed to save board layout"),
  });

  const saveUserLayoutMutation = useMutation({
    mutationFn: ({
      positions,
      viewport,
    }: {
      positions: Record<string, { x: number; y: number }>;
      viewport?: Record<string, any>;
    }) => api.toolchains.saveUserLayout(toolChainId, { positions, viewport: viewport || {} }),
    onError: (e: any) => toast.error(e.message || "Failed to save board layout"),
  });

  const buildNodePositions = useCallback((nodeList: any[]) => {
    return nodeList.reduce((acc, node) => {
      const x = toFiniteNumber(node?.position?.x);
      const y = toFiniteNumber(node?.position?.y);
      if (x !== null && y !== null) {
        acc[String(node.id)] = { x, y };
      }
      return acc;
    }, {} as Record<string, { x: number; y: number }>);
  }, []);

  const persistNodeLayout = useCallback(
    (nextNodes: any[]) => {
      if (!toolChainId) return;
      const positions = buildNodePositions(nextNodes);
      layoutPositionsRef.current = positions;
      if (layoutSaveTimerRef.current !== null) {
        window.clearTimeout(layoutSaveTimerRef.current);
      }
      layoutSaveTimerRef.current = window.setTimeout(() => {
        saveUserLayoutMutation.mutate({ positions, viewport: layoutViewportRef.current || {} });
        if (activeSessionId) {
          saveLayoutMutation.mutate({
            sessionId: activeSessionId,
            positions,
            viewport: layoutViewportRef.current || {},
          });
        }
      }, 450);
    },
    [activeSessionId, saveLayoutMutation, saveUserLayoutMutation, toolChainId, buildNodePositions]
  );

  const persistGraphDraft = useCallback(
    (graph: ToolGraph, nodeSnapshot?: any[]) => {
      setDraftGraph(graph);
      if (!toolChainId || !activeSessionId) return;
      const graphJson = JSON.stringify(graph);
      const nextViewport = { ...(layoutViewportRef.current || {}), [LAYOUT_VIEWPORT_GRAPH_KEY]: graphJson };
      layoutViewportRef.current = nextViewport;
      const positions = buildNodePositions(nodeSnapshot ?? nodes);
      layoutPositionsRef.current = positions;
      saveUserLayoutMutation.mutate({ positions, viewport: nextViewport });
      saveLayoutMutation.mutate({
        sessionId: activeSessionId,
        positions,
        viewport: nextViewport,
      });
    },
    [activeSessionId, buildNodePositions, nodes, saveLayoutMutation, saveUserLayoutMutation, toolChainId]
  );

  const handleNodesChange = useCallback(
    (changes: any[]) => {
      setNodes((current) => {
        const next = applyNodeChanges(changes, current);
        const shouldPersist = changes.some((change: any) => change?.type === "position" && change?.dragging === false);
        if (shouldPersist) {
          persistNodeLayout(next);
          setDraftGraph((prev) => {
            const byId = new Map(next.map((node: any) => [String(node.id), node.position]));
            const updated = normalizeGraph(prev);
            updated.nodes = updated.nodes.map((node) => {
              const position = byId.get(String(node.id));
              return position ? { ...node, position: { x: position.x, y: position.y } } : node;
            });
            return updated;
          });
        }
        return next;
      });
    },
    [persistNodeLayout, setNodes]
  );

  const handleEdgesChange = useCallback(
    (changes: any[]) => {
      setEdges((current) => {
        const next = applyEdgeChanges(changes, current);
        setDraftGraph((prev) => {
          const updated = normalizeGraph(prev);
          updated.edges = flowEdgesToGraph(next);
          persistGraphDraft(updated, nodes);
          return updated;
        });
        return next;
      });
    },
    [persistGraphDraft, setEdges, nodes]
  );

  const handleConnect = useCallback(
    (params: any) => {
      setEdges((current) => {
        const next = addEdge({ ...params, id: `e-${params.source}-${params.target}-${Date.now()}` }, current);
        setDraftGraph((prev) => {
          const updated = normalizeGraph(prev);
          updated.edges = flowEdgesToGraph(next);
          persistGraphDraft(updated, nodes);
          return updated;
        });
        return next;
      });
    },
    [persistGraphDraft, setEdges, nodes]
  );

  const appendSystemMessage = (
    eventType: string,
    content: string,
    requestId?: string,
    eventPayload?: any
  ) => {
    setChatMessages((prev) => [
      ...prev,
      {
        id: genId(),
        type: "system",
        content,
        eventType,
        requestId,
        eventPayload,
        hitlStatus: requestId ? "pending" : undefined,
      },
    ]);
  };

  const markRequestResolved = (requestId: string, response: string) => {
    setChatMessages((prev) =>
      prev.map((m) =>
        m.requestId === requestId && m.hitlStatus === "pending"
          ? { ...m, hitlStatus: "answered", hitlResponse: response }
          : m
      )
    );
    setPendingInteractions((prev) => prev.filter((p) => p.requestId !== requestId));
    setPendingQuestion((prev: any) => (prev && prev.id === requestId ? null : prev));
  };

  const sendConfigChatStream = async (payload: any) => {
    const userText = payload.message || payload.answerText || payload.selectedOptionLabel || payload.selectedOptionId || "";
    const isReplyPayload = !!(payload.answerText || payload.selectedOptionId);
    const targetRequestId = payload.requestId as string | undefined;
    const replyText = payload.selectedOptionLabel || payload.answerText || payload.selectedOptionId || "";
    let forceReplyContinuation = false;
    // If we have a requestId AND a session, ALWAYS try the fast /reply path
    // first — it works whether or not the SSE stream is currently open. We only
    // gate the in-stream-no-requestId case (legacy text answer typed into the
    // composer) on isConfigStreaming so it goes through the existing flow.
    const shouldTryReplyEndpoint = isReplyPayload && activeSessionId && (Boolean(targetRequestId) || isConfigStreaming);
    if (shouldTryReplyEndpoint) {
      // Keep question replies in the HITL card flow (parity with main chat).
      // Do not inject selected answer text as a new user transcript bubble.
      try {
        const replyPayload = {
          sessionId: activeSessionId,
          toolChainId: toolChainId || payload.toolChainId || undefined,
          requestId: targetRequestId,
          answerText: payload.answerText,
          selectedOptionId: payload.selectedOptionId,
          selectedOptionLabel: payload.selectedOptionLabel,
          modelSelectionMode: payload.modelSelectionMode,
          modelRef: payload.modelRef,
        };
        let replyResult: any = null;
        if (toolChainId) {
          replyResult = await api.toolchains.configSessionReply(toolChainId, activeSessionId, replyPayload);
        } else {
          replyResult = await api.toolchains.configSessionReplyGlobal(activeSessionId, replyPayload);
        }
        if (targetRequestId) {
          markRequestResolved(targetRequestId, replyText);
        } else {
          setPendingQuestion(null);
        }
        if (!replyResult?.noActiveStream) {
          return;
        }
        forceReplyContinuation = true;
      } catch (e: any) {
        // The 400 "No active stream is waiting for this session reply" means
        // the stream had already closed (e.g. after page reload). Fall through
        // to the new-stream path so processMessage can resolve the HITL row
        // via the backend safety net.
        const message = String(e?.message || "");
        const isNoActiveStream = /no active stream/i.test(message) || /pending interaction not found/i.test(message);
        if (!isNoActiveStream) {
          toast.error(message || "Failed to submit answer");
          return;
        }
        // Optimistically collapse the inline question card. Backend's
        // processMessage will set the same hitl_status server-side; subsequent
        // hydration confirms it.
        if (targetRequestId) markRequestResolved(targetRequestId, replyText);
        forceReplyContinuation = true;
        // else: fall through to start a fresh stream below.
      }
    }
    if (isConfigStreaming && !forceReplyContinuation) return;
    // Add a user bubble only for fresh user prompts. Don't add one when this
    // call is a fall-through from a HITL answer reply — the answer already
    // appears inline on the now-resolved question card.
    if (userText && !isReplyPayload) {
      setChatMessages((prev) => [...prev, { id: genId(), type: "user", content: userText }]);
    }
    streamedAssistantIdRef.current = null;
    seenQuestionRequestIdsRef.current = new Set();
    setIsConfigStreaming(true);
    // Tracks whether the latest state.updated this stream left a pending question.
    // When true, we keep isConfigStreaming on after done so the user perceives a
    // single continuous turn through the question→answer→continuation cycle.
    let clarificationPending = false;
    let sawTerminalEvent = false;
    try {
      const token = getAuthToken();
      const isRequirementFirst = !toolChainId;
      const url = isRequirementFirst
        ? api.toolchains.configChatGlobalStreamUrl()
        : api.toolchains.configChatStreamUrl(toolChainId);
      // Fresh AbortController for this stream — the composer's Stop button calls
      // streamAbortControllerRef.current?.abort() to cancel the fetch, plus a POST
      // to the backend stop endpoint so the worker thread also unwinds.
      const controller = new AbortController();
      streamAbortControllerRef.current = controller;
      streamSessionIdRef.current = payload.sessionId || null;
      const response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({
          ...payload,
          toolChainId: toolChainId || payload.toolChainId || undefined,
          createIfMissing: isRequirementFirst || payload.createIfMissing === true,
          toolChainName: payload.toolChainName || userText || "AI Generated ToolChain",
        }),
        signal: controller.signal,
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const reader = response.body?.getReader();
      if (!reader) throw new Error("No stream response");
      const decoder = new TextDecoder();
      let buffer = "";
      const processLine = (line: string) => {
        const normalized = line.trim();
        const dataLine = normalized.startsWith("data: ") ? normalized.slice(6) : normalized.startsWith("data:") ? normalized.slice(5) : null;
        if (!dataLine) return;
        try {
          const ev = JSON.parse(dataLine);
          switch (ev.type) {
            case "connected":
              if (ev.sessionId) {
                setActiveSessionId(ev.sessionId);
                streamSessionIdRef.current = ev.sessionId;
              }
              setSessionBootstrapPending(false);
              break;
            case "toolchain.bound":
              if (ev.toolChainId && ev.toolChainId !== toolChainId) {
                setToolChainId(ev.toolChainId);
                queryClient.invalidateQueries({ queryKey: ["toolchains"] });
                navigate(`/toolchains/${ev.toolChainId}/designer`, { replace: true });
              }
              break;
            case "text.delta":
              setChatMessages((prev) => {
                const delta = String(ev.content || "");
                if (!delta) return prev;
                const existingId = streamedAssistantIdRef.current;
                if (existingId) {
                  return prev.map((m) =>
                    m.id === existingId ? { ...m, content: `${m.content || ""}${delta}`, isStreaming: true } : m
                  );
                }
                const nextId = genId();
                streamedAssistantIdRef.current = nextId;
                return [...prev, { id: nextId, type: "assistant", content: delta, reasoning: "", isStreaming: true }];
              });
              break;
            case "reasoning.delta":
              setChatMessages((prev) => {
                const delta = String(ev.content || "");
                if (!delta) return prev;
                const existingId = streamedAssistantIdRef.current;
                if (existingId) {
                  return prev.map((m) =>
                    m.id === existingId
                      ? { ...m, reasoning: `${String(m.reasoning || "")}${delta}`, isStreaming: true }
                      : m
                  );
                }
                const nextId = genId();
                streamedAssistantIdRef.current = nextId;
                return [...prev, { id: nextId, type: "assistant", content: "", reasoning: delta, isStreaming: true }];
              });
              break;
            case "tool.call":
            case "tool.done":
            case "tool.result":
            case "tool.match":
            case "task.started":
            case "task.done":
            case "step.started":
            case "step.finished":
              // Suppress low-level runtime events in ToolChain designer transcript UX.
              break;
            case "question": {
              const requestId = String(ev.requestId || genId());
              const isNew = !seenQuestionRequestIdsRef.current.has(requestId);
              if (isNew) {
                seenQuestionRequestIdsRef.current.add(requestId);
                appendSystemMessage("question", String(ev.question || ""), requestId, {
                  metadata: ev.metadata,
                });
              }
              const normalized = normalizeQuestionMetadata(ev.metadata);
              setPendingInteractions((prev) => [
                ...prev.filter((p) => p.requestId !== requestId),
                { requestId, type: "question", prompt: String(ev.question || ""), metadata: normalized },
              ]);
              setPendingQuestion({
                id: requestId,
                question: ev.question,
                options: ev.metadata?.options || [],
              });
              setSessionStatus("clarification_required");
              break;
            }
            case "state.updated": {
              const state = ev.state || {};
              setSessionStatus(state.status || "draft");
              if (state.toolChainId && state.toolChainId !== toolChainId) {
                setToolChainId(state.toolChainId);
                queryClient.invalidateQueries({ queryKey: ["toolchains"] });
                navigate(`/toolchains/${state.toolChainId}/designer`, { replace: true });
              }
              const embeddedQuestion = state.pendingQuestion;
              clarificationPending = Boolean(embeddedQuestion?.question);
              if (embeddedQuestion?.question) {
                const requestId = String(embeddedQuestion.id || genId());
                const isNew = !seenQuestionRequestIdsRef.current.has(requestId);
                if (isNew) {
                  seenQuestionRequestIdsRef.current.add(requestId);
                  appendSystemMessage("question", String(embeddedQuestion.question || ""), requestId, {
                    metadata: { options: embeddedQuestion.options || [] },
                    key: embeddedQuestion.key,
                  });
                }
                const normalized = normalizeQuestionMetadata({ options: embeddedQuestion.options || [] });
                setPendingInteractions((prev) => [
                  ...prev.filter((p) => p.requestId !== requestId),
                  {
                    requestId,
                    type: "question",
                    prompt: String(embeddedQuestion.question || ""),
                    metadata: normalized,
                  },
                ]);
                setPendingQuestion({
                  id: requestId,
                  question: embeddedQuestion.question,
                  options: embeddedQuestion.options || [],
                });
              } else {
                setPendingQuestion(null);
              }
              if (shouldRenderDraftGraph(state)) {
                try {
                  const parsed = normalizeGraph(JSON.parse(state.graphJson));
                  const canonical = canonicalizeGraphForDisplay(parsed);
                  const { rfNodes, rfEdges } = graphToFlow(canonical, mergedLayoutPositions, 140);
                  if (rfNodes.length > 0) {
                    setDraftGraph(canonical);
                    setNodes(rfNodes);
                    setEdges(rfEdges);
                  }
                } catch {
                  // ignore stream graph parse errors
                }
              }
              break;
            }
            case "compiled":
              setSessionStatus("compiled");
              toast.success(`Compiled ToolChain version v${ev.version}`);
              queryClient.invalidateQueries({ queryKey: ["toolchain-versions", ev.toolChainId || toolChainId] });
              break;
            case "done":
              sawTerminalEvent = true;
              clarificationPending = false;
              setChatMessages((prev) => {
                const doneText = ev.content !== undefined ? String(ev.content || "") : "";
                let next = prev.map((m) => (m.isStreaming ? { ...m, isStreaming: false } : m));
                if (doneText) {
                  if (streamedAssistantIdRef.current) {
                    next = next.map((m) =>
                      m.id === streamedAssistantIdRef.current && !(m.content || "").trim()
                        ? { ...m, content: doneText }
                        : m
                    );
                  } else {
                    const last = next[next.length - 1];
                    if (last?.type === "assistant") {
                      next = next.map((m, idx) =>
                        idx === next.length - 1
                          ? { ...m, content: `${String(m.content || "")}\n\n${doneText}`.trim() }
                          : m
                      );
                    } else {
                      next = [...next, { id: genId(), type: "assistant", content: doneText }];
                    }
                  }
                }
                return next;
              });
              if (ev.toolChainId && ev.toolChainId !== toolChainId) {
                setToolChainId(ev.toolChainId);
                queryClient.invalidateQueries({ queryKey: ["toolchains"] });
                navigate(`/toolchains/${ev.toolChainId}/designer`, { replace: true });
              }
              {
                const chainId = String(ev.toolChainId || activeToolChainRef.current || "");
                const sid = String(ev.sessionId || activeSessionRef.current || "");
                if (chainId) {
                queryClient.invalidateQueries({ queryKey: ["toolchain-config-sessions", chainId] });
                queryClient.invalidateQueries({ queryKey: ["toolchain-versions", chainId] });
                if (sid) {
                  queryClient.invalidateQueries({ queryKey: ["toolchain-config-session-detail", chainId, sid] });
                  queryClient.refetchQueries({ queryKey: ["toolchain-config-session-detail", chainId, sid], type: "active" });
                }
                }
              }
              break;
            case "error":
              sawTerminalEvent = true;
              clarificationPending = false;
              setChatMessages((prev) => prev.map((m) => (m.isStreaming ? { ...m, isStreaming: false } : m)));
              toast.error(ev.message || "ToolChain streaming failed");
              break;
          }
        } catch {
          // ignore malformed SSE event
        }
      };
      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          if (buffer.trim()) processLine(buffer);
          // Some backends close SSE without an explicit terminal event.
          // Treat EOF as stream completion unless we are actively waiting on clarification.
          if (!sawTerminalEvent) {
            clarificationPending = false;
          }
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split(/\r?\n/);
        buffer = lines.pop() || "";
        for (const line of lines) processLine(line);
      }
    } catch (e: any) {
      clarificationPending = false;
      // User-initiated abort flows through here as DOMException("AbortError").
      // Don't surface it as a toast — the user clicked Stop, that's expected.
      const isAbort = e?.name === "AbortError" || /aborted/i.test(String(e?.message || ""));
      if (!isAbort) {
        toast.error(e.message || "Failed to stream ToolChain config");
      }
    } finally {
      // When the AI turn ended on a clarification request, keep the streaming
      // indicator on so the user perceives one continuous turn — the next stream
      // starts as soon as they answer the inline card via /reply.
      if (!clarificationPending) {
        setIsConfigStreaming(false);
      }
      setSessionBootstrapPending(false);
      streamedAssistantIdRef.current = null;
      streamAbortControllerRef.current = null;
      streamSessionIdRef.current = null;
      setChatMessages((prev) => prev.map((m) => (m.isStreaming ? { ...m, isStreaming: false } : m)));
    }
  };

  const cancelConfigStream = useCallback(() => {
    const sid = streamSessionIdRef.current;
    streamAbortControllerRef.current?.abort();
    if (sid && toolChainId) {
      // Best-effort backend cancel — the worker thread releases any pending
      // HITL futures and stops emitting. Failures are silent because the
      // abort already closed the local stream.
      api.toolchains.cancelConfigStream(toolChainId, sid).catch(() => {});
    }
  }, [toolChainId]);

  const startNewConfigSession = () => {
    // UI-only reset — match how the regular AI chat handles "+ New Chat".
    // The backend session row is created lazily on the first user message
    // (sendConfigChatStream's createIfMissing path), so clicking + must NOT
    // open an SSE stream with an empty payload.
    setPendingQuestion(null);
    setPendingInteractions([]);
    setInteractionDrafts({});
    setInteractionSelections({});
    setSessionStatus("draft");
    setChatMessages([]);
    setDraftGraph(normalizeGraph({ nodes: [], edges: [] }));
    setNodes(starterGraph.nodes);
    setEdges(starterGraph.edges);
    setActiveSessionId(null);
    setSessionBootstrapPending(false);
    setShowSessionsMenu(false);
  };

  const answerPendingInteraction = async (
    requestId: string,
    action: InteractionAction,
    selectedOptionIds?: string[]
  ) => {
    if (action !== "reply") return; // config chat only supports question replies
    const customMessage = (interactionDrafts[requestId] || "").trim();
    const selected = selectedOptionIds || interactionSelections[requestId] || [];
    const selectedOptionId = selected[0];
    const pending = pendingInteractions.find((p) => p.requestId === requestId);
    const selectedOptionLabel = pending?.metadata?.options?.find((o) => o.id === selectedOptionId)?.label;
    void sendConfigChatStream({
      sessionId: activeSessionId,
      toolChainId: toolChainId || undefined,
      requestId,
      answerText: customMessage || undefined,
      selectedOptionId,
      selectedOptionLabel,
      modelSelectionMode,
      modelRef: selectedModel ? parseModelRefKey(selectedModel) : null,
    });
  };

  // Single-click Publish Draft: validates the artifact server-side (compile)
  // then publishes immediately. Compile alone never created a version row —
  // upsertDraftVersion already does that on every architect edit — so the
  // user only needs one button.
  const publishDraftMutation = useMutation({
    mutationFn: async () => {
      try {
        await api.toolchains.compileSession(toolChainId, activeSessionId);
      } catch (e: any) {
        // Compile is a validation pass; if the artifact is malformed surface
        // the message and stop. publishSession would fail with the same.
        throw new Error(e?.message || "Validation failed before publish");
      }
      return api.toolchains.publishSession(toolChainId, activeSessionId);
    },
    onSuccess: (result: any) => {
      queryClient.invalidateQueries({ queryKey: ["toolchain-versions", toolChainId] });
      queryClient.invalidateQueries({ queryKey: ["toolchains"] });
      toast.success(`Published draft as v${result?.version ?? ""}`.trim());
    },
    onError: (e: any) => toast.error(e.message || "Failed to publish draft"),
  });

  const updateSessionMutation = useMutation({
    mutationFn: ({ sessionId, payload }: { sessionId: string; payload: any }) =>
      api.toolchains.updateConfigSession(toolChainId, sessionId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["toolchain-config-sessions", toolChainId] });
      toast.success("Session updated");
    },
    onError: (e: any) => toast.error(e.message || "Failed to update session"),
  });

  const deleteSessionMutation = useMutation({
    mutationFn: (sessionId: string) => api.toolchains.deleteConfigSession(toolChainId, sessionId),
    onSuccess: (_, sid) => {
      queryClient.invalidateQueries({ queryKey: ["toolchain-config-sessions", toolChainId] });
      if (activeSessionId === sid) setActiveSessionId(null);
      toast.success("Session deleted");
    },
    onError: (e: any) => toast.error(e.message || "Failed to delete session"),
  });

  const publish = useMutation({
    mutationFn: (version: number) => api.toolchains.publishVersion(toolChainId, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["toolchain-versions", toolChainId] });
      toast.success("Version published");
    },
    onError: (e: any) => toast.error(e.message || "Failed to publish version"),
  });

  const handleNodeSave = useCallback(
    (nextNode: ToolGraphNode) => {
      setDraftGraph((prev) => {
        const graph = normalizeGraph(prev);
        graph.nodes = graph.nodes.map((node) => (String(node.id) === String(nextNode.id) ? { ...nextNode } : node));
        const warnings = collectGraphWarnings(graph);
        if (warnings.length > 0) {
          toast.warning("Saved with validation warnings. Check node inspector for details.");
        } else {
          toast.success("Node updated");
        }
        persistGraphDraft(graph, nodes);
        const { rfNodes, rfEdges } = graphToFlow(graph, mergedLayoutPositions, 140);
        setNodes(rfNodes);
        setEdges(rfEdges);
        return graph;
      });
    },
    [persistGraphDraft, setEdges, setNodes, mergedLayoutPositions, nodes]
  );

  return (
    <div
      className={cn(
        "space-y-4",
        boardOnlyFullscreen ? "fixed inset-0 z-[100] bg-white" : ""
      )}
    >
      <div className={cn("flex items-start justify-between gap-4", boardOnlyFullscreen && "hidden")}>
        <div className="space-y-1">
          <Button
            variant="outline"
            className="h-8 border-[#93C5FD] bg-[#EFF6FF] px-3 text-[#123262] hover:bg-[#DBEAFE]"
            onClick={() => navigate("/toolchains")}
          >
            <ArrowLeft className="mr-1 h-4 w-4" />
            Back to ToolChains
          </Button>
          <h2 className="text-xl font-semibold text-[#123262]">ToolChain Designer</h2>
          <p className="text-sm text-slate-600">AI-driven workflow designer.</p>
          {systemSuggestionInfo ? (
            <p className="text-xs text-amber-700">
              System Suggested • Approval: {toTitleCase(systemSuggestionInfo.approvalStatus)} • Mapping confidence: {toTitleCase(systemSuggestionInfo.mappingConfidence)}
            </p>
          ) : null}
        </div>
        <div className="relative flex min-w-[560px] flex-col items-end gap-2">
          <div className="flex items-center gap-2">
            {Array.isArray(versions) && versions.length > 0 ? (
              <SearchableSelect
                options={[
                  { value: "__current__", label: "Current draft", sublabel: "live editing graph" },
                  ...versions.map((v: any) => ({
                    value: String(v.version),
                    label: `v${v.version}${v.published ? " (published)" : ""}`,
                    sublabel: `responseMode: ${v.responseMode || "hybrid"}`,
                  })),
                ]}
                value={viewedVersionNumber == null ? "__current__" : String(viewedVersionNumber)}
                onValueChange={(value) => {
                  if (value === "__current__") setViewedVersionNumber(null);
                  else setViewedVersionNumber(Number(value));
                }}
                placeholder="Version"
                searchPlaceholder="Search versions…"
                className="w-44"
                triggerTitle="Switch the board to a different version of this ToolChain"
                triggerAriaLabel="Switch viewed version"
              />
            ) : null}
            <Button size="sm" variant="outline" className="h-8 px-3 text-xs" onClick={() => setVersionsOpen(true)}>Versions</Button>
            <Button
              size="sm"
              className="h-8 px-3 text-xs"
              onClick={() => publishDraftMutation.mutate()}
              disabled={!canPublishDraft || publishDraftMutation.isPending}
              title={!canPublishDraft ? publishDraftDisabledReason : undefined}
            >
              {publishDraftMutation.isPending ? "Publishing..." : "Publish Draft"}
            </Button>
          </div>
          <div className="flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-2 py-2 shadow-sm">
            <SearchableSelect
              options={[
                { value: "manual", label: "Manual Model", sublabel: "manual" },
                { value: "auto", label: "Auto Route", sublabel: "auto" },
              ]}
              value={modelSelectionMode}
              onValueChange={(value) => setModelSelectionMode(value as "manual" | "auto")}
              placeholder="Routing"
              searchPlaceholder="Search mode…"
              className="w-32"
              triggerTitle="Choose model routing mode"
              triggerAriaLabel="Choose model routing mode"
            />
            <SearchableSelect
              options={providerList.map((pid) => ({
                value: pid,
                label: modelsByProvider.get(pid)?.providerName || pid,
                sublabel: pid,
              }))}
              value={selectedProvider}
              onValueChange={(pid) => {
                setSelectedProvider(pid);
                const models = modelsByProvider.get(pid)?.models ?? [];
                if (models.length > 0) {
                  const key = modelRefKey({ providerID: pid, modelID: models[0].modelID });
                  setSelectedModel(key);
                  localStorage.setItem("lastModelId", key);
                  persistDefaultModelMutation.mutate(key);
                }
              }}
              placeholder="Provider"
              searchPlaceholder="Search providers…"
              className="w-32"
            />
            <SearchableSelect
              options={(modelsByProvider.get(selectedProvider)?.models ?? []).map((m: any) => ({
                value: modelRefKey({ providerID: m.providerID, modelID: m.modelID }),
                label: m.displayName,
                sublabel: m.modelID,
              }))}
              value={selectedModel}
              onValueChange={(value) => {
                setSelectedModel(value);
                localStorage.setItem("lastModelId", value);
                persistDefaultModelMutation.mutate(value);
              }}
              placeholder="Select model"
              searchPlaceholder="Search models…"
              className="w-40"
            />
          </div>
        </div>
      </div>

      <div
        className={cn("rounded-lg border bg-white p-2", boardOnlyFullscreen && "h-full rounded-none border-0 p-0")}
        style={{
          height: boardOnlyFullscreen ? "100vh" : "calc(100vh - 220px)",
        }}
      >
        <div className="flex h-full">
          <div className="flex min-w-0 flex-1 flex-col">
            <div className="relative min-h-0 flex-1 rounded-md border border-slate-200 bg-white">
              <div className="absolute right-3 top-3 z-20 flex items-center gap-1.5">
                <button
                  type="button"
                  aria-label={boardOnlyFullscreen ? "Exit board fullscreen" : "Board fullscreen"}
                  title={boardOnlyFullscreen ? "Exit board fullscreen (Esc)" : "Show only the flow board"}
                  className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-slate-200 bg-white text-slate-600 shadow-sm hover:bg-slate-50"
                  onClick={() => setBoardOnlyFullscreen((v) => !v)}
                >
                  {boardOnlyFullscreen ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
                </button>
                {chatPanelCollapsed && !boardOnlyFullscreen ? (
                  <button
                    type="button"
                    aria-label="Open AI chat"
                    title="Open AI chat"
                    className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-slate-200 bg-white text-slate-600 shadow-sm hover:bg-slate-50"
                    onClick={() => setChatPanelCollapsed(false)}
                  >
                    <PanelRight size={14} />
                  </button>
                ) : null}
              </div>
              <ReactFlow
                nodes={nodes}
                edges={edges}
                onNodesChange={handleNodesChange}
                onEdgesChange={handleEdgesChange}
                onConnect={handleConnect}
                onNodeClick={(_, n) => setInspectedNodeId(String(n.id))}
                onNodeDoubleClick={(_, n) => {
                  setInspectedNodeId(String(n.id));
                  setEditingNodeId(String(n.id));
                }}
                onMoveEnd={(_, viewport) => {
                  layoutViewportRef.current = {
                    ...(layoutViewportRef.current || {}),
                    x: viewport.x,
                    y: viewport.y,
                    zoom: viewport.zoom,
                  };
                }}
                onPaneClick={() => setInspectedNodeId(null)}
                nodeTypes={nodeTypes}
                edgeTypes={edgeTypes}
                onInit={(instance) => {
                  reactFlowRef.current = instance;
                  if (persistedViewport) {
                    instance.setViewport(persistedViewport, { duration: 0 });
                  }
                }}
                fitView={!persistedViewport}
                proOptions={{ hideAttribution: true }}
                style={{ width: "100%", height: "100%" }}
              >
                <Background />
                <Controls />
                <MiniMap />
              </ReactFlow>
            </div>
            <NodeBottomInspectorPanel
              open={inspectedNode !== null}
              onOpenChange={(o) => {
                if (!o) setInspectedNodeId(null);
              }}
              node={inspectedNode}
              graph={parsedGraph}
              synthesisPrompt={workflowSynthesisPrompt}
              inputSchema={workflowInputSchema}
              toolsCatalog={catalogTools}
              mcpCatalog={catalogMcp}
              onEdit={(id) => setEditingNodeId(id)}
            />
          </div>

          <div
            role="separator"
            aria-orientation="vertical"
            aria-label="Resize AI panel"
            className={cn(
              "mx-1 h-full w-1.5 shrink-0 cursor-col-resize rounded bg-transparent transition-colors hover:bg-slate-200",
              isResizingChatPanel ? "bg-slate-300" : "",
              (boardOnlyFullscreen || chatPanelCollapsed) && "hidden"
            )}
            onMouseDown={(event) => {
              event.preventDefault();
              chatResizeStartRef.current = { startX: event.clientX, startWidth: chatPanelWidth };
              setIsResizingChatPanel(true);
            }}
          />

          <div
            className={cn("relative h-full shrink-0", (boardOnlyFullscreen || chatPanelCollapsed) && "hidden")}
            style={{ width: chatPanelWidth, maxWidth: "55vw", minWidth: 320 }}
          >
            {showSessionsMenu ? (
              <div className="absolute right-2 top-12 z-40 w-[280px]">
                <ToolChainSessionList
                  sessions={sessions}
                  activeSessionId={activeSessionId}
                  onSelect={(sessionId) => {
                    setActiveSessionId(sessionId);
                    setShowSessionsMenu(false);
                  }}
                  onNewSession={() => {
                    startNewConfigSession();
                  }}
                  onRenameSession={(sessionId, title) => {
                    updateSessionMutation.mutate({ sessionId, payload: { title } });
                    setShowSessionsMenu(false);
                  }}
                  onArchiveSession={(sessionId) => {
                    updateSessionMutation.mutate({ sessionId, payload: { archived: true } });
                    setShowSessionsMenu(false);
                  }}
                  onRestoreSession={(sessionId) => {
                    updateSessionMutation.mutate({ sessionId, payload: { archived: false } });
                    setShowSessionsMenu(false);
                  }}
                  onDeleteSession={(sessionId) => {
                    deleteSessionMutation.mutate(sessionId);
                    setShowSessionsMenu(false);
                  }}
                />
              </div>
            ) : null}
            <ToolChainConfigChatPanel
              messages={chatMessages}
              pendingQuestion={pendingQuestion}
              pendingInteractions={pendingInteractions}
              interactionDrafts={interactionDrafts}
              interactionSelections={interactionSelections}
              setInteractionDrafts={setInteractionDrafts}
              setInteractionSelections={setInteractionSelections}
              answerPending={answerPendingInteraction}
              onSend={(payload) => {
                const resolvedModelKey =
                  selectedModel ||
                  (() => {
                    const first = modelsData?.find((m: any) => m.modelKind !== "embedding");
                    return first ? modelRefKey({ providerID: first.providerID, modelID: first.modelID }) : "";
                  })();
                void sendConfigChatStream({
                  sessionId: activeSessionId,
                  toolChainId: toolChainId || undefined,
                  createIfMissing: !toolChainId,
                  toolChainName: payload.message || "AI Generated ToolChain",
                  toolChainDescription: payload.message || "Generated from requirements",
                  message: payload.message,
                  answerText: payload.answerText,
                  selectedOptionId: payload.selectedOptionId,
                  selectedOptionLabel: payload.selectedOptionLabel,
                  attachments: payload.attachments || [],
                  modelSelectionMode,
                  modelRef: resolvedModelKey ? parseModelRefKey(resolvedModelKey) : null,
                });
              }}
              isSending={isConfigStreaming}
              onNewSession={() => {
                startNewConfigSession();
              }}
              onShowHistory={() => setShowSessionsMenu((v) => !v)}
              onCancelStream={cancelConfigStream}
              onCollapse={() => setChatPanelCollapsed(true)}
              className="h-full"
              onResendMessage={async (content, messageId) => {
                // Match ChatPage's edit-and-resend semantics: rewind the conversation by
                // deleting every message in this session at or after the edited message,
                // then re-send the (possibly edited) content. The backend persists the new
                // user turn and the architect responds against the truncated history.
                if (toolChainId && activeSessionId && messageId) {
                  // Find the dbId — chatMessages keeps the local id; the persisted row's
                  // dbId is set on hydration. Fall back to the local id if dbId is unset.
                  const target = chatMessages.find((m) => m.id === messageId);
                  const truncateId = target?.dbId || messageId;
                  try {
                    await api.toolchains.truncateConfigSession(toolChainId, activeSessionId, truncateId);
                  } catch (err: any) {
                    // Non-fatal — proceed with the resend even if truncate failed (e.g. the
                    // message was never persisted server-side because the prior turn errored).
                    console.warn("[Designer] truncate failed:", err?.message || err);
                  }
                }
                // Slice the local chat to drop the edited message and everything after it,
                // matching the backend truncate. The next stream's persisted user message +
                // refetch will rebuild the transcript with the new turn at the end.
                setChatMessages((prev) => {
                  const idx = prev.findIndex((m) => m.id === messageId);
                  return idx >= 0 ? prev.slice(0, idx) : prev;
                });
                setPendingQuestion(null);
                setPendingInteractions([]);
                void sendConfigChatStream({
                  sessionId: activeSessionId,
                  toolChainId: toolChainId || undefined,
                  createIfMissing: !toolChainId,
                  toolChainName: content || "AI Generated ToolChain",
                  toolChainDescription: content || "Generated from requirements",
                  message: content,
                  modelSelectionMode,
                  modelRef: selectedModel ? parseModelRefKey(selectedModel) : null,
                });
              }}
            />
          </div>
        </div>
      </div>

      <Dialog open={versionsOpen} onOpenChange={setVersionsOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>ToolChain Versions</DialogTitle>
          </DialogHeader>
          <div className="max-h-[380px] space-y-2 overflow-auto">
            {versions.length === 0 ? (
              <div className="rounded border border-dashed p-4">
                <p className="text-sm font-medium text-slate-700">No versions found</p>
                <p className="mt-1 text-xs text-slate-500">
                  This ToolChain has no saved versions yet. Use AI/chat edits and click Publish Draft to create/publish a version.
                </p>
              </div>
            ) : (
              versions.map((v: any) => (
                <div key={v.id} className="flex items-center justify-between rounded border p-2">
                  <div>
                    <p className="text-sm font-medium">v{v.version} {v.published ? "(published)" : ""}</p>
                    <p className="text-xs text-slate-500">responseMode: {v.responseMode || "hybrid"}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    <Button size="sm" onClick={() => publish.mutate(v.version)} disabled={publish.isPending || v.published}>Publish</Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={v.published}
                      onClick={() => {
                        setRollbackVersion(v.version);
                        setRollbackText("");
                        setRollbackOpen(true);
                      }}
                    >
                      Rollback
                    </Button>
                  </div>
                </div>
              ))
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setVersionsOpen(false)}>Close</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog open={rollbackOpen} onOpenChange={setRollbackOpen}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>Confirm Rollback</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-slate-600">
            This will repoint published state to version v{rollbackVersion}. Existing runs stay unchanged, but new runs will use the rolled back version.
          </p>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRollbackOpen(false)}>Cancel</Button>
            <Button
              onClick={() => {
                setRollbackOpen(false);
                setRollbackVerifyOpen(true);
              }}
            >
              Continue
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog open={rollbackVerifyOpen} onOpenChange={setRollbackVerifyOpen}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>Final Confirmation</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-slate-600">Type ROLLBACK to confirm publishing version v{rollbackVersion}.</p>
          <Input value={rollbackText} onChange={(e) => setRollbackText(e.target.value)} placeholder="ROLLBACK" />
          <DialogFooter>
            <Button variant="outline" onClick={() => setRollbackVerifyOpen(false)}>Cancel</Button>
            <Button
              disabled={rollbackText.trim().toUpperCase() !== "ROLLBACK" || rollbackVersion == null || publish.isPending}
              onClick={() => {
                if (rollbackVersion == null) return;
                publish.mutate(rollbackVersion, {
                  onSuccess: () => {
                    setRollbackVerifyOpen(false);
                    setRollbackVersion(null);
                    setRollbackText("");
                  },
                });
              }}
            >
              Confirm Rollback
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <NodeEditorDialog
        open={editingNode !== null}
        onOpenChange={(o) => {
          if (!o) setEditingNodeId(null);
        }}
        node={editingNode}
        allNodes={parsedGraph?.nodes || []}
        onSave={handleNodeSave}
      />
    </div>
  );
}
