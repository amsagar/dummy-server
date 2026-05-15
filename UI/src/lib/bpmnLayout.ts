import BpmnModdle from "bpmn-moddle";
import * as dagre from "@dagrejs/dagre";

/**
 * Lays out a BPMN 2.0 process and injects BPMN-DI as a raw XML string so
 * bpmn-js renders shapes AND sequence-flow arrows reliably.
 *
 * We parse with bpmn-moddle (only to discover flow elements + sequence flows
 * + nested subprocess children), compute positions with dagre, then emit DI
 * by string templating. Going through moddle's toXML for the DI was dropping
 * edges silently — string templating avoids that entirely and is far easier
 * to debug.
 *
 * Returns the original XML with any existing <bpmndi:BPMNDiagram> blocks
 * stripped and replaced with our generated ones.
 */
export async function layoutBpmn(xml: string): Promise<string> {
  const normalizedXml = ensureSequenceFlowIds(xml);
  const moddle = new BpmnModdle();
  const parsed = await moddle.fromXML(normalizedXml);
  const definitions: any = parsed.rootElement;

  const processes: any[] = (definitions.rootElements ?? []).filter(
    (r: any) => r.$type === "bpmn:Process",
  );
  if (processes.length === 0) return xml;

  const diagramXml = processes
    .map((p) => layoutProcessAndSubprocesses(p))
    .filter((s) => !!s)
    .join("\n");

  if (!diagramXml) return xml;

  // Strip any existing diagrams (paired or self-closing); we replace them
  // wholesale. LLM-authored BPMN often has shapes-without-edges DI, which is
  // exactly the case we're working around here.
  const stripped = normalizedXml
    .replace(/<(?:bpmndi:)?BPMNDiagram[\s\S]*?<\/(?:bpmndi:)?BPMNDiagram>/g, "")
    .replace(/<(?:bpmndi:)?BPMNDiagram\b[^>]*\/>/g, "");

  // Ensure the required namespaces are declared on <definitions>.
  const withNamespaces = ensureNamespaces(stripped);

  // Insert our DI block just before the closing definitions tag.
  return withNamespaces.replace(
    /<\/(?:bpmn:)?definitions>/,
    `${diagramXml}\n$&`,
  );
}

/**
 * LLM-generated BPMN often omits IDs on sequenceFlow elements.
 * bpmn-js can infer runtime links, but BPMN-DI edges still need a concrete
 * bpmnElement reference, so we synthesize stable IDs when missing.
 */
function ensureSequenceFlowIds(xml: string): string {
  const usedIds = new Set<string>();
  const idRegex = /\bid=(["'])(.*?)\1/g;
  for (let m = idRegex.exec(xml); m; m = idRegex.exec(xml)) {
    usedIds.add(m[2]);
  }

  let counter = 1;
  return xml.replace(
    /<((?:[A-Za-z_][\w.-]*:)?sequenceFlow)\b([^>]*)>/gi,
    (fullMatch, _tagName: string, attrs: string) => {
      if (/\bid\s*=/.test(attrs)) return fullMatch;
      let candidate = `Flow_auto_${counter++}`;
      while (usedIds.has(candidate)) {
        candidate = `Flow_auto_${counter++}`;
      }
      usedIds.add(candidate);
      return fullMatch.replace(/\/?>$/, ` id="${candidate}"$&`);
    },
  );
}

function ensureNamespaces(xml: string): string {
  return xml.replace(/<(?:bpmn:)?definitions\b[^>]*>/, (match) => {
    let out = match;
    if (!/xmlns:bpmndi=/.test(out)) {
      out = out.replace(/>$/, ' xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI">');
    }
    if (!/xmlns:dc=/.test(out)) {
      out = out.replace(/>$/, ' xmlns:dc="http://www.omg.org/spec/DD/20100524/DC">');
    }
    if (!/xmlns:di=/.test(out)) {
      out = out.replace(/>$/, ' xmlns:di="http://www.omg.org/spec/DD/20100524/DI">');
    }
    return out;
  });
}

const NODE_SIZE: Record<string, { width: number; height: number }> = {
  "bpmn:StartEvent": { width: 36, height: 36 },
  "bpmn:EndEvent": { width: 36, height: 36 },
  "bpmn:IntermediateThrowEvent": { width: 36, height: 36 },
  "bpmn:IntermediateCatchEvent": { width: 36, height: 36 },
  "bpmn:BoundaryEvent": { width: 36, height: 36 },
  "bpmn:ExclusiveGateway": { width: 50, height: 50 },
  "bpmn:ParallelGateway": { width: 50, height: 50 },
  "bpmn:InclusiveGateway": { width: 50, height: 50 },
  "bpmn:Task": { width: 110, height: 80 },
  "bpmn:ServiceTask": { width: 110, height: 80 },
  "bpmn:UserTask": { width: 110, height: 80 },
  "bpmn:ScriptTask": { width: 110, height: 80 },
  "bpmn:BusinessRuleTask": { width: 110, height: 80 },
  "bpmn:SubProcess": { width: 200, height: 100 },
  "bpmn:CallActivity": { width: 110, height: 80 },
};
const DEFAULT_SIZE = { width: 110, height: 80 };

function sizeFor(node: any) {
  return NODE_SIZE[node.$type] ?? DEFAULT_SIZE;
}

function layoutProcessAndSubprocesses(rootProcess: any): string {
  const diagrams: string[] = [];
  const queue: any[] = [rootProcess];

  while (queue.length > 0) {
    const container = queue.shift();
    const diagram = layoutOneContainer(container);
    if (diagram) diagrams.push(diagram);

    const childSubProcesses = (container?.flowElements ?? []).filter(
      (fe: any) => fe.$type === "bpmn:SubProcess",
    );
    queue.push(...childSubProcesses);
  }

  return diagrams.join("\n");
}

function layoutOneContainer(container: any): string {
  const flowElements: any[] = container.flowElements ?? [];
  if (flowElements.length === 0) return "";

  const nodes = flowElements.filter((fe) => fe.$type !== "bpmn:SequenceFlow");
  const flows = flowElements.filter((fe) => fe.$type === "bpmn:SequenceFlow");
  if (nodes.length === 0) return "";

  // Boundary events attach to a host activity's perimeter. We exclude them
  // from the dagre graph (otherwise they get laid out as detached vertices
  // floating next to the host), then place them on the host's bottom-right
  // corner after the main layout has run, n8n-style.
  const boundaryByHost = new Map<string, any[]>();
  const mainNodes: any[] = [];
  for (const n of nodes) {
    if (n.$type === "bpmn:BoundaryEvent") {
      const hostId = n.attachedToRef?.id ?? n.attachedToRef;
      if (hostId) {
        if (!boundaryByHost.has(hostId)) boundaryByHost.set(hostId, []);
        boundaryByHost.get(hostId)!.push(n);
        continue;
      }
    }
    mainNodes.push(n);
  }

  const g = new (dagre as any).graphlib.Graph();
  g.setGraph({ rankdir: "LR", nodesep: 50, ranksep: 90, marginx: 30, marginy: 30 });
  g.setDefaultEdgeLabel(() => ({}));

  for (const n of mainNodes) {
    const { width, height } = sizeFor(n);
    g.setNode(n.id, { width, height });
  }
  // Boundary-event-outgoing flows still need their source coordinates resolved.
  // We compute boundary positions from the host, so we can route their edges
  // by hand. The non-boundary edges go through dagre as usual.
  const dagreFlows: any[] = [];
  const boundaryFlows: any[] = [];
  for (const f of flows) {
    const src = f.sourceRef?.id ?? f.sourceRef;
    const tgt = f.targetRef?.id ?? f.targetRef;
    if (!src || !tgt) continue;
    if (g.hasNode(src) && g.hasNode(tgt)) {
      dagreFlows.push(f);
      g.setEdge(src, tgt, { id: f.id });
    } else if (!g.hasNode(src) && g.hasNode(tgt)) {
      // Source is a boundary event — route manually.
      boundaryFlows.push(f);
    }
  }

  (dagre as any).layout(g);

  // Position boundary events at host's bottom-right corner.
  const boundaryPositions = new Map<string, { x: number; y: number; width: number; height: number }>();
  for (const [hostId, boundaries] of boundaryByHost.entries()) {
    const hostPos = g.node(hostId);
    if (!hostPos) continue;
    boundaries.forEach((be, idx) => {
      const sz = sizeFor(be);
      // Offset each subsequent boundary 24px to the left along the host's
      // bottom edge so multiple boundary events don't stack.
      const cx = hostPos.x + hostPos.width / 2 - 18 - idx * 24;
      const cy = hostPos.y + hostPos.height / 2 - 4;
      boundaryPositions.set(be.id, { x: cx, y: cy, width: sz.width, height: sz.height });
    });
  }

  const mainShapeXml = mainNodes
    .map((n) => {
      const pos = g.node(n.id);
      if (!pos) return "";
      const { width, height } = sizeFor(n);
      const x = Math.round(pos.x - width / 2);
      const y = Math.round(pos.y - height / 2);
      const isExpandedAttr =
        n.$type === "bpmn:SubProcess" ? ' isExpanded="false"' : "";
      return `      <bpmndi:BPMNShape id="${n.id}_di" bpmnElement="${n.id}"${isExpandedAttr}>
        <dc:Bounds x="${x}" y="${y}" width="${width}" height="${height}" />
      </bpmndi:BPMNShape>`;
    })
    .filter(Boolean)
    .join("\n");

  const boundaryShapeXml = Array.from(boundaryPositions.entries())
    .map(([beId, pos]) => {
      const x = Math.round(pos.x - pos.width / 2);
      const y = Math.round(pos.y - pos.height / 2);
      return `      <bpmndi:BPMNShape id="${beId}_di" bpmnElement="${beId}">
        <dc:Bounds x="${x}" y="${y}" width="${pos.width}" height="${pos.height}" />
      </bpmndi:BPMNShape>`;
    })
    .join("\n");

  const dagreEdgeXml = dagreFlows
    .map((f) => {
      const src = f.sourceRef?.id ?? f.sourceRef;
      const tgt = f.targetRef?.id ?? f.targetRef;
      const srcNode = g.node(src);
      const tgtNode = g.node(tgt);
      if (!srcNode || !tgtNode) return "";
      const start = boundaryPoint(srcNode, tgtNode);
      const end = boundaryPoint(tgtNode, srcNode);
      return `      <bpmndi:BPMNEdge id="${f.id}_di" bpmnElement="${f.id}">
        <di:waypoint x="${Math.round(start.x)}" y="${Math.round(start.y)}" />
        <di:waypoint x="${Math.round(end.x)}" y="${Math.round(end.y)}" />
      </bpmndi:BPMNEdge>`;
    })
    .filter(Boolean)
    .join("\n");

  const boundaryEdgeXml = boundaryFlows
    .map((f) => {
      const src = f.sourceRef?.id ?? f.sourceRef;
      const tgt = f.targetRef?.id ?? f.targetRef;
      const bePos = boundaryPositions.get(src);
      const tgtNode = g.node(tgt);
      if (!bePos || !tgtNode) return "";
      const start = { x: bePos.x, y: bePos.y };
      const end = boundaryPoint(tgtNode, { x: bePos.x, y: bePos.y, width: tgtNode.width, height: tgtNode.height });
      return `      <bpmndi:BPMNEdge id="${f.id}_di" bpmnElement="${f.id}">
        <di:waypoint x="${Math.round(start.x)}" y="${Math.round(start.y)}" />
        <di:waypoint x="${Math.round(end.x)}" y="${Math.round(end.y)}" />
      </bpmndi:BPMNEdge>`;
    })
    .filter(Boolean)
    .join("\n");

  const shapesAndEdges = [mainShapeXml, boundaryShapeXml, dagreEdgeXml, boundaryEdgeXml]
    .filter((s) => s && s.length > 0)
    .join("\n");

  return `  <bpmndi:BPMNDiagram id="BPMNDiagram_${container.id}">
    <bpmndi:BPMNPlane id="BPMNPlane_${container.id}" bpmnElement="${container.id}">
${shapesAndEdges}
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>`;
}

interface DagreNode {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Returns the point on `node`'s rectangle border on the line from `node`'s center toward `target`'s center. */
function boundaryPoint(node: DagreNode, target: DagreNode) {
  const dx = target.x - node.x;
  const dy = target.y - node.y;
  if (dx === 0 && dy === 0) return { x: node.x, y: node.y };
  const halfW = node.width / 2;
  const halfH = node.height / 2;
  const absDx = Math.abs(dx);
  const absDy = Math.abs(dy);
  const scaleX = absDx > 0 ? halfW / absDx : Infinity;
  const scaleY = absDy > 0 ? halfH / absDy : Infinity;
  const scale = Math.min(scaleX, scaleY);
  return { x: node.x + dx * scale, y: node.y + dy * scale };
}
