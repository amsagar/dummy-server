import type { ChatMessage } from "./types";

export type RenderItem =
  | { kind: "msg"; msg: ChatMessage; idx: number }
  | { kind: "group"; msgs: Array<{ msg: ChatMessage; idx: number }> };

export function turnOrderMessages(messages: ChatMessage[]): Array<{ msg: ChatMessage; idx: number }> {
  const result: Array<{ msg: ChatMessage; idx: number }> = [];
  let cursor = 0;
  while (cursor < messages.length) {
    const current = messages[cursor];
    if (current.type !== "user") { result.push({ msg: current, idx: cursor }); cursor++; continue; }
    result.push({ msg: current, idx: cursor }); cursor++;
    const segment: Array<{ msg: ChatMessage; idx: number }> = [];
    while (cursor < messages.length && messages[cursor].type !== "user") {
      segment.push({ msg: messages[cursor], idx: cursor }); cursor++;
    }
    result.push(...segment.filter(e => e.msg.type === "system"), ...segment.filter(e => e.msg.type !== "system"));
  }
  return result;
}

export function buildRenderItems(messages: ChatMessage[]): RenderItem[] {
  const ordered = turnOrderMessages(messages);
  const items: RenderItem[] = [];
  let i = 0;
  while (i < ordered.length) {
    if (ordered[i].msg.type !== "system") {
      items.push({ kind: "msg", msg: ordered[i].msg, idx: ordered[i].idx }); i++;
    } else {
      const group: Array<{ msg: ChatMessage; idx: number }> = [];
      while (i < ordered.length && ordered[i].msg.type === "system") { group.push(ordered[i]); i++; }
      items.push({ kind: "group", msgs: group });
    }
  }
  return items;
}
