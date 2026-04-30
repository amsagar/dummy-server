import * as React from "react";
import { MermaidBlock } from "./MermaidBlock";

type MarkdownCodeProps = React.ComponentPropsWithoutRef<"code"> & {
  inline?: boolean;
};

export function useMarkdownComponents() {
  return React.useMemo(
    () => ({
      code({ inline, className, children, ...props }: MarkdownCodeProps) {
        const match = /language-([\w-]+)/.exec(className || "");
        const language = match?.[1]?.toLowerCase();
        const code = String(children ?? "").replace(/\n$/, "");
        if (!inline && language === "mermaid") {
          return <MermaidBlock chart={code} />;
        }
        return (
          <code className={className} {...props}>
            {children}
          </code>
        );
      },
    }),
    []
  );
}
