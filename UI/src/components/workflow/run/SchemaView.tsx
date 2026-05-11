// Renders the *type* tree of a JSON value — useful for understanding the
// shape of an upstream activity's output without scrolling through the
// data itself. Arrays show length; objects show field count.

interface SchemaViewProps {
  value: unknown;
}

interface SchemaNode {
  name: string;
  type: string;
  children?: SchemaNode[];
}

function buildSchema(name: string, value: unknown): SchemaNode {
  if (value === null) return { name, type: "null" };
  if (value === undefined) return { name, type: "undefined" };
  if (Array.isArray(value)) {
    const sample = value[0];
    return {
      name,
      type: `array[${value.length}]`,
      children: sample !== undefined ? [buildSchema("[0]", sample)] : [],
    };
  }
  if (typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>);
    return {
      name,
      type: `object{${entries.length}}`,
      children: entries.map(([k, v]) => buildSchema(k, v)),
    };
  }
  return { name, type: typeof value };
}

export function SchemaView({ value }: SchemaViewProps) {
  const tree = buildSchema("(root)", value);
  return (
    <div className="text-xs font-mono p-2">
      <Render node={tree} depth={0} />
    </div>
  );
}

function Render({ node, depth }: { node: SchemaNode; depth: number }) {
  return (
    <div style={{ paddingLeft: depth * 12 }}>
      <span>{node.name}: </span>
      <span className="text-purple-700 dark:text-purple-300">{node.type}</span>
      {node.children?.map((c) => (
        <Render key={c.name} node={c} depth={depth + 1} />
      ))}
    </div>
  );
}
