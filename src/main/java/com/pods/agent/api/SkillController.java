package com.pods.agent.api;

import com.pods.agent.api.dto.SkillFileRequest;
import com.pods.agent.api.dto.SkillRequest;
import com.pods.agent.domain.Skill;
import com.pods.agent.domain.SkillFile;
import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.repository.SkillRepository;
import com.pods.agent.service.SkillFileStorageService;
import com.pods.agent.service.SkillRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/api/v1/skills")
@Tag(name = "Skills", description = "Skill CRUD and file management")
public class SkillController {
    private final SkillRepository skillRepository;
    private final SkillFileStorageService storageService;
    private final SkillRegistryService skillRegistryService;

    public SkillController(SkillRepository skillRepository,
                           SkillFileStorageService storageService,
                           SkillRegistryService skillRegistryService) {
        this.skillRepository = skillRepository;
        this.storageService = storageService;
        this.skillRegistryService = skillRegistryService;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List skills")
    public ResponseEntity<?> listSkills() {
        return ResponseEntity.ok(skillRepository.findAll());
    }

    @GetMapping("/registry")
    @Operation(summary = "List all skills currently active in the registry (DB + built-in defaults)")
    public ResponseEntity<?> listRegistry() {
        var all = skillRegistryService.getEnabledSkills().stream().map(s -> Map.of(
                "id", s.skill().getId(),
                "name", s.skill().getName(),
                "description", s.skill().getDescription() != null ? s.skill().getDescription() : "",
                "isDefault", s.skill().getId().startsWith("system-"),
                "files", s.files().keySet()
        )).toList();
        return ResponseEntity.ok(all);
    }

    @PostMapping
    @Operation(summary = "Create skill")
    public ResponseEntity<?> createSkill(@Valid @RequestBody SkillRequest request) {
        Skill saved = createSkillWithDefaultFile(request.getName(), request.getDescription(),
                Boolean.TRUE.equals(request.getEnabled()));
        skillRegistryService.refresh();
        return ResponseEntity.ok(saved);
    }

    @PatchMapping("/{skillId}")
    @Operation(summary = "Update skill")
    public ResponseEntity<?> updateSkill(@PathVariable String skillId, @Valid @RequestBody SkillRequest request) {
        Skill skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) return ResponseEntityFactory.notFound("Skill not found: " + skillId);
        skill.setName(request.getName().trim());
        skill.setDescription(request.getDescription());
        skill.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        skillRepository.update(skill);
        skillRegistryService.refresh();
        return ResponseEntity.ok(skill);
    }

    @PostMapping("/{skillId}/enable")
    @Operation(summary = "Enable skill")
    public ResponseEntity<?> enableSkill(@PathVariable String skillId) {
        if (skillRepository.findById(skillId).isEmpty())
            return ResponseEntityFactory.notFound("Skill not found: " + skillId);
        skillRepository.setEnabled(skillId, true);
        skillRegistryService.refresh();
        return ResponseEntity.ok(Map.of("enabled", true, "skillId", skillId));
    }

    @PostMapping("/{skillId}/disable")
    @Operation(summary = "Disable skill")
    public ResponseEntity<?> disableSkill(@PathVariable String skillId) {
        if (skillRepository.findById(skillId).isEmpty())
            return ResponseEntityFactory.notFound("Skill not found: " + skillId);
        skillRepository.setEnabled(skillId, false);
        skillRegistryService.refresh();
        return ResponseEntity.ok(Map.of("enabled", false, "skillId", skillId));
    }

    @DeleteMapping("/{skillId}")
    @Operation(summary = "Delete skill")
    public ResponseEntity<?> deleteSkill(@PathVariable String skillId) {
        if (skillRepository.findById(skillId).isEmpty())
            return ResponseEntityFactory.notFound("Skill not found: " + skillId);
        // Delete entire skill prefix from Azure (catches tracked files + any marker blobs)
        storageService.deletePrefix("skills/" + skillId + "/");
        skillRepository.delete(skillId);
        skillRegistryService.refresh();
        return ResponseEntity.ok(Map.of("deleted", true, "skillId", skillId));
    }

    // ── FILE MANAGEMENT ───────────────────────────────────────────────────────

    @GetMapping("/{skillId}/files")
    @Operation(summary = "List skill files")
    public ResponseEntity<?> listSkillFiles(@PathVariable String skillId) {
        if (skillRepository.findById(skillId).isEmpty())
            return ResponseEntityFactory.notFound("Skill not found: " + skillId);
        return ResponseEntity.ok(skillRepository.findFilesBySkill(skillId));
    }

    @PostMapping("/{skillId}/files")
    @Operation(summary = "Create or update skill file (upsert by path)")
    public ResponseEntity<?> createSkillFile(@PathVariable String skillId,
                                             @Valid @RequestBody SkillFileRequest request) {
        if (skillRepository.findById(skillId).isEmpty())
            return ResponseEntityFactory.notFound("Skill not found: " + skillId);
        if (!isValidPath(request.getFilePath()))
            return ResponseEntityFactory.badRequest("Invalid filePath");

        byte[] content = request.getContent().getBytes(StandardCharsets.UTF_8);
        SkillFile saved = upsertFile(skillId, request.getFilePath(), content, request.getMimeType());
        skillRegistryService.refresh();
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{skillId}/files/{fileId}")
    @Operation(summary = "Download skill file")
    public ResponseEntity<?> getSkillFile(@PathVariable String skillId, @PathVariable String fileId) {
        SkillFile file = skillRepository.findFileById(fileId).orElse(null);
        if (file == null || !skillId.equals(file.getSkillId()))
            return ResponseEntityFactory.notFound("Skill file not found: " + fileId);
        byte[] content = storageService.get(file.getBlobPath());
        return ResponseEntity.ok(Map.of(
                "id", file.getId(),
                "filePath", file.getFilePath(),
                "mimeType", file.getMimeType(),
                "content", new String(content, StandardCharsets.UTF_8)
        ));
    }

    @PatchMapping("/{skillId}/files/{fileId}")
    @Operation(summary = "Update skill file")
    public ResponseEntity<?> updateSkillFile(@PathVariable String skillId,
                                             @PathVariable String fileId,
                                             @Valid @RequestBody SkillFileRequest request) {
        SkillFile file = skillRepository.findFileById(fileId).orElse(null);
        if (file == null || !skillId.equals(file.getSkillId()))
            return ResponseEntityFactory.notFound("Skill file not found: " + fileId);
        if (!isValidPath(request.getFilePath()))
            return ResponseEntityFactory.badRequest("Invalid filePath");

        byte[] content = request.getContent().getBytes(StandardCharsets.UTF_8);
        String blobPath = "skills/" + skillId + "/" + normalizePath(request.getFilePath());
        storageService.put(blobPath, content, request.getMimeType());
        file.setFilePath(normalizePath(request.getFilePath()));
        file.setBlobPath(blobPath);
        file.setMimeType(request.getMimeType());
        file.setContentSha256(sha256(content));
        file.setSizeBytes(content.length);
        skillRepository.updateFile(file);
        skillRegistryService.refresh();
        return ResponseEntity.ok(file);
    }

    @DeleteMapping("/{skillId}/files/{fileId}")
    @Operation(summary = "Delete skill file")
    public ResponseEntity<?> deleteSkillFile(@PathVariable String skillId, @PathVariable String fileId) {
        SkillFile file = skillRepository.findFileById(fileId).orElse(null);
        if (file == null || !skillId.equals(file.getSkillId()))
            return ResponseEntityFactory.notFound("Skill file not found: " + fileId);
        if ("SKILL.md".equalsIgnoreCase(file.getFilePath()))
            return ResponseEntityFactory.badRequest("Root SKILL.md cannot be deleted");
        storageService.delete(file.getBlobPath());
        skillRepository.deleteFile(fileId);
        skillRegistryService.refresh();
        return ResponseEntity.ok(Map.of("deleted", true, "fileId", fileId));
    }

    @GetMapping("/{skillId}/tree")
    @Operation(summary = "Get skill directory tree model")
    public ResponseEntity<?> getSkillTree(@PathVariable String skillId) {
        if (skillRepository.findById(skillId).isEmpty())
            return ResponseEntityFactory.notFound("Skill not found: " + skillId);
        var files = skillRepository.findFilesBySkill(skillId);
        var nodes = files.stream().map(f -> Map.of(
                "id", f.getId(),
                "path", f.getFilePath(),
                "name", f.getFilePath().contains("/")
                        ? f.getFilePath().substring(f.getFilePath().lastIndexOf('/') + 1)
                        : f.getFilePath(),
                "sizeBytes", f.getSizeBytes()
        )).toList();
        return ResponseEntity.ok(Map.of("skillId", skillId, "files", nodes));
    }

    // ── UPLOAD ────────────────────────────────────────────────────────────────

    @PostMapping("/upload")
    @Operation(summary = "Upload a skill package (.md, .zip, or .skill)")
    public ResponseEntity<?> uploadSkill(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String nameParam) throws IOException {

        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase();

        Skill skill;
        if (filename.endsWith(".md")) {
            byte[] bytes = file.getBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            Map<String, String> meta = parseYamlFrontmatter(content);
            String name = resolveSkillName(nameParam, meta.get("name"), stripExtension(filename, ".md"));
            String description = meta.getOrDefault("description", "");
            skill = createSkillWithDefaultFile(name, description, true);
            upsertFile(skill.getId(), "SKILL.md", bytes, "text/markdown");

        } else if (filename.endsWith(".zip") || filename.endsWith(".skill")) {
            List<Map.Entry<String, byte[]>> entries = extractZip(file.getBytes());
            String ext = filename.endsWith(".zip") ? ".zip" : ".skill";

            // SKILL.md is optional — read metadata from it if present, otherwise use provided name
            Optional<byte[]> skillMdOpt = entries.stream()
                    .filter(e -> e.getKey().equalsIgnoreCase("SKILL.md"))
                    .map(Map.Entry::getValue)
                    .findFirst();

            Map<String, String> meta = skillMdOpt
                    .map(b -> parseYamlFrontmatter(new String(b, StandardCharsets.UTF_8)))
                    .orElse(Map.of());

            String name = resolveSkillName(nameParam, meta.get("name"), stripExtension(filename, ext));
            String description = meta.getOrDefault("description", "");
            skill = createSkillWithDefaultFile(name, description, true);

            if (!entries.isEmpty()) {
                // Upload all files in parallel using virtual threads (Java 21+)
                String skillId = skill.getId();
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<CompletableFuture<Void>> futures = entries.stream()
                            .filter(e -> !e.getKey().isBlank())
                            .map(e -> CompletableFuture.runAsync(() -> {
                                String mimeType = e.getKey().endsWith(".md")
                                        ? "text/markdown" : "text/plain";
                                upsertFile(skillId, e.getKey(), e.getValue(), mimeType);
                            }, executor))
                            .toList();
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                }
            }
        } else {
            return ResponseEntityFactory.badRequest("Unsupported file type. Use .md, .zip, or .skill");
        }

        skillRegistryService.refresh();
        return ResponseEntity.ok(skill);
    }

    /** Picks the best skill name: explicit param > frontmatter name > filename fallback. */
    private static String resolveSkillName(String nameParam, String metaName, String filenameFallback) {
        if (nameParam != null && !nameParam.isBlank()) return nameParam.trim();
        if (metaName != null && !metaName.isBlank()) return metaName.trim();
        return filenameFallback.trim();
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    /**
     * Creates a skill record + default SKILL.md placeholder.
     * The SKILL.md content can be overwritten afterwards via upsertFile.
     */
    private Skill createSkillWithDefaultFile(String name, String description, boolean enabled) {
        Skill skill = Skill.builder()
                .name(name.trim())
                .description(description == null || description.isBlank() ? null : description.trim())
                .enabled(enabled)
                .build();
        Skill saved = skillRepository.save(skill);
        byte[] defaultContent = "# SKILL.md\n\nDescribe this skill behavior.".getBytes(StandardCharsets.UTF_8);
        String blobPath = "skills/" + saved.getId() + "/SKILL.md";
        storageService.put(blobPath, defaultContent, "text/markdown");
        skillRepository.saveFile(SkillFile.builder()
                .skillId(saved.getId())
                .filePath("SKILL.md")
                .blobPath(blobPath)
                .mimeType("text/markdown")
                .contentSha256(sha256(defaultContent))
                .sizeBytes(defaultContent.length)
                .build());
        return saved;
    }

    /**
     * Upsert a file for a skill: updates if (skillId, filePath) already exists, inserts otherwise.
     */
    private SkillFile upsertFile(String skillId, String rawPath, byte[] content, String mimeType) {
        String filePath = normalizePath(rawPath);
        String blobPath = "skills/" + skillId + "/" + filePath;
        storageService.put(blobPath, content, mimeType);

        Optional<SkillFile> existing = skillRepository.findFileBySkillAndPath(skillId, filePath);
        if (existing.isPresent()) {
            SkillFile file = existing.get();
            file.setFilePath(filePath);
            file.setBlobPath(blobPath);
            file.setMimeType(mimeType);
            file.setContentSha256(sha256(content));
            file.setSizeBytes(content.length);
            skillRepository.updateFile(file);
            return file;
        } else {
            SkillFile file = SkillFile.builder()
                    .skillId(skillId)
                    .filePath(filePath)
                    .blobPath(blobPath)
                    .mimeType(mimeType)
                    .contentSha256(sha256(content))
                    .sizeBytes(content.length)
                    .build();
            return skillRepository.saveFile(file);
        }
    }

    /**
     * Extracts a zip archive, filters system/hidden files, strips a common root
     * folder prefix, and preserves the remaining directory structure.
     */
    private static List<Map.Entry<String, byte[]>> extractZip(byte[] zipBytes) throws IOException {
        List<Map.Entry<String, byte[]>> raw = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && !isSystemPath(entry.getName())) {
                    raw.add(Map.entry(entry.getName(), zis.readAllBytes()));
                }
                zis.closeEntry();
            }
        }
        // Strip common single root folder (e.g. "my-skill/docs/README.md" → "docs/README.md")
        String prefix = detectCommonPrefix(raw.stream().map(Map.Entry::getKey).toList());
        List<Map.Entry<String, byte[]>> result = new ArrayList<>();
        for (var e : raw) {
            String path = prefix.isEmpty() ? e.getKey() : e.getKey().substring(prefix.length());
            // Preserve the full sub-path (folder structure intact)
            if (!path.isBlank()) result.add(Map.entry(path, e.getValue()));
        }
        return result;
    }

    /**
     * Returns true for macOS metadata, hidden files, git internals, and other
     * system noise that should never be stored as skill files.
     */
    private static boolean isSystemPath(String name) {
        if (name == null || name.isBlank()) return true;
        String lower = name.toLowerCase();
        // macOS archive artefacts
        if (lower.startsWith("__macosx/")) return true;
        // Hidden files / directories (any segment starting with ".")
        for (String segment : name.split("/")) {
            if (segment.startsWith(".")) return true; // .DS_Store, .git, .gitignore, etc.
        }
        // Windows thumbnails
        if (lower.endsWith("thumbs.db") || lower.endsWith("desktop.ini")) return true;
        return false;
    }

    private static String detectCommonPrefix(List<String> paths) {
        if (paths.isEmpty()) return "";
        String first = paths.get(0);
        int slash = first.indexOf('/');
        if (slash < 1) return "";
        String candidate = first.substring(0, slash + 1);
        boolean allMatch = paths.stream().allMatch(p -> p.startsWith(candidate));
        return allMatch ? candidate : "";
    }

    /** Parse YAML-style frontmatter between leading --- delimiters. */
    private static Map<String, String> parseYamlFrontmatter(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!content.startsWith("---")) return result;
        int end = content.indexOf("\n---", 3);
        if (end < 0) return result;
        String block = content.substring(4, end);
        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 1) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim().replaceAll("^[\"']|[\"']$", "");
            if (!key.isEmpty()) result.put(key, value);
        }
        return result;
    }

    private static String stripExtension(String filename, String ext) {
        return filename.endsWith(ext) ? filename.substring(0, filename.length() - ext.length()) : filename;
    }

    private static String normalizePath(String path) {
        return path.replace("\\", "/").replaceAll("^/+", "");
    }

    private static boolean isValidPath(String path) {
        if (path == null || path.isBlank()) return false;
        String normalized = normalizePath(path);
        return !normalized.contains("..") && !normalized.startsWith("/");
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            return "";
        }
    }
}
