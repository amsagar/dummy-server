import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import { api } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { parseModelRefKey } from "@/types";

type SchemaProperty = {
  type?: string;
  description?: string;
  default?: any;
};

type JsonSchema = {
  type?: string;
  properties?: Record<string, SchemaProperty>;
  required?: string[];
};

type Props = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  toolChainId: string;
  triggerSource: "ui" | "rerun";
  initialVersion?: number;
  initialInput?: Record<string, any>;
  onExecuted?: (result: any) => void;
};

function parseSchema(raw: any): JsonSchema {
  if (!raw || typeof raw !== "string" || !raw.trim()) return { type: "object", properties: {}, required: [] };
  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === "object") {
      return {
        type: parsed.type || "object",
        properties: parsed.properties && typeof parsed.properties === "object" ? parsed.properties : {},
        required: Array.isArray(parsed.required) ? parsed.required : [],
      };
    }
  } catch {
    // fall through
  }
  return { type: "object", properties: {}, required: [] };
}

function convertInputValue(raw: string, type: string | undefined) {
  if (type === "number" || type === "integer") {
    if (!raw.trim()) return null;
    const num = Number(raw);
    if (!Number.isFinite(num)) throw new Error("must be a valid number");
    return type === "integer" ? Math.trunc(num) : num;
  }
  if (type === "array" || type === "object") {
    if (!raw.trim()) return type === "array" ? [] : {};
    try {
      return JSON.parse(raw);
    } catch {
      throw new Error("must be valid JSON");
    }
  }
  return raw;
}

function parseMetadata(raw: any): Record<string, any> {
  if (!raw) return {};
  if (typeof raw === "object") return raw as Record<string, any>;
  if (typeof raw !== "string") return {};
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? (parsed as Record<string, any>) : {};
  } catch {
    return {};
  }
}

export default function ToolChainRunInputDialog({
  open,
  onOpenChange,
  toolChainId,
  triggerSource,
  initialVersion,
  initialInput,
  onExecuted,
}: Props) {
  const { data: versions = [] } = useQuery<any[]>({
    queryKey: ["toolchain-versions", toolChainId, "run-dialog"],
    queryFn: () => api.toolchains.versions(toolChainId),
    enabled: open && !!toolChainId,
  });
  const { data: allToolChains = [] } = useQuery<any[]>({
    queryKey: ["toolchains"],
    queryFn: () => api.toolchains.list(),
    enabled: open,
  });

  const [selectedVersion, setSelectedVersion] = useState<string>("");
  const [versionChoice, setVersionChoice] = useState<"published" | "specific">("published");
  const [formValues, setFormValues] = useState<Record<string, any>>({});
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const selectedVersionObj = useMemo(
    () => versions.find((v: any) => String(v.version) === selectedVersion),
    [versions, selectedVersion]
  );

  const schema = useMemo(() => parseSchema(selectedVersionObj?.inputSchema), [selectedVersionObj]);
  const schemaProperties = schema.properties || {};
  const requiredFields = new Set(schema.required || []);
  const toolChainModelPrefs = useMemo(() => {
    if (!toolChainId) return { runtime: "", default: "" };
    const currentToolChain = allToolChains.find((row: any) => row.id === toolChainId);
    const metadata = parseMetadata(currentToolChain?.metadataJson);
    const toKey = (modelRef: any) => {
      if (!modelRef || typeof modelRef !== "object") return "";
      const providerID = String((modelRef as any).providerID || "").trim();
      const modelID = String((modelRef as any).modelID || "").trim();
      if (!providerID || !modelID) return "";
      return `${providerID}/${modelID}`;
    };
    return {
      runtime: toKey(metadata?.runtimeModelRef),
      default: toKey(metadata?.defaultModelRef),
    };
  }, [allToolChains, toolChainId]);

  useEffect(() => {
    if (!open) return;
    if (!versions.length) return;
    const preferred =
      (initialVersion !== undefined && versions.find((v: any) => v.version === initialVersion)) ||
      versions.find((v: any) => v.published) ||
      versions[0];
    const nextVersion = String(preferred?.version ?? "");
    setSelectedVersion(nextVersion);
    setVersionChoice(versions.some((v: any) => v.published) ? "published" : "specific");
  }, [open, versions, initialVersion]);

  useEffect(() => {
    if (!open || !selectedVersionObj) return;
    const prev = formValues;
    const next: Record<string, any> = {};
    Object.entries(schemaProperties).forEach(([key, prop]) => {
      if (initialInput && key in initialInput) {
        next[key] = initialInput[key];
        return;
      }
      if (key in prev) {
        next[key] = prev[key];
        return;
      }
      if (prop.default !== undefined) {
        next[key] = prop.default;
        return;
      }
      if (prop.type === "boolean") {
        next[key] = false;
      } else if (prop.type === "number" || prop.type === "integer") {
        next[key] = "";
      } else if (prop.type === "array") {
        next[key] = "[]";
      } else if (prop.type === "object") {
        next[key] = "{}";
      } else {
        next[key] = "";
      }
    });
    setFormValues(next);
    setFieldErrors({});
  }, [open, selectedVersionObj, schemaProperties, initialInput]); // keep overlapping values when schema changes

  const executeMutation = useMutation({
    mutationFn: async () => {
      if (!selectedVersionObj) throw new Error("Select a ToolChain version");
      const payloadInput: Record<string, any> = {};
      const nextErrors: Record<string, string> = {};
      for (const [key, prop] of Object.entries(schemaProperties)) {
        const val = formValues[key];
        if (prop.type === "boolean") {
          payloadInput[key] = Boolean(val);
          continue;
        }
        const raw = val == null ? "" : String(val);
        if (requiredFields.has(key) && !raw.trim()) {
          nextErrors[key] = "Required";
          continue;
        }
        if (!raw.trim() && !requiredFields.has(key)) continue;
        try {
          payloadInput[key] = convertInputValue(raw, prop.type);
        } catch (e: any) {
          nextErrors[key] = e.message || "Invalid value";
        }
      }
      if (Object.keys(nextErrors).length > 0) {
        setFieldErrors(nextErrors);
        throw new Error("Please fix input errors");
      }
      setFieldErrors({});
      const localModelKey = (typeof window !== "undefined" ? window.localStorage.getItem("lastModelId") : null) || "";
      const chosenModelKey = toolChainModelPrefs.runtime || toolChainModelPrefs.default || localModelKey;
      const modelRef = chosenModelKey ? parseModelRefKey(chosenModelKey) : null;
      const options: Record<string, any> = { async: true };
      if (modelRef) options.model = modelRef;
      const publishedVersion = versions.find((v: any) => v.published);
      const versionToRun =
        versionChoice === "published"
          ? publishedVersion?.version ?? selectedVersionObj.version
          : selectedVersionObj.version;
      return api.toolchains.execute(toolChainId, {
        version: versionToRun,
        input: payloadInput,
        triggerSource,
        options,
      });
    },
    onSuccess: (result) => {
      toast.success("Execution started");
      onExecuted?.(result);
      onOpenChange(false);
    },
    onError: (e: any) => {
      toast.error(e.message || "Failed to execute ToolChain");
    },
  });

  const hasSchemaFields = Object.keys(schemaProperties).length > 0;
  const publishedVersion = versions.find((v: any) => v.published);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Run ToolChain</DialogTitle>
          <DialogDescription>Select a version and provide inputs before execution.</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-1">
            <p className="text-xs font-medium text-slate-600">Version Source</p>
            <Select value={versionChoice} onValueChange={(value) => setVersionChoice(value as "published" | "specific")}>
              <SelectTrigger className="w-full">
                <SelectValue placeholder="Choose run version source" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="published" disabled={!publishedVersion}>
                  Published Version {publishedVersion ? `(v${publishedVersion.version})` : "(none)"}
                </SelectItem>
                <SelectItem value="specific">Choose Specific Version</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-1">
            <p className="text-xs font-medium text-slate-600">Version</p>
            <Select value={selectedVersion} onValueChange={setSelectedVersion}>
              <SelectTrigger className="w-full">
                <SelectValue placeholder="Select version" />
              </SelectTrigger>
              <SelectContent>
                {versions.map((v: any) => (
                  <SelectItem key={v.id} value={String(v.version)}>
                    v{v.version} {v.published ? "(published)" : ""}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {versionChoice === "published" ? (
              <p className="text-xs text-slate-500">Run uses currently published version.</p>
            ) : null}
          </div>
          {hasSchemaFields ? (
            <div className="space-y-3">
              {Object.entries(schemaProperties).map(([key, prop]) => {
                const required = requiredFields.has(key);
                const helper = prop.description || `${prop.type || "string"} input`;
                const value = formValues[key];
                return (
                  <div key={key} className="space-y-1">
                    <p className="text-sm font-medium text-slate-700">
                      {key} {required ? <span className="text-red-500">*</span> : null}
                    </p>
                    {prop.type === "boolean" ? (
                      <label className="flex items-center gap-2 text-sm text-slate-700">
                        <Checkbox checked={Boolean(value)} onCheckedChange={(checked) => setFormValues((prev) => ({ ...prev, [key]: Boolean(checked) }))} />
                        Enable
                      </label>
                    ) : (
                      <Input
                        value={value ?? ""}
                        onChange={(e) => setFormValues((prev) => ({ ...prev, [key]: e.target.value }))}
                        placeholder={prop.type === "array" ? "[]" : prop.type === "object" ? "{}" : key}
                      />
                    )}
                    <p className="text-xs text-slate-500">{helper}</p>
                    {fieldErrors[key] ? <p className="text-xs text-red-500">{fieldErrors[key]}</p> : null}
                  </div>
                );
              })}
            </div>
          ) : (
            <p className="rounded border bg-slate-50 p-3 text-sm text-slate-600">
              No input parameters defined in this version schema. Run will proceed with an empty input object.
            </p>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button onClick={() => executeMutation.mutate()} disabled={!selectedVersion || executeMutation.isPending}>
            {executeMutation.isPending ? "Starting..." : "Run"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
