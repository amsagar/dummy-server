package com.pods.agent.service.instruction;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class InstructionLoaderService {
    private static final List<String> FILENAMES = List.of("AGENTS.md", "CLAUDE.md");

    public String load(Path workspaceRoot) {
        if (workspaceRoot == null) return "";
        List<String> chunks = new ArrayList<>();
        Path cursor = workspaceRoot.toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && cursor != null; depth++) {
            for (String filename : FILENAMES) {
                Path candidate = cursor.resolve(filename);
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    try {
                        String text = Files.readString(candidate, StandardCharsets.UTF_8);
                        if (!text.isBlank()) {
                            String location = depth == 0 ? "workspace:///" : "workspace-parent:///" + depth;
                            chunks.add("## " + filename + " (" + location + ")\n" + text.trim());
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            cursor = cursor.getParent();
        }
        if (chunks.isEmpty()) return "";
        return String.join("\n\n", chunks);
    }
}

