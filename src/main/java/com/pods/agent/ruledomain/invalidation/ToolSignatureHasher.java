package com.pods.agent.ruledomain.invalidation;

import com.pods.agent.domain.AgentTool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Stable hash over the (name, requestSchema) tuples for every tool a domain
 * references. Used at refresh time to detect tool schema drift — if a tool's
 * request schema changed since the BPMN was compiled, the BPMN's argTemplate
 * may now reference fields that no longer exist (or omit required ones), so
 * the domain must be re-compiled.
 *
 * Tools are sorted by name for determinism.
 */
@Component
public class ToolSignatureHasher {

    public String hash(List<AgentTool> tools) {
        if (tools == null || tools.isEmpty()) return "";
        StringBuilder canonical = new StringBuilder();
        tools.stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .forEach(t -> canonical.append(t.getName().toLowerCase())
                        .append('')
                        .append(t.getRequestSchema() == null ? "" : t.getRequestSchema().trim())
                        .append(''));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
