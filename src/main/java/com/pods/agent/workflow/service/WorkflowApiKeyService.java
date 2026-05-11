package com.pods.agent.workflow.service;

import com.pods.agent.workflow.persistence.WorkflowApiKeyRepository;
import com.pods.agent.workflow.persistence.WorkflowApiKeyRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Issues, verifies, and revokes scoped API keys used to trigger workflow
 * runs from outside the app.
 *
 * <p>Key format: {@code pak_<32 url-safe random chars>} where {@code pak_}
 * stands for "pods api key". The prefix lets ops tell at a glance what kind
 * of credential it is in logs.
 *
 * <p>Storage: we persist only the sha-256 hash of the plaintext key and the
 * first 12 chars (which include the {@code pak_} prefix) as an index for
 * O(1) lookup at auth time. The plaintext is returned to the caller exactly
 * once on creation.
 */
@Service
public class WorkflowApiKeyService {

    private static final String KEY_PREFIX = "pak_";
    private static final int RANDOM_LEN = 32;
    private static final int LOOKUP_PREFIX_LEN = 12; // "pak_" + 8 random chars
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final WorkflowApiKeyRepository repo;
    private final ObjectMapper objectMapper;
    private final SecureRandom rng = new SecureRandom();

    public WorkflowApiKeyService(WorkflowApiKeyRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * Mint a new key. The plaintext in {@link Issued#plaintextKey()} is the
     * only place it ever appears — store it now or lose it.
     */
    public Issued create(String ownerId, String name, List<String> processDefIds) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (processDefIds == null || processDefIds.isEmpty()) {
            throw new IllegalArgumentException("processDefIds must list at least one workflow");
        }
        String plaintext = generateKey();
        String prefix = plaintext.substring(0, LOOKUP_PREFIX_LEN);
        String hash = sha256Hex(plaintext);
        String id = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();
        WorkflowApiKeyRow row = new WorkflowApiKeyRow(
                id, name, prefix, hash, ownerId,
                toJsonArray(processDefIds), now, null, null);
        repo.insert(row);
        return new Issued(row, plaintext);
    }

    /**
     * Validate a plaintext key presented at request time. Returns the row
     * (with scope) on success; empty on unknown/revoked/tampered keys.
     * Updates last_used_at on hit.
     */
    public Optional<WorkflowApiKeyRow> verify(String plaintextKey) {
        if (plaintextKey == null || plaintextKey.length() < LOOKUP_PREFIX_LEN) {
            return Optional.empty();
        }
        if (!plaintextKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        String prefix = plaintextKey.substring(0, LOOKUP_PREFIX_LEN);
        String hash = sha256Hex(plaintextKey);
        for (WorkflowApiKeyRow candidate : repo.findByPrefix(prefix)) {
            if (candidate.revokedAt() != null) continue;
            if (constantTimeEquals(candidate.keyHash(), hash)) {
                repo.touchLastUsed(candidate.id(), Instant.now().toEpochMilli());
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public List<WorkflowApiKeyRow> listOwnedBy(String ownerId) {
        return repo.listByOwner(ownerId);
    }

    public boolean revoke(String id, String ownerId) {
        return repo.revoke(id, ownerId, Instant.now().toEpochMilli());
    }

    /**
     * Hard-delete an already-revoked key. Returns false when the row isn't
     * owned by {@code ownerId} or hasn't been revoked yet — keys must be
     * revoked first, then purged. This two-step keeps "delete" from being
     * a one-click escape hatch that silently kills active credentials.
     */
    public boolean purge(String id, String ownerId) {
        return repo.purge(id, ownerId);
    }

    /**
     * Scrub the given process def id out of every API key's scope. Invoked
     * by workflow deletion so deleted workflows don't linger as stale ids
     * in keys. Keys whose scope becomes empty are NOT auto-revoked — they
     * stay listed (with an empty scope) so the owner can see what happened
     * and either edit or revoke explicitly. Returns the number of keys
     * touched.
     *
     * <p>Errors on individual rows are logged but never thrown — workflow
     * deletion shouldn't be blocked by an unparseable scope column.
     */
    public int removeProcessDefFromAllScopes(String processDefId) {
        if (processDefId == null || processDefId.isBlank()) return 0;
        int touched = 0;
        for (WorkflowApiKeyRow row : repo.listReferencingDef(processDefId)) {
            try {
                List<String> scope = parseScope(row);
                if (!scope.contains(processDefId)) continue;
                List<String> next = new ArrayList<>(scope);
                next.remove(processDefId);
                repo.setScope(row.id(), toJsonArray(next));
                touched++;
            } catch (Exception e) {
                // Best-effort cleanup — don't let a malformed row block the
                // delete cascade. The dangling id will eventually be visible
                // in the UI as a hint to clean up manually.
            }
        }
        return touched;
    }

    /**
     * Update an existing key's editable fields (name + scope). Secret
     * material is untouched. Returns false when the row isn't owned by
     * {@code ownerId} or has been revoked.
     */
    public boolean update(String id, String ownerId, String name, List<String> processDefIds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (processDefIds == null || processDefIds.isEmpty()) {
            throw new IllegalArgumentException("processDefIds must list at least one workflow");
        }
        return repo.update(id, ownerId, name, toJsonArray(processDefIds));
    }

    /**
     * Rotate an existing key: mint a fresh plaintext, replace the stored
     * prefix + hash on the same row, leave name and scope untouched. The
     * previous plaintext stops authenticating the moment this returns.
     * Returns empty when the row isn't owned by {@code ownerId}, doesn't
     * exist, or is already revoked.
     */
    public Optional<Issued> regenerate(String id, String ownerId) {
        Optional<WorkflowApiKeyRow> existing = repo.findById(id);
        if (existing.isEmpty()) return Optional.empty();
        WorkflowApiKeyRow row = existing.get();
        if (!row.ownerId().equals(ownerId) || row.revokedAt() != null) {
            return Optional.empty();
        }
        String plaintext = generateKey();
        String prefix = plaintext.substring(0, LOOKUP_PREFIX_LEN);
        String hash = sha256Hex(plaintext);
        boolean ok = repo.rotate(id, ownerId, prefix, hash);
        if (!ok) return Optional.empty();
        WorkflowApiKeyRow rotated = new WorkflowApiKeyRow(
                row.id(), row.name(), prefix, hash, row.ownerId(),
                row.processDefIds(), row.createdAt(), null, null);
        return Optional.of(new Issued(rotated, plaintext));
    }

    /**
     * Parse the persisted JSON array of process_def ids back into a list.
     * Returns an empty list on any parse trouble — fail closed.
     */
    public List<String> parseScope(WorkflowApiKeyRow row) {
        if (row == null || row.processDefIds() == null || row.processDefIds().isBlank()) {
            return List.of();
        }
        try {
            String[] ids = objectMapper.readValue(row.processDefIds(), String[].class);
            return ids == null ? List.of() : List.of(ids);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJsonArray(List<String> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            // Shouldn't happen for a List<String>; fall back to empty array
            // so we don't accidentally persist a key with broken scope.
            return "[]";
        }
    }

    private String generateKey() {
        StringBuilder sb = new StringBuilder(KEY_PREFIX.length() + RANDOM_LEN);
        sb.append(KEY_PREFIX);
        for (int i = 0; i < RANDOM_LEN; i++) {
            sb.append(ALPHABET.charAt(rng.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** Result of {@link #create}. The plaintext key is shown to the user once. */
    public record Issued(WorkflowApiKeyRow row, String plaintextKey) {
        public List<String> processDefIds(ObjectMapper om) {
            try {
                String[] arr = om.readValue(row.processDefIds(), String[].class);
                return arr == null ? List.of() : new ArrayList<>(List.of(arr));
            } catch (Exception e) {
                return List.of();
            }
        }
    }
}
