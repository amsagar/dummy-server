package com.pods.agent.workflow.api;

import tools.jackson.databind.ObjectMapper;
import com.pods.agent.workflow.api.dto.ProcessDefDto;
import com.pods.agent.workflow.engine.domain.ProcessDefinition;
import com.pods.agent.workflow.persistence.ProcessDefRepository;
import com.pods.agent.workflow.persistence.ProcessDefRow;
import com.pods.agent.workflow.persistence.ProcessInstRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for process definitions: persists / retrieves the wire DTO,
 * and converts to the engine {@link ProcessDefinition} on demand.
 */
@Service
@Slf4j
public class ProcessDefService {

    private final ProcessDefRepository repo;
    private final ProcessInstRepository processInstRepo;
    private final ObjectMapper objectMapper;

    public ProcessDefService(ProcessDefRepository repo,
                             ProcessInstRepository processInstRepo,
                             ObjectMapper objectMapper) {
        this.repo = repo;
        this.processInstRepo = processInstRepo;
        this.objectMapper = objectMapper;
    }

    public ProcessDefDto save(ProcessDefDto dto) {
        // Generate an id BEFORE validation; ProcessDefinition.build rejects
        // blank ids, but the UI legitimately POSTs without one on create.
        String id = (dto.id() != null && !dto.id().isBlank())
                ? dto.id()
                : UUID.randomUUID().toString();
        ProcessDefDto withId = new ProcessDefDto(
                id, dto.name(), dto.version(), dto.packageId(), dto.description(),
                dto.variables(), dto.activities(), dto.transitions());

        // Validate by converting through the engine domain (catches duplicate
        // activity ids, dangling transitions, missing start, etc.).
        ProcessDefinition validated = ProcessDefinitionMapper.toDomain(withId);

        long now = Instant.now().toEpochMilli();
        try {
            String json = objectMapper.writeValueAsString(withId);
            repo.upsert(new ProcessDefRow(
                    id,
                    validated.name(),
                    validated.version(),
                    validated.packageId(),
                    validated.description(),
                    json,
                    now,
                    now));
        } catch (RuntimeException e) {
            throw new RuntimeException("could not serialize process def: " + e.getMessage(), e);
        }
        return withId;
    }

    public Optional<ProcessDefDto> findById(String id) {
        return repo.findById(id).map(this::deserialize);
    }

    public Optional<ProcessDefinition> loadDomainById(String id) {
        return findById(id).map(ProcessDefinitionMapper::toDomain);
    }

    public List<ProcessDefDto> findAll() {
        return repo.findAll().stream().map(this::deserialize).toList();
    }

    public List<ProcessDefDto> findByName(String name) {
        return repo.findByName(name).stream().map(this::deserialize).toList();
    }

    public boolean deleteById(String id) {
        long runCount = processInstRepo.countByDefId(id);
        if (runCount > 0) {
            throw new IllegalStateException(
                    "Cannot delete workflow definition because it has " + runCount
                            + " run(s). Delete run history first or keep this definition for audit.");
        }
        return repo.deleteById(id) > 0;
    }

    @Transactional
    public boolean forceDeleteById(String id) {
        processInstRepo.deleteByDefId(id);
        return repo.deleteById(id) > 0;
    }

    private ProcessDefDto deserialize(ProcessDefRow row) {
        try {
            return objectMapper.readValue(row.xpdlJson(), ProcessDefDto.class);
        } catch (RuntimeException e) {
            throw new RuntimeException("could not deserialize process def "
                    + row.id() + ": " + e.getMessage(), e);
        }
    }
}
