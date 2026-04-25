import { useState, useRef } from "react";
import { Upload, Loader2 } from "lucide-react";
import { api } from "@/services/api";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

interface UploadSkillDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSuccess: (newSkillId: string) => void;
}

export function UploadSkillDialog({
  open,
  onOpenChange,
  onSuccess,
}: UploadSkillDialogProps) {
  const [isDragging, setIsDragging] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [skillName, setSkillName] = useState("");
  const [pendingFile, setPendingFile] = useState<File | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const processFile = async (file: File, name: string) => {
    setIsUploading(true);
    setError(null);
    try {
      const skill = await api.upload("/skills/upload", file, name.trim() || undefined);
      onSuccess(skill.id);
      setPendingFile(null);
      setSkillName("");
    } catch (e: any) {
      setError(e.message || "Upload failed");
    } finally {
      setIsUploading(false);
    }
  };

  const handleFilePicked = (file: File) => {
    // Pre-fill name from filename (user can override)
    const guessed = file.name.replace(/\.(md|zip|skill)$/i, "").replace(/[-_]/g, " ");
    setSkillName((prev) => prev || guessed);
    setPendingFile(file);
    setError(null);
  };

  const handleUpload = () => {
    if (!pendingFile) return;
    processFile(pendingFile, skillName);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    const file = e.dataTransfer.files[0];
    if (file) handleFilePicked(file);
  };

  const handleOpenChange = (next: boolean) => {
    if (!isUploading) {
      setError(null);
      setPendingFile(null);
      setSkillName("");
      onOpenChange(next);
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Upload skill</DialogTitle>
          <DialogDescription>
            Add a skill from a file to your collection.
          </DialogDescription>
        </DialogHeader>

        {/* Skill name input */}
        <div className="space-y-1.5">
          <label className="text-xs font-semibold text-slate-600">
            Skill name
          </label>
          <Input
            value={skillName}
            onChange={(e) => setSkillName(e.target.value)}
            placeholder="e.g. brand-guidelines"
            className="h-8 text-sm bg-white border-slate-300 focus:border-[#123262]"
            disabled={isUploading}
          />
          <p className="text-[11px] text-slate-400">
            Overrides the name in SKILL.md if provided
          </p>
        </div>

        {/* Drop zone */}
        <div
          onDragOver={(e) => { e.preventDefault(); setIsDragging(true); }}
          onDragLeave={() => setIsDragging(false)}
          onDrop={handleDrop}
          onClick={() => !isUploading && fileInputRef.current?.click()}
          className={cn(
            "rounded-lg p-7 text-center transition-colors border-2 border-dashed",
            isUploading
              ? "cursor-not-allowed bg-slate-50 border-slate-200"
              : pendingFile
              ? "cursor-pointer bg-emerald-50 border-emerald-300"
              : isDragging
              ? "cursor-copy bg-blue-50 border-blue-400"
              : "cursor-pointer bg-slate-50 border-slate-200 hover:border-[#123262] hover:bg-blue-50/30"
          )}
        >
          {isUploading ? (
            <Loader2 className="mx-auto mb-2 animate-spin text-slate-400" size={26} />
          ) : (
            <Upload
              className={cn(
                "mx-auto mb-2 transition-colors",
                pendingFile ? "text-emerald-500" : isDragging ? "text-blue-400" : "text-slate-400"
              )}
              size={26}
            />
          )}
          <p className="text-sm font-medium text-slate-700">
            {isUploading
              ? "Uploading…"
              : pendingFile
              ? pendingFile.name
              : "Drag and drop or click to browse"}
          </p>
          <p className="mt-0.5 text-xs text-slate-400">
            {pendingFile
              ? `${(pendingFile.size / 1024).toFixed(1)} KB · click to change`
              : "Supports .md, .zip, .skill files"}
          </p>
          <input
            ref={fileInputRef}
            type="file"
            accept=".md,.zip,.skill"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) handleFilePicked(file);
              e.target.value = "";
            }}
          />
        </div>

        {/* Requirements */}
        <div className="text-xs text-slate-500 space-y-1">
          <p className="font-semibold text-slate-600">File requirements</p>
          <ul className="space-y-0.5 list-disc list-inside">
            <li>
              <code className="bg-slate-100 text-[#123262] rounded px-1 py-0.5 text-[11px]">.md</code>{" "}
              file must contain skill name and description formatted in YAML
            </li>
            <li>
              <code className="bg-slate-100 text-[#123262] rounded px-1 py-0.5 text-[11px]">.zip</code>{" "}
              or{" "}
              <code className="bg-slate-100 text-[#123262] rounded px-1 py-0.5 text-[11px]">.skill</code>{" "}
              archive — SKILL.md is optional if a name is provided above
            </li>
          </ul>
        </div>

        {/* Error */}
        {error && (
          <div className="rounded-lg px-3 py-2 text-xs bg-red-50 border border-red-200 text-red-600">
            {error}
          </div>
        )}

        {/* Upload button */}
        <button
          onClick={handleUpload}
          disabled={!pendingFile || isUploading}
          className={cn(
            "w-full h-9 rounded-lg text-sm font-medium transition-colors",
            pendingFile && !isUploading
              ? "bg-[#123262] hover:bg-[#0e2550] text-white"
              : "bg-slate-100 text-slate-400 cursor-not-allowed"
          )}
        >
          {isUploading ? (
            <span className="flex items-center justify-center gap-2">
              <Loader2 size={14} className="animate-spin" /> Uploading…
            </span>
          ) : (
            "Upload skill"
          )}
        </button>
      </DialogContent>
    </Dialog>
  );
}
