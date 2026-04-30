import type { ChatMessage } from "./types";

export type RenderItem =
  | { kind: "msg"; msg: ChatMessage; idx: number }
  | { kind: "group"; msgs: Array<{ msg: ChatMessage; idx: number }> };

/**
 * Reorders the timeline so each turn shows: user → system events → assistant,
 * matching how chat history is hydrated.
 */
export function turnOrderMessages(messages: ChatMessage[]): Array<{ msg: ChatMessage; idx: number }> {
  const turnOrdered: Array<{ msg: ChatMessage; idx: number }> = [];
  let cursor = 0;
  while (cursor < messages.length) {
    const current = messages[cursor];
    if (current.type !== "user") {
      turnOrdered.push({ msg: current, idx: cursor });
      cursor++;
      continue;
    }
    turnOrdered.push({ msg: current, idx: cursor });
    cursor++;
    const segment: Array<{ msg: ChatMessage; idx: number }> = [];
    while (cursor < messages.length && messages[cursor].type !== "user") {
      segment.push({ msg: messages[cursor], idx: cursor });
      cursor++;
    }
    const systems = segment.filter((entry) => entry.msg.type === "system");
    const nonSystems = segment.filter((entry) => entry.msg.type !== "system");
    turnOrdered.push(...systems, ...nonSystems);
  }
  return turnOrdered;
}

/**
 * Groups consecutive system messages from a turn-ordered list. User and
 * assistant messages stay as individual items.
 */
export function groupSystemRuns(
  ordered: Array<{ msg: ChatMessage; idx: number }>
): RenderItem[] {
  const items: RenderItem[] = [];
  let i = 0;
  while (i < ordered.length) {
    if (ordered[i].msg.type !== "system") {
      items.push({ kind: "msg", msg: ordered[i].msg, idx: ordered[i].idx });
      i++;
    } else {
      const group: Array<{ msg: ChatMessage; idx: number }> = [];
      while (i < ordered.length && ordered[i].msg.type === "system") {
        group.push({ msg: ordered[i].msg, idx: ordered[i].idx });
        i++;
      }
      items.push({ kind: "group", msgs: group });
    }
  }
  return items;
}

export function buildRenderItems(messages: ChatMessage[]): RenderItem[] {
  return groupSystemRuns(turnOrderMessages(messages));
}
