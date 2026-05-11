// Maps backend descriptor `icon` strings (Lucide names, e.g. "globe") to the
// imported React component. We keep this list small and explicit rather than
// reaching into lucide-react's internals — only icons referenced by our six
// shipped descriptors need to resolve.

import {
  BookOpen,
  Code,
  Globe,
  HelpCircle,
  Plug,
  Sparkles,
  SquareStack,
  Wrench,
  type LucideIcon,
} from "lucide-react";

const ICONS: Record<string, LucideIcon> = {
  "book-open": BookOpen,
  code: Code,
  globe: Globe,
  plug: Plug,
  sparkles: Sparkles,
  wrench: Wrench,
  "square-stack": SquareStack,
};

export function iconFor(name: string | null | undefined): LucideIcon {
  if (!name) return HelpCircle;
  return ICONS[name] ?? HelpCircle;
}
