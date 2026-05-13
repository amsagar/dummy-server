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

export interface HitlQuestionMetadata {
  responseMode?: "single_select" | "multi_select" | "text";
  options?: { id: string; label: string }[];
  allowCustomText?: boolean;
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
  const options = optionsRaw
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
    .filter(Boolean) as { id: string; label: string }[];
  if (options.length > 0) {
    return { responseMode: raw.responseMode || "single_select", options, allowCustomText: raw.allowCustomText !== false };
  }
  return undefined;
}

export function formatPayload(payload: any): string {
  if (payload === null || payload === undefined) return "";
  if (typeof payload === "string") return payload;
  try { return JSON.stringify(payload, null, 2); } catch { return String(payload); }
}
