export type MsgType = "user" | "assistant" | "system";

export interface ChatMessage {
  id: string;
  type: MsgType;
  content: string;
  reasoning?: string;
  isStreaming?: boolean;
  dbId?: string;
  createdAt?: number;
  turnId?: string;
  eventType?: string;
  requestId?: string;
  hitlStatus?: string;
  hitlResponse?: string;
  eventPayload?: any;
}

export interface HitlQuestionOption {
  id: string;
  label: string;
}

export interface HitlQuestionMetadata {
  responseMode?: "single_select" | "multi_select" | "text";
  options?: HitlQuestionOption[];
  allowCustomText?: boolean;
  minSelections?: number;
  maxSelections?: number;
}

export interface PendingInteraction {
  requestId: string;
  type: string;
  prompt: string;
  metadata?: HitlQuestionMetadata;
}

export type InteractionAction = "reply" | "approve" | "reject";

export function normalizeQuestionMetadata(raw: any): HitlQuestionMetadata | undefined {
  if (!raw) return undefined;
  const optionsRaw = Array.isArray(raw.options) ? raw.options : [];
  const options: HitlQuestionOption[] = optionsRaw
    .map((opt: any) => {
      if (typeof opt === "string") return { id: opt, label: opt };
      if (opt && typeof opt === "object") {
        const label = String(opt.label ?? opt.id ?? "").trim();
        const id = String(opt.id ?? label).trim();
        if (!id) return null;
        return { id, label: label || id };
      }
      return null;
    })
    .filter(Boolean) as HitlQuestionOption[];
  if (options.length > 0) {
    return {
      responseMode: raw.responseMode || "single_select",
      options,
      allowCustomText: raw.allowCustomText !== false,
      minSelections: raw.minSelections,
      maxSelections: raw.maxSelections,
    };
  }
  if (Array.isArray(raw.questions) && raw.questions.length > 0) {
    const first = raw.questions[0];
    const qOptions = Array.isArray(first?.options) ? first.options : [];
    const normalized = qOptions
      .map((opt: any) => {
        const label = String(opt?.label ?? opt?.id ?? "").trim();
        const id = String(opt?.id ?? label).trim();
        if (!id) return null;
        return { id, label: label || id };
      })
      .filter(Boolean) as HitlQuestionOption[];
    if (normalized.length > 0) {
      return {
        responseMode: "single_select",
        options: normalized,
        allowCustomText: true,
        minSelections: 1,
        maxSelections: 1,
      };
    }
  }
  if (raw.responseMode || raw.allowCustomText !== undefined) {
    return {
      responseMode: raw.responseMode || "text",
      options: [],
      allowCustomText: raw.allowCustomText !== false,
      minSelections: raw.minSelections,
      maxSelections: raw.maxSelections,
    };
  }
  return undefined;
}

export function formatPayload(payload: any): string {
  if (payload === null || payload === undefined) return "";
  if (typeof payload === "string") return payload;
  try {
    return JSON.stringify(payload, null, 2);
  } catch {
    return String(payload);
  }
}

export function formatHitlResponse(response?: string): string {
  if (!response) return "";
  if (response.startsWith("options=")) {
    const parts = response.split(";").map((p) => p.trim());
    const optionsPart = parts.find((p) => p.startsWith("options="));
    const messagePart = parts.find((p) => p.startsWith("message="));
    const selected = optionsPart ? optionsPart.replace("options=", "").trim() : "";
    const text = messagePart ? messagePart.replace("message=", "").trim() : "";
    if (selected && text) return `Selected: ${selected} | Message: ${text}`;
    if (selected) return `Selected: ${selected}`;
    if (text) return text;
  }
  return response;
}
