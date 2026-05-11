package com.pods.agent.workflow.api;

import com.pods.agent.service.SecurityContextService;
import com.pods.agent.workflow.persistence.WorkflowApiKeyRow;
import com.pods.agent.workflow.service.WorkflowApiKeyService;
import com.pods.agent.workflow.service.WorkflowApiKeyService.Issued;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manage scoped API keys for triggering workflow runs from outside the app.
 *
 * <p>All endpoints are owner-scoped via {@link SecurityContextService#currentUserIdOrThrow()}:
 * a user only sees and revokes their own keys. The plaintext key is returned
 * exactly once at creation — every other response omits it.
 */
@RestController
@RequestMapping("/api/v1/workflow/api-keys")
public class WorkflowApiKeyController {

    private final WorkflowApiKeyService service;
    private final SecurityContextService securityContext;

    public WorkflowApiKeyController(WorkflowApiKeyService service,
                                    SecurityContextService securityContext) {
        this.service = service;
        this.securityContext = securityContext;
    }

    @PostMapping
    public ResponseEntity<CreateResponse> create(@RequestBody CreateRequest req) {
        String ownerId = securityContext.currentUserIdOrThrow();
        Issued issued = service.create(ownerId, req.name(), req.processDefIds());
        return ResponseEntity.ok(CreateResponse.from(issued, service.parseScope(issued.row())));
    }

    @GetMapping
    public ResponseEntity<List<ApiKeySummary>> list() {
        String ownerId = securityContext.currentUserIdOrThrow();
        List<ApiKeySummary> out = service.listOwnedBy(ownerId).stream()
                .map(row -> ApiKeySummary.from(row, service.parseScope(row)))
                .toList();
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable("id") String id) {
        String ownerId = securityContext.currentUserIdOrThrow();
        boolean revoked = service.revoke(id, ownerId);
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Hard-delete a previously-revoked key. Returns 404 when the key isn't
     * owned by the caller or hasn't been revoked yet — purge is only valid
     * as a follow-up to revoke.
     */
    @DeleteMapping("/{id}/purge")
    public ResponseEntity<Void> purge(@PathVariable("id") String id) {
        String ownerId = securityContext.currentUserIdOrThrow();
        boolean purged = service.purge(id, ownerId);
        return purged ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Update an existing key's name and/or scope. The secret is left alone —
     * the caller doesn't need to rotate it just to change which workflows it
     * can trigger. Returns 404 when the row isn't owned by the caller or
     * has been revoked.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable("id") String id,
                                       @RequestBody UpdateRequest req) {
        String ownerId = securityContext.currentUserIdOrThrow();
        boolean ok = service.update(id, ownerId, req.name(), req.processDefIds());
        return ok ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Rotate the secret on an existing key. Name and scope are preserved;
     * the previous plaintext stops working as soon as this returns. The new
     * plaintext is returned the same way as on create — exactly once.
     */
    @PostMapping("/{id}/regenerate")
    public ResponseEntity<CreateResponse> regenerate(@PathVariable("id") String id) {
        String ownerId = securityContext.currentUserIdOrThrow();
        return service.regenerate(id, ownerId)
                .map(issued -> ResponseEntity.ok(
                        CreateResponse.from(issued, service.parseScope(issued.row()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateRequest(String name, List<String> processDefIds) {}

    public record UpdateRequest(String name, List<String> processDefIds) {}

    /**
     * One-shot response. {@code key} is the plaintext — the caller must
     * store it now, the server can't show it again.
     */
    public record CreateResponse(
            String id,
            String name,
            String key,
            String keyPrefix,
            List<String> processDefIds,
            long createdAt
    ) {
        static CreateResponse from(Issued issued, List<String> scope) {
            WorkflowApiKeyRow row = issued.row();
            return new CreateResponse(
                    row.id(),
                    row.name(),
                    issued.plaintextKey(),
                    row.keyPrefix(),
                    scope,
                    row.createdAt());
        }
    }

    /** Listing view — no plaintext, just the prefix for disambiguation. */
    public record ApiKeySummary(
            String id,
            String name,
            String keyPrefix,
            List<String> processDefIds,
            long createdAt,
            Long lastUsedAt,
            Long revokedAt
    ) {
        static ApiKeySummary from(WorkflowApiKeyRow row, List<String> scope) {
            return new ApiKeySummary(
                    row.id(),
                    row.name(),
                    row.keyPrefix(),
                    scope,
                    row.createdAt(),
                    row.lastUsedAt(),
                    row.revokedAt());
        }
    }
}
