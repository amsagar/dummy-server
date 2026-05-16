// Extract the top-level process variables a BPMN references in its
// ${...} / #{...} expressions. Used to build a form-style Test panel so
// the user doesn't have to discover the input shape by trial-and-error.
//
// Heuristics:
//  - Pull every ${...} and #{...} expression body.
//  - Take the leading identifier (everything before the first `.`, `[`, or
//    whitespace).
//  - Drop reserved internals (anything starting with `_`, e.g. `_turnId`).
//  - Drop a known-noise allowlist of FEEL/Flowable built-ins.
//  - Track the longest reference seen so callers can show a hint
//    ("used as: order.Addresses[0]").

const RESERVED = new Set<string>([
  "execution",
  "currentLeg",
  "loopCounter",
  "result",
  "true",
  "false",
  "null",
  "now",
  "today",
]);

const KNOWN_OUTPUTS = new Set<string>([
  // Things service tasks write *into* the variable space — not inputs the
  // user needs to provide. Best-effort: extend as new compilers emit them.
  "order",
  "actualSequence",
  "legLines",
  "legSequence",
  "serviceability",
  "containerAvailability",
  "result",
]);

export interface BpmnVarRef {
  name: string;
  // Full expressions where this variable appeared — useful as a tooltip.
  usages: string[];
  // True when the variable is also assigned to (likely a write, not an input).
  likelyOutput: boolean;
}

export function extractBpmnVariables(bpmnXml: string | null | undefined): BpmnVarRef[] {
  if (!bpmnXml) return [];
  const byName = new Map<string, BpmnVarRef>();

  // ${var}, ${var.field}, ${var[0].field}, etc. Also #{...} for compatibility.
  const exprRe = /[$#]\{([^}]+)\}/g;
  let m: RegExpExecArray | null;
  while ((m = exprRe.exec(bpmnXml)) !== null) {
    const body = m[1].trim();
    // Skip FEEL function call results, e.g. ${size(legLines)} → "size" is fine,
    // but we still want "legLines" too. Walk identifiers in the body.
    const idRe = /[A-Za-z_][A-Za-z0-9_]*/g;
    let firstIdent: string | null = null;
    let id: RegExpExecArray | null;
    while ((id = idRe.exec(body)) !== null) {
      const name = id[0];
      if (firstIdent === null) firstIdent = name;
      register(byName, name, body);
    }
    void firstIdent;
  }

  // Also pull <flowable:resultVariable name="X"> declarations as known outputs.
  const resultVarRe = /resultVariable(?:Name)?\s*=\s*"([^"]+)"/g;
  while ((m = resultVarRe.exec(bpmnXml)) !== null) {
    const name = m[1];
    register(byName, name, `resultVariable=${name}`, /* isOutput */ true);
  }

  // <flowable:variable name="X" /> — these are subprocess input/output bindings.
  const namedVarRe = /<flowable:variable\s+[^>]*name\s*=\s*"([^"]+)"/g;
  while ((m = namedVarRe.exec(bpmnXml)) !== null) {
    register(byName, m[1], `<flowable:variable name="${m[1]}">`);
  }

  return [...byName.values()].sort((a, b) => a.name.localeCompare(b.name));
}

function register(out: Map<string, BpmnVarRef>, name: string, usage: string, isOutput = false) {
  if (!name) return;
  if (name.startsWith("_")) return; // internal vars like _turnId / _turnContextId
  if (RESERVED.has(name)) return;
  let v = out.get(name);
  if (!v) {
    v = { name, usages: [], likelyOutput: false };
    out.set(name, v);
  }
  if (v.usages.length < 5 && !v.usages.includes(usage)) v.usages.push(usage);
  if (isOutput || KNOWN_OUTPUTS.has(name)) v.likelyOutput = true;
}

/** Split into the two groups the form should render separately. */
export function partitionVariables(
  vars: BpmnVarRef[],
): { inputs: BpmnVarRef[]; outputs: BpmnVarRef[] } {
  const inputs: BpmnVarRef[] = [];
  const outputs: BpmnVarRef[] = [];
  for (const v of vars) (v.likelyOutput ? outputs : inputs).push(v);
  return { inputs, outputs };
}
