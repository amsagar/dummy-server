// Extract the user-supplied INPUT variables a BPMN expects.
//
// "Input" here means a `${...}` reference whose leading identifier the
// engine can't resolve internally: it isn't a Spring bean wired via
// flowable:delegateExpression, isn't written by an earlier service task's
// outputBinding, isn't a loop element variable, isn't a Flowable internal,
// and isn't a FEEL function name.
//
// The result is what we render as a form on the rule-test screens so a
// user knows exactly what the BPMN needs to run.

const RESERVED = new Set<string>([
  "execution",
  "loopCounter",
  "currentLeg",
  "result",
  "true",
  "false",
  "null",
]);

// FEEL / Flowable built-in function names that may appear as the leading
// identifier of an expression like ${today()} or ${size(legLines)}.
// We strip the function name itself; arguments are still walked for refs.
const FEEL_FUNCTIONS = new Set<string>([
  "now",
  "today",
  "date",
  "time",
  "duration",
  "string",
  "number",
  "boolean",
  "size",
  "count",
  "sum",
  "min",
  "max",
  "abs",
  "floor",
  "ceiling",
  "round",
  "concatenate",
  "contains",
  "starts_with",
  "ends_with",
  "matches",
  "upper_case",
  "lower_case",
  "substring",
  "split",
  "list_contains",
  "not",
]);

export interface BpmnVarRef {
  name: string;
  // Full expression bodies where this variable appeared — useful as a
  // "used as: ..." tooltip on the form field.
  usages: string[];
}

export function extractBpmnVariables(bpmnXml: string | null | undefined): BpmnVarRef[] {
  if (!bpmnXml) return [];

  // 1. Collect OUTPUT variable names (anything the engine itself writes).
  const outputs = collectOutputs(bpmnXml);

  // 2. Collect DELEGATE bean names — Spring beans, never user inputs.
  const delegates = collectDelegateBeans(bpmnXml);

  // 3. Walk every ${...} / #{...} expression (legacy compiler dialect)
  //    AND every FEEL-bearing field's value (canonical dialect). Register
  //    the leading identifier of each fragment.
  const byName = new Map<string, BpmnVarRef>();

  // 3a. Legacy: ${var} / ${var.path} / #{...} interpolation, anywhere.
  const exprRe = /[$#]\{([^}]+)\}/g;
  let m: RegExpExecArray | null;
  while ((m = exprRe.exec(bpmnXml)) !== null) {
    const body = m[1].trim();
    for (const fragment of splitFragments(body)) {
      registerLead(fragment, body, byName, outputs, delegates);
    }
  }

  // 3b. Canonical: `argTemplate` / `inputsTemplate` are JSON-shaped, and
  //     every string value is a FEEL expression. `feelExpr` is a FEEL
  //     expression directly.
  const feelFieldsRe = new RegExp(
    "<flowable:field\\s+name\\s*=\\s*\"(argTemplate|inputsTemplate|feelExpr|postTransform)\"[^>]*>\\s*<flowable:string>([\\s\\S]*?)</flowable:string>",
    "g",
  );
  let fm: RegExpExecArray | null;
  while ((fm = feelFieldsRe.exec(bpmnXml)) !== null) {
    const fieldName = fm[1];
    const rawBody = fm[2].replace(/<!\[CDATA\[|\]\]>/g, "").trim();
    if (fieldName === "feelExpr") {
      // Entire body is FEEL.
      walkFeel(rawBody, rawBody, byName, outputs, delegates);
      continue;
    }
    // argTemplate / inputsTemplate / postTransform: JSON-with-FEEL-values.
    // Pull every "key":"value" pair and walk value as FEEL.
    const kvRe = /"([^"\\]*(?:\\.[^"\\]*)*)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"/g;
    let kv: RegExpExecArray | null;
    while ((kv = kvRe.exec(rawBody)) !== null) {
      const feel = kv[2];
      // Unescape JSON-string escapes to recover the raw FEEL.
      const unescaped = feel.replace(/\\"/g, '"').replace(/\\\\/g, "\\");
      walkFeel(unescaped, unescaped, byName, outputs, delegates);
    }
  }

  return [...byName.values()].sort((a, b) => a.name.localeCompare(b.name));
}

function walkFeel(
  body: string,
  usage: string,
  byName: Map<string, BpmnVarRef>,
  outputs: Set<string>,
  delegates: Set<string>,
) {
  for (const fragment of splitFragments(body)) {
    registerLead(fragment, usage, byName, outputs, delegates);
  }
}

function registerLead(
  fragment: string,
  usage: string,
  byName: Map<string, BpmnVarRef>,
  outputs: Set<string>,
  delegates: Set<string>,
) {
  const lead = leadingIdentifier(fragment);
  if (!lead) return;
  if (FEEL_FUNCTIONS.has(lead)) return;
  if (RESERVED.has(lead)) return;
  if (lead.startsWith("_")) return;
  if (outputs.has(lead)) return;
  if (delegates.has(lead)) return;
  // Skip pure JSON/FEEL literals — quoted strings, numbers.
  if (/^["'\d]/.test(fragment)) return;
  let v = byName.get(lead);
  if (!v) {
    v = { name: lead, usages: [] };
    byName.set(lead, v);
  }
  if (v.usages.length < 5 && !v.usages.includes(usage)) v.usages.push(usage);
}

// ── Output-variable detection ────────────────────────────────────────────

function collectOutputs(bpmnXml: string): Set<string> {
  const out = new Set<string>();

  // <flowable:field name="outputBinding"><flowable:string>X</flowable:string></flowable:field>
  // Flowable 7's trace-compiled BPMNs use this shape for `order`,
  // `containerAvailabilityRaw`, `dtResult`, `serviceabilityResults`, etc.
  const fieldRe =
    /<flowable:field\s+name\s*=\s*"outputBinding"[^>]*>\s*<flowable:string>\s*(?:<!\[CDATA\[)?([^<\]]+?)(?:\]\]>)?\s*<\/flowable:string>/g;
  let m: RegExpExecArray | null;
  while ((m = fieldRe.exec(bpmnXml)) !== null) {
    addIdent(out, m[1].trim());
  }

  // Legacy attribute form (kept for non-trace-compiled rows).
  const resultAttrRe = /resultVariable(?:Name)?\s*=\s*"([^"]+)"/g;
  while ((m = resultAttrRe.exec(bpmnXml)) !== null) {
    addIdent(out, m[1].trim());
  }

  // Multi-instance subprocess element variables — e.g. `svcReq`.
  const elemVarRe = /flowable:elementVariable\s*=\s*"([^"]+)"/g;
  while ((m = elemVarRe.exec(bpmnXml)) !== null) {
    addIdent(out, m[1].trim());
  }

  // Subprocess/scope-level variable declarations.
  const namedVarRe = /<flowable:variable\s+[^>]*name\s*=\s*"([^"]+)"/g;
  while ((m = namedVarRe.exec(bpmnXml)) !== null) {
    addIdent(out, m[1].trim());
  }

  return out;
}

function collectDelegateBeans(bpmnXml: string): Set<string> {
  const out = new Set<string>();
  // flowable:delegateExpression="${beanName}" — beanName is a Spring bean
  // wired into the engine, not a user input.
  const re = /flowable:delegateExpression\s*=\s*"\s*[$#]\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\s*"/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(bpmnXml)) !== null) {
    out.add(m[1]);
  }
  return out;
}

function addIdent(set: Set<string>, raw: string) {
  // Tolerate stray whitespace / newlines from CDATA. Only register a
  // plain identifier — anything else (JSON literal, FEEL expression)
  // means we mis-matched the regex and should bail.
  const trimmed = raw.trim();
  if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(trimmed)) set.add(trimmed);
}

// ── Expression parsing helpers ───────────────────────────────────────────

// Split an expression body into "fragments" the way a programmer would
// read it — pieces separated by `(`, `,`, or whitespace. Each fragment
// has one identifier-rooted access path we care about.
function splitFragments(body: string): string[] {
  return body
    .split(/[(),\s+\-*\/&|=!<>?:]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

// Strip everything after the first `.` or `[` to get the root identifier
// of an access path like `order.Lines[0].AssignedSiteId` → `order`.
function leadingIdentifier(fragment: string): string | null {
  const m = fragment.match(/^[A-Za-z_][A-Za-z0-9_]*/);
  return m ? m[0] : null;
}

// ── Shared helpers used by the Test screens ──────────────────────────────

/** Pick a starting value for a freshly-discovered variable. The current
 *  prefill is a one-off Pods-Order convenience; for any other shape the
 *  field starts blank and the user fills it in. */
export function defaultForVar(v: BpmnVarRef): string {
  const n = v.name.toLowerCase();
  if (n === "orderid" || n === "ord_id" || n === "id") return "600030447";
  if (n === "usermessage") return "Validate this order";
  return "";
}

/** True when the variable is accessed via a path (`.field` or `[i]`),
 *  signalling the user probably wants to paste a JSON blob. */
export function looksMultiline(usages: string[]): boolean {
  return usages.some((u) => /[.\[]/.test(u));
}

/** Convert a single form field's raw text into a JSON-ready value. */
export function coerce(raw: string): unknown {
  const trimmed = raw.trim();
  if (trimmed === "") return "";
  if (trimmed === "true") return true;
  if (trimmed === "false") return false;
  if (trimmed === "null") return null;
  if (/^-?\d+(\.\d+)?$/.test(trimmed)) return Number(trimmed);
  if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith('"')) {
    try {
      return JSON.parse(trimmed);
    } catch {
      return raw;
    }
  }
  return raw;
}

/**
 * Classify discovered input variables into two buckets:
 *  - `required`: the variable(s) bound to the FIRST tool's inputBindings
 *    (the "lookup" tool — e.g. `Get_OrderID` consumes `${orderId}` as
 *    `ORD_ID`). These are the values a user actually has to provide.
 *  - `suspect`: every other discovered input. These *should* have been
 *    derived from a prior tool's output by the compiler; their presence
 *    almost always means the trace-compiler left a literal as a bare
 *    process variable instead of templating it from `order.<path>`.
 *
 * The classification is best-effort and heuristic. It only fires when we
 * can find the BPMN's first service task — otherwise everything falls
 * back to `required`, matching today's behavior.
 */
export interface ClassifiedVars {
  required: BpmnVarRef[];
  suspect: BpmnVarRef[];
}

export function classifyVariables(
  bpmnXml: string | null | undefined,
  vars: BpmnVarRef[],
): ClassifiedVars {
  if (!bpmnXml || vars.length === 0) return { required: vars, suspect: [] };
  const firstToolInputs = firstServiceTaskInputs(bpmnXml);
  if (firstToolInputs.size === 0) return { required: vars, suspect: [] };

  const required: BpmnVarRef[] = [];
  const suspect: BpmnVarRef[] = [];
  for (const v of vars) {
    if (firstToolInputs.has(v.name)) required.push(v);
    else suspect.push(v);
  }
  return { required, suspect };
}

// Find every variable referenced inside the FIRST service task's input
// fields. Two compiler dialects coexist:
//  - Modern (canonical): `argTemplate` is a JSON object whose VALUES are
//    bare FEEL expressions, e.g. `{"ORD_ID":"orderId"}`.
//  - Legacy / drifted: `inputBindings` uses `${var}` interpolation, e.g.
//    `{"ORD_ID":"${orderId}"}`. Older trace-compiled rows still look like
//    this and we want their forms to work too.
function firstServiceTaskInputs(bpmnXml: string): Set<string> {
  const out = new Set<string>();
  const taskRe = /<serviceTask\b[\s\S]*?<\/serviceTask>/;
  const taskMatch = bpmnXml.match(taskRe);
  if (!taskMatch) return out;
  const taskBlock = taskMatch[0];

  // Try the canonical `argTemplate` field first.
  const argRe =
    /<flowable:field\s+name\s*=\s*"argTemplate"[^>]*>\s*<flowable:string>([\s\S]*?)<\/flowable:string>/;
  const am = taskBlock.match(argRe);
  if (am) {
    const body = am[1].replace(/<!\[CDATA\[|\]\]>/g, "");
    // argTemplate values are JSON strings holding FEEL expressions. Pull
    // every string value and take its leading identifier.
    const valueRe = /"([^"\\]*(?:\\.[^"\\]*)*)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"/g;
    let m: RegExpExecArray | null;
    while ((m = valueRe.exec(body)) !== null) {
      const feel = m[2];
      const ident = feel.trim().match(/^[A-Za-z_][A-Za-z0-9_]*/);
      if (ident) out.add(ident[0]);
    }
    return out;
  }

  // Fall back to legacy `inputBindings` with ${...} interpolation.
  const bindingsRe =
    /<flowable:field\s+name\s*=\s*"inputBindings"[^>]*>\s*<flowable:string>([\s\S]*?)<\/flowable:string>/;
  const bm = taskBlock.match(bindingsRe);
  if (!bm) return out;
  const body = bm[1].replace(/<!\[CDATA\[|\]\]>/g, "");
  const exprRe = /[$#]\{([^}]+)\}/g;
  let m: RegExpExecArray | null;
  while ((m = exprRe.exec(body)) !== null) {
    const ident = m[1].trim().match(/^[A-Za-z_][A-Za-z0-9_]*/);
    if (ident) out.add(ident[0]);
  }
  return out;
}

/** A friendly hint for a suspect variable — speculate where the compiler
 *  probably should have pulled it from. Best-effort: we don't know the
 *  real `order` shape, but the suggestion at least nudges the operator. */
export function suspectHint(name: string): string {
  const n = name.toLowerCase();
  if (n === "zip" || n === "postalcode") {
    return "Likely should be derived from `order.Lines[0].DeliveryAddress.PostalCode` or similar.";
  }
  if (n === "ordertype") {
    return "Likely should be derived from `order.orderType` (compiler left it as a bare input).";
  }
  if (n === "actualsequence" || n.endsWith("sequence")) {
    return "Likely should be derived from `order.Lines` via a FEEL expression — not a user input.";
  }
  return "Likely should be derived from a prior tool's response — compiler may have left it bare.";
}

/** Union the variable lists of N rules, deduping by name and concatenating
 *  usages so the `used as:` hint shows every place the variable appears. */
export function unionVariables(perRule: BpmnVarRef[][]): BpmnVarRef[] {
  const byName = new Map<string, BpmnVarRef>();
  for (const list of perRule) {
    for (const v of list) {
      const existing = byName.get(v.name);
      if (!existing) {
        byName.set(v.name, { name: v.name, usages: [...v.usages] });
      } else {
        for (const u of v.usages) {
          if (existing.usages.length < 8 && !existing.usages.includes(u)) {
            existing.usages.push(u);
          }
        }
      }
    }
  }
  return [...byName.values()].sort((a, b) => a.name.localeCompare(b.name));
}
