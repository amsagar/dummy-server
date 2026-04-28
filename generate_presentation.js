const PptxGenJS = require("pptxgenjs");
const path = require("path");

const pptx = new PptxGenJS();
pptx.layout = "LAYOUT_WIDE"; // 13.33 x 7.5 inches

// ── Palette ───────────────────────────────────────────────────────────────────
const NAVY   = "0A1628";
const BLUE   = "1A6BC4";
const TEAL   = "0E8A7A";
const ORANGE = "E8720C";
const WHITE  = "FFFFFF";
const LGRAY  = "F2F5F9";
const DGRAY  = "3D4F63";
const MGRAY  = "7A8FA6";
const DNAVY  = "060E1C";
const LBLUE  = "B8D4F5";

// ── Helpers ───────────────────────────────────────────────────────────────────
function addRect(slide, x, y, w, h, fill, line) {
  slide.addShape(pptx.ShapeType.rect, {
    x, y, w, h,
    fill: { color: fill },
    line: line || { type: "none" },
  });
}

function addText(slide, text, x, y, w, h, opts = {}) {
  slide.addText(text, {
    x, y, w, h,
    fontSize: opts.fontSize || 13,
    bold: opts.bold || false,
    italic: opts.italic || false,
    color: opts.color || WHITE,
    align: opts.align || "left",
    valign: opts.valign || "middle",
    wrap: true,
    fontFace: "Calibri",
    lineSpacingMultiple: opts.lineSpacingMultiple || 1.15,
    ...opts,
  });
}

function headerBand(slide, title, num) {
  addRect(slide, 0, 0.08, 13.33, 1.05, NAVY);
  addRect(slide, 0, 0, 13.33, 0.08, ORANGE);
  addText(slide, title, 0.45, 0.14, 10.0, 0.55, { fontSize: 25, bold: true, color: WHITE });
  addText(slide, `${num} / 4`, 12.20, 0.22, 0.90, 0.40, { fontSize: 10, color: MGRAY, align: "right" });
}

function footer(slide) {
  addRect(slide, 0, 7.22, 13.33, 0.28, DNAVY);
  addText(slide, "Confidential — Client Presentation  |  2026",
    0.30, 7.22, 12.70, 0.26, { fontSize: 9, color: MGRAY, align: "right" });
}

function featureRow(slide, x, y, w, h, icon, title, desc, accent) {
  addRect(slide, x, y, w, h, WHITE, { color: accent, pt: 1.0 });
  addRect(slide, x, y, 0.50, h, accent);
  addText(slide, icon, x, y + (h / 2) - 0.22, 0.50, 0.44, { fontSize: 17, align: "center", color: WHITE });
  addText(slide, title, x + 0.58, y + 0.06, w - 0.68, 0.34, { fontSize: 11.5, bold: true, color: NAVY });
  addText(slide, desc, x + 0.58, y + 0.42, w - 0.68, h - 0.50, { fontSize: 10, color: DGRAY, valign: "top" });
}

function pillCard(slide, x, y, w, h, title, lines, accent) {
  addRect(slide, x, y, w, h, LGRAY, { color: accent, pt: 1.2 });
  addRect(slide, x, y, w, 0.46, accent);
  addText(slide, title, x + 0.12, y + 0.06, w - 0.24, 0.36, { fontSize: 11, bold: true, color: WHITE });
  addText(slide, lines.join("\n"), x + 0.14, y + 0.54, w - 0.28, h - 0.64,
    { fontSize: 10, color: DGRAY, valign: "top", lineSpacingMultiple: 1.35 });
}

// ══════════════════════════════════════════════════════════════════════════════
// SLIDE 1 — Hero
// ══════════════════════════════════════════════════════════════════════════════
const s1 = pptx.addSlide();
addRect(s1, 0, 0, 13.33, 7.5, NAVY);
// left accent panel
addRect(s1, 0, 0, 5.60, 7.5, BLUE);
// orange stripe
addRect(s1, 5.60, 0, 0.10, 7.5, ORANGE);

// product name
addText(s1, "AI Agent Platform", 0.38, 0.38, 5.0, 0.72, { fontSize: 38, bold: true, color: WHITE });
addText(s1, "Enterprise-grade AI orchestration for your organisation",
  0.38, 1.14, 5.0, 0.52, { fontSize: 12, italic: true, color: LBLUE });

// tagline
addText(s1, "One platform.\nEvery model.\nAny tool.", 0.38, 2.10, 5.0, 2.00,
  { fontSize: 30, bold: true, color: WHITE, valign: "top", lineSpacingMultiple: 1.4 });

// sub-copy
addText(s1, "Build, configure and run intelligent agents with streaming responses,\nsemantic tool retrieval, skill management, and MCP integration.",
  0.38, 4.22, 5.0, 0.90, { fontSize: 11.5, color: LBLUE });

// right side — 6 capability badges in a 2×3 grid
const badges = [
  ["🤖", "Multi-Model Chat"],
  ["🔧", "Tool Registry"],
  ["🧠", "Skills Engine"],
  ["🔌", "MCP Integration"],
  ["📊", "Embedding Search"],
  ["🔒", "JWT Auth & HITL"],
];
badges.forEach(([icon, label], i) => {
  const col = i % 2;
  const row = Math.floor(i / 2);
  const bx = 6.10 + col * 3.40;
  const by = 1.80 + row * 1.50;
  addRect(s1, bx, by, 3.10, 1.20, DNAVY, { color: TEAL, pt: 1.0 });
  addText(s1, icon, bx, by + 0.10, 3.10, 0.50, { fontSize: 22, align: "center", color: WHITE });
  addText(s1, label, bx, by + 0.62, 3.10, 0.46, { fontSize: 11, bold: true, color: WHITE, align: "center" });
});

addRect(s1, 0, 7.22, 13.33, 0.28, DNAVY);
addText(s1, "Confidential — Client Presentation  |  2026",
  0.30, 7.22, 12.70, 0.26, { fontSize: 9, color: MGRAY, align: "right" });

// ══════════════════════════════════════════════════════════════════════════════
// SLIDE 2 — Core Features
// ══════════════════════════════════════════════════════════════════════════════
const s2 = pptx.addSlide();
addRect(s2, 0, 0, 13.33, 7.5, LGRAY);
headerBand(s2, "Core Platform Features", "2");

// Left column — 5 feature rows
const leftFeatures = [
  { icon: "⚡", title: "Real-Time Streaming Agent", accent: BLUE,
    desc: "SSE-based streaming with token-delta delivery, <think> tag reasoning extraction, and context-window budget management." },
  { icon: "🤖", title: "Multi-Model & Auto-Routing", accent: BLUE,
    desc: "Live models.dev catalog (100+ models). Per-model API keys, base URLs, context windows. Auto-routes by capability, cost, and context size." },
  { icon: "🔧", title: "Tool Registry & Semantic Retrieval", accent: TEAL,
    desc: "HTTP proxy tools, OpenAPI/curl import, MCP tools. pgvector embedding index for semantic tool selection — finds the right tool by intent, not keyword." },
  { icon: "🧠", title: "Skills & Knowledge Engine", accent: TEAL,
    desc: "Upload multi-file skill packages (zip). Built-in classpath skills always available. YAML frontmatter metadata, memory-based skill ranking." },
  { icon: "🔌", title: "MCP Server Registry", accent: ORANGE,
    desc: "Register any MCP server (HTTP/SSE/stdio). Auto-discovers tools. Supports API key, bearer, basic auth, and full OAuth2 flows." },
];

leftFeatures.forEach((f, i) => {
  featureRow(s2, 0.35, 1.28 + i * 1.14, 7.30, 1.02, f.icon, f.title, f.desc, f.accent);
});

// Right column — architecture stack
addRect(s2, 7.95, 1.28, 5.05, 5.72, WHITE, { color: NAVY, pt: 1.2 });
addRect(s2, 7.95, 1.28, 5.05, 0.44, NAVY);
addText(s2, "Architecture Stack", 7.95, 1.32, 5.05, 0.38,
  { fontSize: 12, bold: true, color: WHITE, align: "center" });

const stack = [
  { color: BLUE,   label: "React + TypeScript UI" },
  { color: MGRAY,  label: "↕  REST / SSE" },
  { color: NAVY,   label: "Spring Boot  (Java 21)" },
  { color: MGRAY,  label: "↕  Spring AI  |  Tool Callbacks" },
  { color: TEAL,   label: "Agent Orchestrator + Runtime" },
  { color: MGRAY,  label: "↕  MCP Client  |  HTTP Proxy" },
  { color: ORANGE, label: "External Tools & MCP Servers" },
  { color: MGRAY,  label: "↕  pgvector  |  PostgreSQL" },
  { color: BLUE,   label: "Embeddings  ·  Memory  ·  Skills" },
];

stack.forEach((s, i) => {
  addRect(s2, 8.15, 1.82 + i * 0.52, 4.65, 0.44, s.color);
  addText(s2, s.label, 8.15, 1.86 + i * 0.52, 4.65, 0.36,
    { fontSize: 10, bold: s.color !== MGRAY, color: WHITE, align: "center" });
});

footer(s2);

// ══════════════════════════════════════════════════════════════════════════════
// SLIDE 3 — What Users Can Do (UI Walkthrough)
// ══════════════════════════════════════════════════════════════════════════════
const s3 = pptx.addSlide();
addRect(s3, 0, 0, 13.33, 7.5, WHITE);
headerBand(s3, "What Users Can Do — UI Walkthrough", "3");

// 6 UI section cards in 2 rows × 3 cols
const uiCards = [
  {
    title: "💬  AI Chat",
    accent: BLUE,
    lines: [
      "• Streaming chat with any enabled model",
      "• Manual or auto model selection",
      "• File & image attachments (PDF, DOCX, PNG…)",
      "• Session history, rename, archive",
      "• Human-in-the-loop approval prompts",
      "• Mermaid diagram rendering inline",
    ],
  },
  {
    title: "🤖  Models & Embeddings",
    accent: BLUE,
    lines: [
      "• Browse 100+ models from models.dev catalog",
      "• Enable / disable per model",
      "• Store encrypted API keys per provider",
      "• Register custom / self-hosted models",
      "• Manage embedding models for vector search",
      "• View pricing ($/M tokens) and capabilities",
    ],
  },
  {
    title: "🔧  Tools & Domains",
    accent: TEAL,
    lines: [
      "• Organise tools into named domains",
      "• Import from OpenAPI spec or curl command",
      "• Manual HTTP tool builder",
      "• Per-domain auth profiles (API key, OAuth2…)",
      "• Enable / disable individual tools",
      "• Approval gates & permission scopes",
    ],
  },
  {
    title: "🧠  Skills",
    accent: TEAL,
    lines: [
      "• Upload skill packages as .zip files",
      "• Multi-file skills with YAML frontmatter",
      "• Enable / disable skills at runtime",
      "• Built-in system skills always available",
      "• Skill files injected into agent context",
      "• Memory-based skill ranking over time",
    ],
  },
  {
    title: "🔌  MCP Registry",
    accent: ORANGE,
    lines: [
      "• Register remote MCP servers by URL",
      "• Auto-detect auth type on connection",
      "• OAuth2 flow with callback handling",
      "• Discover & cache available tools",
      "• Health checks & connection status",
      "• Per-server tool enable / disable",
    ],
  },
  {
    title: "⚙️  Admin & Platform",
    accent: ORANGE,
    lines: [
      "• Guardrail policies (tool-level rules)",
      "• Runtime hooks (pre/post tool execution)",
      "• Cost tracking per session",
      "• Agent trace replay & evaluation",
      "• Context compaction for long sessions",
      "• JWT auth with per-user data isolation",
    ],
  },
];

uiCards.forEach((card, i) => {
  const col = i % 3;
  const row = Math.floor(i / 3);
  const cx = 0.35 + col * 4.32;
  const cy = 1.30 + row * 2.90;
  pillCard(s3, cx, cy, 4.10, 2.72, card.title, card.lines, card.accent);
});

footer(s3);

// ══════════════════════════════════════════════════════════════════════════════
// SLIDE 4 — Advanced Capabilities & Value
// ══════════════════════════════════════════════════════════════════════════════
const s4 = pptx.addSlide();
addRect(s4, 0, 0, 13.33, 7.5, NAVY);
headerBand(s4, "Advanced Capabilities & Business Value", "4");

// Top metric tiles
const metrics = [
  { num: "100+",   label: "AI models\nout of the box" },
  { num: "pgvector", label: "Semantic tool\nretrieval" },
  { num: "OAuth2",  label: "MCP auth\nsupport" },
  { num: "HITL",   label: "Human-in-the-loop\nworkflows" },
];

metrics.forEach((m, i) => {
  const mx = 0.40 + i * 3.22;
  addRect(s4, mx, 1.28, 2.90, 1.52, "0E1F3A", { color: TEAL, pt: 1.0 });
  addRect(s4, mx, 1.28, 2.90, 0.07, ORANGE);
  addText(s4, m.num, mx, 1.40, 2.90, 0.72, { fontSize: 28, bold: true, color: WHITE, align: "center" });
  addText(s4, m.label, mx, 2.10, 2.90, 0.62, { fontSize: 10, color: LBLUE, align: "center" });
});

// Advanced capabilities — 2 columns
const leftCaps = [
  "🔍  Semantic Tool Retrieval — pgvector embedding index finds the right tool by user intent, not keyword matching",
  "🧩  Context Compaction — automatic session summarisation keeps long conversations within model token budgets",
  "📎  File Attachments — agents can reason over PDFs, DOCX, images, CSV, JSON, YAML up to 5 MB",
  "💾  Persistent Memory — per-user memory store with semantic search, categories, and signal-based ranking",
];

const rightCaps = [
  "🔄  Reasoning Extraction — <think> tag parsing separates model reasoning from final response in real time",
  "📈  Cost Tracking — per-session token usage and cost estimation based on live models.dev pricing data",
  "🛡️  Guardrail Policies — rule-based execution constraints applied before any tool call is dispatched",
  "🔁  Eval Harness — replay and evaluate agent traces for regression testing and quality assurance",
];

leftCaps.forEach((c, i) => {
  addRect(s4, 0.35, 3.06 + i * 0.82, 6.20, 0.72, "0E1F3A");
  addText(s4, c, 0.50, 3.10 + i * 0.82, 6.00, 0.64, { fontSize: 10.5, color: WHITE, valign: "top" });
});

rightCaps.forEach((c, i) => {
  addRect(s4, 6.78, 3.06 + i * 0.82, 6.20, 0.72, "0E1F3A");
  addText(s4, c, 6.93, 3.10 + i * 0.82, 6.00, 0.64, { fontSize: 10.5, color: WHITE, valign: "top" });
});

// Bottom CTA strip
addRect(s4, 0.35, 6.38, 12.60, 0.72, "0E1F3A");
addRect(s4, 0.35, 6.38, 12.60, 0.06, ORANGE);
addText(s4,
  "Spring Boot (Java 21)  ·  React + TypeScript  ·  PostgreSQL + pgvector  ·  Spring AI  ·  MCP  ·  JWT  ·  SSE Streaming",
  0.35, 6.46, 12.60, 0.56,
  { fontSize: 11, bold: true, color: LBLUE, align: "center" });

footer(s4);

// ── Save ──────────────────────────────────────────────────────────────────────
const outPath = path.join(process.env.HOME, "Desktop", "AI_Agent_Platform_Presentation.pptx");
pptx.writeFile({ fileName: outPath })
  .then(() => console.log("✅  Saved:", outPath))
  .catch(err => console.error("❌  Error:", err));
