import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ChevronRight,
  Plus,
  Upload,
  MoreHorizontal,
  FileText,
  Folder,
  FolderOpen,
  Eye,
  Code2,
  Trash2,
  Loader2,
} from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { api } from "@/services/api";
import type { Skill, SkillFile } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import { UploadSkillDialog } from "@/components/UploadSkillDialog";

// ── File tree helpers ─────────────────────────────────────────────────────────

interface TreeNode {
  name: string;
  fullPath: string;
  type: "file" | "folder";
  file?: SkillFile;
  children?: TreeNode[];
}

function buildFileTree(files: SkillFile[]): TreeNode[] {
  // Internal mutable structure
  const root: Record<string, any> = {};

  for (const file of files) {
    const parts = file.filePath.split("/");
    let node = root;
    for (let i = 0; i < parts.length - 1; i++) {
      if (!node[parts[i]]) node[parts[i]] = { __folder: true, __children: {} };
      node = node[parts[i]].__children;
    }
    const leaf = parts[parts.length - 1];
    node[leaf] = { __folder: false, __file: file };
  }

  function toNodes(obj: Record<string, any>, prefix: string): TreeNode[] {
    return Object.entries(obj)
      .sort(([aKey, aVal], [bKey, bVal]) => {
        // folders first, then alphabetical
        const af = aVal.__folder, bf = bVal.__folder;
        if (af !== bf) return af ? -1 : 1;
        return aKey.localeCompare(bKey);
      })
      .map(([name, val]) => {
        const fullPath = prefix ? `${prefix}/${name}` : name;
        if (val.__folder) {
          return {
            name,
            fullPath,
            type: "folder" as const,
            children: toNodes(val.__children, fullPath),
          };
        }
        return {
          name,
          fullPath,
          type: "file" as const,
          file: val.__file as SkillFile,
        };
      });
  }

  return toNodes(root, "");
}

// ── Recursive tree renderer ───────────────────────────────────────────────────

interface FileTreeProps {
  nodes: TreeNode[];
  depth: number;
  expandedFolders: Set<string>;
  onToggleFolder: (path: string) => void;
  selectedFileId: string | null;
  onFileClick: (file: SkillFile) => void;
}

function FileTree({
  nodes,
  depth,
  expandedFolders,
  onToggleFolder,
  selectedFileId,
  onFileClick,
}: FileTreeProps) {
  return (
    <>
      {nodes.map((node) => {
        if (node.type === "folder") {
          const isOpen = expandedFolders.has(node.fullPath);
          return (
            <div key={node.fullPath}>
              <div
                className="flex items-center gap-1.5 py-1 cursor-pointer select-none hover:bg-slate-100 transition-colors rounded"
                style={{ paddingLeft: `${8 + depth * 12}px`, paddingRight: 8 }}
                onClick={() => onToggleFolder(node.fullPath)}
              >
                <ChevronRight
                  size={12}
                  className="flex-shrink-0 text-slate-400 transition-transform duration-150"
                  style={{ transform: isOpen ? "rotate(90deg)" : "rotate(0deg)" }}
                />
                {isOpen ? (
                  <FolderOpen size={13} className="flex-shrink-0 text-amber-400" />
                ) : (
                  <Folder size={13} className="flex-shrink-0 text-amber-400" />
                )}
                <span className="text-xs text-slate-600 font-medium truncate" title={node.fullPath}>
                  {node.name}
                </span>
              </div>
              {isOpen && node.children && (
                <FileTree
                  nodes={node.children}
                  depth={depth + 1}
                  expandedFolders={expandedFolders}
                  onToggleFolder={onToggleFolder}
                  selectedFileId={selectedFileId}
                  onFileClick={onFileClick}
                />
              )}
            </div>
          );
        }

        // file node
        const isSelected = selectedFileId === node.file?.id;
        return (
          <div
            key={node.fullPath}
            className={cn(
              "flex items-center gap-1.5 py-1 cursor-pointer select-none transition-colors rounded",
              isSelected
                ? "bg-blue-50 text-[#123262] font-medium"
                : "text-slate-500 hover:bg-slate-100 hover:text-slate-700"
            )}
            style={{ paddingLeft: `${8 + depth * 12}px`, paddingRight: 8 }}
            onClick={() => node.file && onFileClick(node.file)}
          >
            <FileText
              size={12}
              className={cn("flex-shrink-0", isSelected ? "text-[#123262]" : "text-slate-400")}
            />
            <span className="text-xs truncate" title={node.fullPath}>{node.name}</span>
          </div>
        );
      })}
    </>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function SkillsPage() {
  const qc = useQueryClient();

  const [selectedSkillId, setSelectedSkillId] = useState<string | null>(null);
  const [selectedFileId, setSelectedFileId] = useState<string | null>(null);
  const [expandedSkills, setExpandedSkills] = useState<Set<string>>(new Set());
  // expanded folders per skill:  skillId → Set<folderPath>
  const [expandedFoldersBySkill, setExpandedFoldersBySkill] = useState<
    Record<string, Set<string>>
  >({});

  const [viewMode, setViewMode] = useState<"raw" | "preview">("raw");
  const [fileContents, setFileContents] = useState<Record<string, string>>({});
  const [dirtyFiles, setDirtyFiles] = useState<Set<string>>(new Set());

  const [addingFile, setAddingFile] = useState(false);
  const [newFilePath, setNewFilePath] = useState("");
  const [uploadOpen, setUploadOpen] = useState(false);

  // ── queries ────────────────────────────────────────────────────────────────
  const { data: skills = [] } = useQuery<Skill[]>({
    queryKey: ["skills"],
    queryFn: () => api.get("/skills"),
  });

  const { data: files = [], isFetching: filesFetching } = useQuery<SkillFile[]>({
    queryKey: ["skill-files", selectedSkillId],
    queryFn: () => api.get(`/skills/${selectedSkillId}/files`),
    enabled: !!selectedSkillId,
  });

  const selectedSkill = skills.find((s) => s.id === selectedSkillId) ?? null;
  const selectedFile = files.find((f) => f.id === selectedFileId) ?? null;

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["skills"] });
    if (selectedSkillId)
      qc.invalidateQueries({ queryKey: ["skill-files", selectedSkillId] });
  };

  // ── mutations ──────────────────────────────────────────────────────────────
  const saveFileMutation = useMutation({
    mutationFn: (vars: { skillId: string; filePath: string; content: string; fileId: string }) =>
      api.post(`/skills/${vars.skillId}/files`, {
        filePath: vars.filePath,
        mimeType: "text/markdown",
        content: vars.content,
      }),
    onSuccess: (_: any, vars: any) => {
      setDirtyFiles((prev) => { const n = new Set(prev); n.delete(vars.fileId); return n; });
      invalidate();
      toast.success("File saved");
    },
    onError: (e: any) => toast.error(e.message || "Failed to save file"),
  });

  const createFileMutation = useMutation({
    mutationFn: (vars: { skillId: string; filePath: string }) =>
      api.post(`/skills/${vars.skillId}/files`, {
        filePath: vars.filePath.trim(),
        mimeType: "text/markdown",
        content: "",
      }),
    onSuccess: (file: SkillFile) => {
      invalidate();
      setAddingFile(false);
      setNewFilePath("");
      setSelectedFileId(file.id);
      toast.success("File created");
    },
    onError: (e: any) => toast.error(e.message || "Failed to create file"),
  });

  const deleteSkillMutation = useMutation({
    mutationFn: (id: string) => api.delete(`/skills/${id}`),
    onSuccess: (_: any, id: string) => {
      qc.invalidateQueries({ queryKey: ["skills"] });
      if (selectedSkillId === id) { setSelectedSkillId(null); setSelectedFileId(null); }
      toast.success("Skill deleted");
    },
    onError: (e: any) => toast.error(e.message || "Failed to delete skill"),
  });

  // ── handlers ───────────────────────────────────────────────────────────────
  const toggleSkillExpand = (skillId: string) =>
    setExpandedSkills((prev) => {
      const n = new Set(prev);
      n.has(skillId) ? n.delete(skillId) : n.add(skillId);
      return n;
    });

  const handleSelectSkill = (skillId: string) => {
    setSelectedSkillId(skillId);
    setSelectedFileId(null);
    if (!expandedSkills.has(skillId)) toggleSkillExpand(skillId);
  };

  const handleToggleFolder = (skillId: string, folderPath: string) => {
    setExpandedFoldersBySkill((prev) => {
      const skillFolders = new Set(prev[skillId] ?? []);
      skillFolders.has(folderPath)
        ? skillFolders.delete(folderPath)
        : skillFolders.add(folderPath);
      return { ...prev, [skillId]: skillFolders };
    });
  };

  const handleFileClick = async (skillId: string, file: SkillFile) => {
    setSelectedSkillId(skillId);
    setSelectedFileId(file.id);
    if (fileContents[file.id] !== undefined) return;
    try {
      const res = await api.get(`/skills/${skillId}/files/${file.id}`);
      setFileContents((prev) => ({ ...prev, [file.id]: res?.content ?? "" }));
    } catch (e: any) {
      toast.error(e.message || "Failed to load file");
    }
  };

  const handleContentChange = (fileId: string, value: string) => {
    setFileContents((prev) => ({ ...prev, [fileId]: value }));
    setDirtyFiles((prev) => new Set([...prev, fileId]));
  };

  const handleSaveFile = () => {
    if (!selectedSkillId || !selectedFile) return;
    const normalized = selectedFile.filePath.trim().replaceAll("\\", "/");
    if (!normalized || normalized.includes("..")) { toast.error("Invalid file path"); return; }
    saveFileMutation.mutate({
      skillId: selectedSkillId,
      filePath: normalized,
      content: fileContents[selectedFile.id] ?? "",
      fileId: selectedFile.id,
    });
  };

  const toggleSkill = async (skill: Skill) => {
    try {
      await api.post(`/skills/${skill.id}/${skill.enabled ? "disable" : "enable"}`);
      invalidate();
    } catch (e: any) {
      toast.error(e.message || "Failed to toggle skill");
    }
  };

  const handleAddFile = () => {
    const path = newFilePath.trim();
    if (!path || path.includes("..")) { toast.error("Invalid file path"); return; }
    if (!selectedSkillId) return;
    createFileMutation.mutate({ skillId: selectedSkillId, filePath: path });
  };

  // ── render ─────────────────────────────────────────────────────────────────
  return (
    <div className="-m-6 flex overflow-hidden" style={{ height: "calc(100vh - 64px)" }}>

      {/* ── LEFT PANEL ─────────────────────────────────────────────────────── */}
      <div className="w-[260px] flex-shrink-0 flex flex-col bg-slate-50 border-r border-slate-200">

        {/* Header */}
        <div className="flex items-center justify-between px-3 h-12 border-b border-slate-200 flex-shrink-0">
          <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
            Personal skills
          </span>
          <button
            className="p-1.5 rounded text-slate-400 hover:text-[#123262] hover:bg-slate-200 transition-colors"
            title="Upload skill"
            onClick={() => setUploadOpen(true)}
          >
            <Upload size={13} />
          </button>
        </div>

        {/* Skill list */}
        <ScrollArea className="flex-1">
          <div className="py-1 px-1">
            {skills.map((skill) => {
              const isSelected = selectedSkillId === skill.id;
              const isExpanded = expandedSkills.has(skill.id);
              const treeNodes = isSelected ? buildFileTree(files) : [];
              const expandedFolders = expandedFoldersBySkill[skill.id] ?? new Set<string>();

              return (
                <div key={skill.id} className="mb-0.5">
                  {/* Skill row */}
                  <div
                    className={cn(
                      "flex items-center gap-1.5 px-2 py-1.5 cursor-pointer select-none transition-colors rounded",
                      isSelected ? "bg-blue-50" : "hover:bg-slate-100"
                    )}
                    onClick={() => handleSelectSkill(skill.id)}
                  >
                    <button
                      className="flex-shrink-0 text-slate-400 hover:text-slate-600 transition-colors"
                      onClick={(e) => { e.stopPropagation(); toggleSkillExpand(skill.id); }}
                    title={isExpanded ? "Collapse skill files" : "Expand skill files"}
                    aria-label={isExpanded ? "Collapse skill files" : "Expand skill files"}
                    >
                      <ChevronRight
                        size={13}
                        className="transition-transform duration-150"
                        style={{ transform: isExpanded ? "rotate(90deg)" : "rotate(0deg)" }}
                      />
                    </button>
                    <span className={cn(
                      "text-sm truncate flex-1 font-medium",
                      isSelected ? "text-[#123262]" : "text-slate-700"
                    )} title={skill.name}>
                      {skill.name}
                    </span>
                    <span
                      className={cn(
                        "w-1.5 h-1.5 rounded-full flex-shrink-0",
                        skill.enabled ? "bg-emerald-400" : "bg-slate-300"
                      )}
                      title={skill.enabled ? "Enabled" : "Disabled"}
                    />
                  </div>

                  {/* File tree */}
                  {isExpanded && (
                    <div className="ml-2 mt-0.5">
                      {filesFetching && isSelected && files.length === 0 ? (
                        <div className="flex items-center gap-2 pl-4 py-1.5 text-xs text-slate-400">
                          <Loader2 size={11} className="animate-spin" /> Loading…
                        </div>
                      ) : (
                        <FileTree
                          nodes={treeNodes}
                          depth={0}
                          expandedFolders={expandedFolders}
                          onToggleFolder={(path) => handleToggleFolder(skill.id, path)}
                          selectedFileId={selectedFileId}
                          onFileClick={(file) => handleFileClick(skill.id, file)}
                        />
                      )}
                    </div>
                  )}
                </div>
              );
            })}

            {skills.length === 0 && (
              <div className="px-3 py-8 text-center space-y-2">
                <Upload size={20} className="mx-auto text-slate-300" />
                <p className="text-xs text-slate-400">No skills yet</p>
                <button
                  className="text-xs text-[#123262] hover:underline font-medium"
                  onClick={() => setUploadOpen(true)}
                >
                  Upload your first skill
                </button>
              </div>
            )}
          </div>
        </ScrollArea>
      </div>

      {/* ── RIGHT PANEL ────────────────────────────────────────────────────── */}
      {selectedSkill ? (
        <div className="flex-1 flex flex-col min-w-0 bg-white">

          {/* Header */}
          <div className="flex items-center justify-between px-5 h-12 border-b border-slate-200 flex-shrink-0">
            <span className="text-sm font-semibold text-[#123262] truncate">
              {selectedSkill.name}
            </span>
            <div className="flex items-center gap-2.5">
              <button
                type="button"
                role="switch"
                aria-checked={selectedSkill.enabled}
                onClick={() => toggleSkill(selectedSkill)}
                className="relative inline-flex flex-shrink-0 rounded-full border-2 border-transparent transition-colors duration-200 focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-blue-400"
                style={{ width: 36, height: 20, background: selectedSkill.enabled ? "#3b82f6" : "#cbd5e1" }}
                title={selectedSkill.enabled ? "Disable skill" : "Enable skill"}
              >
                <span
                  className="inline-block rounded-full bg-white shadow-sm transition-transform duration-200"
                  style={{ width: 16, height: 16, transform: selectedSkill.enabled ? "translateX(16px)" : "translateX(0px)" }}
                />
              </button>
              <DropdownMenu>
                <DropdownMenuTrigger>
                  <button className="p-1 rounded text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors" title="Skill actions" aria-label="Skill actions">
                    <MoreHorizontal size={16} />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent side="bottom" align="end">
                  <DropdownMenuItem
                    className="gap-2 text-red-600 focus:text-red-600 cursor-pointer"
                    onClick={() => deleteSkillMutation.mutate(selectedSkill.id)}
                  >
                    <Trash2 size={13} /> Delete skill
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          </div>

          {/* Metadata chips */}
          <div className="flex items-center gap-4 px-5 py-3 border-b border-slate-100 flex-shrink-0 bg-slate-50/50">
            {[
              { label: "Added by", value: "System" },
              { label: "Trigger", value: "Manual" },
            ].map(({ label, value }) => (
              <div key={label}>
                <p className="text-[10px] uppercase tracking-wider font-semibold text-slate-400 mb-1">{label}</p>
                <span className="text-xs px-2 py-0.5 rounded-full bg-slate-100 text-slate-600 font-medium border border-slate-200">
                  {value}
                </span>
              </div>
            ))}
            <div>
              <p className="text-[10px] uppercase tracking-wider font-semibold text-slate-400 mb-1">Status</p>
              <span className={cn(
                "text-xs px-2 py-0.5 rounded-full font-medium border",
                selectedSkill.enabled
                  ? "bg-emerald-50 text-emerald-700 border-emerald-200"
                  : "bg-slate-100 text-slate-500 border-slate-200"
              )}>
                {selectedSkill.enabled ? "Enabled" : "Disabled"}
              </span>
            </div>
          </div>

          {/* File content area */}
          <div className="flex-1 flex flex-col min-h-0">
            {selectedFile ? (
              <>
                {/* Toolbar */}
                <div className="flex items-center justify-between px-5 py-2 border-b border-slate-100 bg-slate-50/70 flex-shrink-0">
                  <span className="text-xs font-mono text-slate-500">
                    <span title={selectedFile.filePath}>{selectedFile.filePath}</span>
                    {dirtyFiles.has(selectedFile.id) && (
                      <span className="ml-2 text-[10px] text-amber-500 font-sans">● unsaved</span>
                    )}
                  </span>
                  <div className="flex items-center gap-0.5 bg-slate-100 rounded-md p-0.5">
                    {(["preview", "raw"] as const).map((mode) => (
                      <button
                        key={mode}
                        className={cn(
                          "px-2 py-0.5 rounded text-xs font-medium transition-colors capitalize",
                          viewMode === mode ? "bg-white text-[#123262] shadow-sm" : "text-slate-500 hover:text-slate-700"
                        )}
                        onClick={() => setViewMode(mode)}
                        title={mode === "preview" ? "Render markdown preview" : "Edit raw markdown"}
                      >
                        {mode === "preview" ? <><Eye size={12} className="inline mr-1" />Preview</> : <><Code2 size={12} className="inline mr-1" />Raw</>}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Content */}
                <div className="flex-1 overflow-hidden">
                  {viewMode === "raw" ? (
                    <textarea
                      className="w-full h-full resize-none outline-none font-mono text-xs p-4 text-slate-700 bg-white placeholder:text-slate-300"
                      value={fileContents[selectedFile.id] ?? ""}
                      onChange={(e) => handleContentChange(selectedFile.id, e.target.value)}
                      placeholder="# Skill instructions..."
                      spellCheck={false}
                    />
                  ) : (
                    <ScrollArea className="h-full">
                      <div className="p-5 prose prose-slate prose-sm max-w-none">
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                          {fileContents[selectedFile.id] ?? ""}
                        </ReactMarkdown>
                      </div>
                    </ScrollArea>
                  )}
                </div>

                {/* Save bar */}
                <div className="flex items-center justify-end px-5 py-2.5 border-t border-slate-200 bg-slate-50/50 flex-shrink-0">
                  <Button
                    size="sm"
                    className="text-xs h-7 px-4 bg-[#123262] hover:bg-[#0e2550] text-white"
                    onClick={handleSaveFile}
                    disabled={saveFileMutation.isPending}
                  >
                    {saveFileMutation.isPending && <Loader2 size={12} className="animate-spin mr-1" />}
                    Save
                  </Button>
                </div>
              </>
            ) : (
              <div className="flex-1 flex items-center justify-center">
                <div className="text-center space-y-1">
                  <FileText size={24} className="mx-auto text-slate-300" />
                  <p className="text-sm text-slate-400">Select a file to view</p>
                </div>
              </div>
            )}
          </div>

          {/* New file bar */}
          <div className="px-5 py-2.5 border-t border-slate-200 flex-shrink-0 bg-slate-50/50">
            {addingFile ? (
              <div className="flex items-center gap-2">
                <Input
                  value={newFilePath}
                  onChange={(e) => setNewFilePath(e.target.value)}
                  placeholder="path/to/file.md"
                  className="h-7 text-xs flex-1 bg-white border-slate-300 focus:border-[#123262]"
                  autoFocus
                  onKeyDown={(e) => {
                    if (e.key === "Enter") handleAddFile();
                    if (e.key === "Escape") { setAddingFile(false); setNewFilePath(""); }
                  }}
                />
                <Button
                  size="sm"
                  className="h-7 text-xs px-3 bg-[#123262] hover:bg-[#0e2550] text-white"
                  onClick={handleAddFile}
                  disabled={createFileMutation.isPending}
                >
                  Create
                </Button>
                <button
                  className="text-xs text-slate-400 hover:text-slate-600 px-1"
                  onClick={() => { setAddingFile(false); setNewFilePath(""); }}
                >
                  Cancel
                </button>
              </div>
            ) : (
              <button
                className="flex items-center gap-1.5 text-xs text-slate-400 hover:text-[#123262] transition-colors"
                onClick={() => setAddingFile(true)}
              >
                <Plus size={12} /> New file
              </button>
            )}
          </div>
        </div>
      ) : (
        <div className="flex-1 flex items-center justify-center bg-slate-50/50">
          <div className="text-center space-y-3">
            <div className="w-14 h-14 rounded-full bg-slate-100 flex items-center justify-center mx-auto">
              <Upload size={22} className="text-slate-400" />
            </div>
            <div>
              <p className="text-sm text-slate-600 font-medium">No skill selected</p>
              <p className="text-xs text-slate-400 mt-0.5">Upload a skill to get started</p>
            </div>
            <button
              onClick={() => setUploadOpen(true)}
              className="mx-auto flex items-center gap-1.5 text-xs font-medium text-white bg-[#123262] hover:bg-[#0e2550] px-3 py-1.5 rounded-lg transition-colors"
            >
              <Upload size={12} /> Upload skill
            </button>
          </div>
        </div>
      )}

      <UploadSkillDialog
        open={uploadOpen}
        onOpenChange={setUploadOpen}
        onSuccess={(newSkillId) => {
          qc.invalidateQueries({ queryKey: ["skills"] });
          setSelectedSkillId(newSkillId);
          setExpandedSkills((prev) => new Set([...prev, newSkillId]));
          setUploadOpen(false);
          toast.success("Skill uploaded");
        }}
      />
    </div>
  );
}
