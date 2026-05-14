package com.pods.agent.ruledomain.invalidation;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 of the skill markdown bytes. Stored on each compiled domain row;
 * mismatch at refresh time triggers auto-deprecation in {@code RuleDomainInvalidator}.
 *
 * Whitespace-sensitive on purpose — even cosmetic edits should be treated as
 * a change worth re-compiling for, since the LLM may produce different BPMN.
 */
@Component
public class SkillSourceHasher {

    public String hash(String skillMarkdown) {
        if (skillMarkdown == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(skillMarkdown.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
