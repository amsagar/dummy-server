/**
 * Pulls structured "what does this node read and what does it write?" info out
 * of a BPMN XML string for a given element id. Used by the diagram inspector
 * panel — purely deterministic, no LLM call, works against the BPMN that the
 * compiler emits.
 *
 * What gets recognized:
 *   • ServiceTask + delegateExpression="${toolCallDelegate}"     → tool call
 *   • ServiceTask + delegateExpression="${decisionTableDelegate}" → decision table
 *   • ServiceTask + delegateExpression="${feelExtractDelegate}"   → FEEL extract
 *   • Exclusive/Parallel/Inclusive Gateway                       → outgoing branches
 *   • Start / End events                                         → general note
 *   • Anything else                                              → best-effort
 */

export interface NodeInput {
  label: string;
  source: string;
}

export interface NodeOutput {
  name: string;
  description: string;
}

export interface NodeInspection {
  id: string;
  name: string;
  /** Short type label for display: "Service Task", "Exclusive Gateway", etc. */
  typeLabel: string;
  /** Underlying delegate (e.g. "toolCallDelegate") if applicable, else null. */
  delegate: string | null;
  /** One-line plain-English description of what the node does. */
  summary: string;
  inputs: NodeInput[];
  output: NodeOutput | null;
  /** Raw field map — surfaced for debugging in the panel's "Raw" section. */
  rawFields: Record<string, string>;
}

const NODE_TAGS = [
  "serviceTask",
  "userTask",
  "scriptTask",
  "businessRuleTask",
  "task",
  "exclusiveGateway",
  "parallelGateway",
  "inclusiveGateway",
  "eventBasedGateway",
  "startEvent",
  "endEvent",
  "intermediateThrowEvent",
  "intermediateCatchEvent",
  "boundaryEvent",
  "subProcess",
  "callActivity",
] as const;

const TAG_LABEL: Record<string, string> = {
  serviceTask: "Service Task",
  userTask: "User Task",
  scriptTask: "Script Task",
  businessRuleTask: "Business Rule Task",
  task: "Task",
  exclusiveGateway: "Exclusive Gateway",
  parallelGateway: "Parallel Gateway",
  inclusiveGateway: "Inclusive Gateway",
  eventBasedGateway: "Event-based Gateway",
  startEvent: "Start Event",
  endEvent: "End Event",
  intermediateThrowEvent: "Intermediate Throw",
  intermediateCatchEvent: "Intermediate Catch",
  boundaryEvent: "Boundary Event",
  subProcess: "Subprocess",
  callActivity: "Call Activity",
};

export function inspectBpmnNode(xml: string, elementId: string): NodeInspection | null {
  const block = extractBlock(xml, elementId);
  if (!block) return null;

  const attrs = parseOpenTagAttrs(block);
  const localTag = (attrs.__tag ?? "").split(":").pop() ?? "";
  const fields = parseFields(block);
  const delegate = parseDelegate(attrs);
  const name = attrs.name?.trim() || elementId;

  const base: NodeInspection = {
    id: elementId,
    name,
    typeLabel: TAG_LABEL[localTag] ?? localTag,
    delegate,
    summary: "",
    inputs: [],
    output: null,
    rawFields: fields,
  };

  switch (delegate) {
    case "toolCallDelegate":
      return enrichToolCall(base, fields);
    case "decisionTableDelegate":
      return enrichDecisionTable(base, fields);
    case "feelExtractDelegate":
      return enrichFeelExtract(base, fields);
  }

  // Gateways: describe outgoing flow conditions
  if (localTag.endsWith("Gateway")) {
    const outgoing = findOutgoingFlows(xml, elementId);
    base.summary = "Routes flow based on outgoing conditions against process variables.";
    base.inputs = outgoing.map((f) => ({
      label: f.condition ? f.condition : "(default branch)",
      source: `→ ${f.targetLabel ?? f.target}`,
    }));
    base.output = null;
    return base;
  }

  if (localTag === "startEvent") {
    base.summary = "Workflow entry point. Initial process variables (e.g. `orderId`) become available here.";
    return base;
  }
  if (localTag === "endEvent") {
    base.summary = "Workflow terminates. The variable `result` carries the final structured output.";
    return base;
  }

  base.summary = "Custom or unrecognized node — see Raw fields for details.";
  return base;
}

// ── Delegate-specific enrichers ────────────────────────────────────────────

function enrichToolCall(base: NodeInspection, fields: Record<string, string>): NodeInspection {
  const toolName = fields.toolName?.trim() || "(unknown tool)";
  const argTemplate = safeParseJson(fields.argTemplate);
  const inputs: NodeInput[] = isPlainObject(argTemplate)
    ? Object.entries(argTemplate as Record<string, unknown>).map(([label, source]) => ({
        label,
        source: String(source),
      }))
    : [];

  const hasPostTransform = (fields.postTransform ?? "").trim().length > 0;
  return {
    ...base,
    summary: `Calls the **${toolName}** tool. Each input is a FEEL expression evaluated against the current process variables.`,
    inputs,
    output: {
      name: fields.outputBinding || "(unset)",
      description: hasPostTransform
        ? "Raw tool response is post-transformed via FEEL expressions before being stored under this variable."
        : "Raw tool response (parsed JSON) is stored under this variable.",
    },
  };
}

function enrichDecisionTable(base: NodeInspection, fields: Record<string, string>): NodeInspection {
  const tableName = fields.tableName?.trim() || "(unknown table)";
  const inputsTemplate = safeParseJson(fields.inputsTemplate);
  const inputs: NodeInput[] = isPlainObject(inputsTemplate)
    ? Object.entries(inputsTemplate as Record<string, unknown>).map(([label, source]) => ({
        label,
        source: String(source),
      }))
    : [];

  return {
    ...base,
    summary: `Evaluates the **${tableName}** decision table against the resolved inputs.`,
    inputs,
    output: {
      name: fields.outputBinding || "(unset)",
      description: "Result is a `{ matched, rows, outputs }` object stored under this variable.",
    },
  };
}

function enrichFeelExtract(base: NodeInspection, fields: Record<string, string>): NodeInspection {
  const expr = fields.feelExpr?.trim() || "";
  return {
    ...base,
    summary: "Evaluates a FEEL expression against the current process variables and stores the result.",
    inputs: [{ label: "FEEL expression", source: expr }],
    output: {
      name: fields.outputBinding || "(unset)",
      description: "The computed value of the FEEL expression.",
    },
  };
}

// ── Parsing helpers ────────────────────────────────────────────────────────

/** Find the full XML block for the given element id, opening-tag through
 *  closing tag (or just the self-closing tag). Returns null if not found. */
function extractBlock(xml: string, elementId: string): string | null {
  const idEsc = escapeRegex(elementId);
  const tagAlt = NODE_TAGS.join("|");
  const openRegex = new RegExp(
    `<((?:[A-Za-z_][\\w.-]*:)?(?:${tagAlt}))\\b[^>]*\\bid=["']${idEsc}["'][^>]*>`,
    "i",
  );
  const openMatch = openRegex.exec(xml);
  if (!openMatch) return null;

  const fullOpenTag = openMatch[0];
  const tagName = openMatch[1];
  if (fullOpenTag.endsWith("/>")) return fullOpenTag;

  const closeTag = `</${tagName}>`;
  const closeIdx = xml.indexOf(closeTag, openMatch.index + fullOpenTag.length);
  if (closeIdx === -1) return null;
  return xml.substring(openMatch.index, closeIdx + closeTag.length);
}

function parseOpenTagAttrs(block: string): Record<string, string> {
  const out: Record<string, string> = {};
  const tagMatch = /^<([A-Za-z_][\w:.-]*)([\s\S]*?)\/?>/.exec(block);
  if (!tagMatch) return out;
  out.__tag = tagMatch[1];
  const attrRegex = /([A-Za-z_][\w:.-]*)\s*=\s*"([^"]*)"/g;
  let m: RegExpExecArray | null;
  while ((m = attrRegex.exec(tagMatch[2])) !== null) {
    out[m[1]] = m[2];
  }
  return out;
}

function parseFields(block: string): Record<string, string> {
  const fields: Record<string, string> = {};
  const fieldRegex =
    /<(?:[A-Za-z_][\w.-]*:)?field\b[^>]*\bname=["']([^"']+)["'][^>]*>\s*<(?:[A-Za-z_][\w.-]*:)?string\b[^>]*>([\s\S]*?)<\/(?:[A-Za-z_][\w.-]*:)?string>\s*<\/(?:[A-Za-z_][\w.-]*:)?field>/gi;
  let m: RegExpExecArray | null;
  while ((m = fieldRegex.exec(block)) !== null) {
    fields[m[1]] = m[2].trim();
  }
  return fields;
}

function parseDelegate(attrs: Record<string, string>): string | null {
  const raw =
    attrs["flowable:delegateExpression"] ??
    attrs["activiti:delegateExpression"] ??
    attrs["camunda:delegateExpression"] ??
    attrs["delegateExpression"] ??
    "";
  if (!raw) return null;
  const m = /\$\{\s*([A-Za-z_][\w.]*)\s*\}/.exec(raw);
  return m ? m[1] : raw;
}

interface OutgoingFlow {
  condition: string | null;
  target: string;
  targetLabel: string | null;
}

function findOutgoingFlows(xml: string, sourceId: string): OutgoingFlow[] {
  const flows: OutgoingFlow[] = [];
  const flowRegex =
    /<(?:[A-Za-z_][\w.-]*:)?sequenceFlow\b([^>]*?)(?:\/>|>([\s\S]*?)<\/(?:[A-Za-z_][\w.-]*:)?sequenceFlow>)/gi;
  let m: RegExpExecArray | null;
  while ((m = flowRegex.exec(xml)) !== null) {
    const attrs = m[1] ?? "";
    const body = m[2] ?? "";
    const src = /\bsourceRef\s*=\s*"([^"]+)"/.exec(attrs)?.[1];
    if (src !== sourceId) continue;
    const tgt = /\btargetRef\s*=\s*"([^"]+)"/.exec(attrs)?.[1] ?? "(unknown)";
    const condMatch =
      /<(?:[A-Za-z_][\w.-]*:)?conditionExpression\b[^>]*>([\s\S]*?)<\/(?:[A-Za-z_][\w.-]*:)?conditionExpression>/.exec(
        body,
      );
    const condition = condMatch?.[1]?.trim() ?? null;
    flows.push({
      condition,
      target: tgt,
      targetLabel: lookupElementName(xml, tgt),
    });
  }
  return flows;
}

function lookupElementName(xml: string, elementId: string): string | null {
  const idEsc = escapeRegex(elementId);
  const re = new RegExp(`\\bid=["']${idEsc}["'][^>]*\\bname=["']([^"']+)["']`, "i");
  return re.exec(xml)?.[1] ?? null;
}

function safeParseJson(value: string | undefined): unknown {
  if (!value) return null;
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
