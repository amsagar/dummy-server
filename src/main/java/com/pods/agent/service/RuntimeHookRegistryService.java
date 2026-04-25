package com.pods.agent.service;

import com.pods.agent.domain.HookMapping;
import com.pods.agent.repository.HookMappingRepository;
import com.pods.agent.service.hooks.RuntimeHook;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RuntimeHookRegistryService {
    private final HookMappingRepository hookMappingRepository;
    private final Map<String, RuntimeHook> hookImplementations;
    private final ObjectMapper objectMapper;

    public RuntimeHookRegistryService(HookMappingRepository hookMappingRepository,
                                      List<RuntimeHook> hookImplementations,
                                      ObjectMapper objectMapper) {
        this.hookMappingRepository = hookMappingRepository;
        this.hookImplementations = hookImplementations.stream()
                .collect(Collectors.toMap(RuntimeHook::name, Function.identity(), (a, b) -> a, TreeMap::new));
        this.objectMapper = objectMapper;
    }

    public void register(String hookPoint, String hookName) {
        register(hookPoint, hookName, null, "{}", true);
    }

    public void register(String hookPoint, String hookName, String profileId, String configJson, boolean enabled) {
        hookMappingRepository.save(HookMapping.builder()
                .hookPoint(hookPoint)
                .hookName(hookName)
                .profileId(profileId)
                .configJson(configJson)
                .enabled(enabled)
                .build());
    }

    public List<String> list(String hookPoint) {
        return hookMappingRepository.findEnabledByPoint(hookPoint).stream()
                .map(HookMapping::getHookName)
                .toList();
    }

    public Map<String, List<String>> listAll() {
        return hookMappingRepository.findAll().stream()
                .filter(HookMapping::isEnabled)
                .collect(Collectors.groupingBy(HookMapping::getHookPoint,
                        TreeMap::new,
                        Collectors.mapping(HookMapping::getHookName, Collectors.collectingAndThen(Collectors.toList(), list ->
                                list.stream().sorted().toList()))));
    }

    public void clear(String hookPoint) {
        hookMappingRepository.deleteByPoint(hookPoint);
    }

    public void emit(String hookPoint, Map<String, Object> payload) {
        hookMappingRepository.findEnabledByPoint(hookPoint).forEach(h -> {
            try {
                RuntimeHook runtimeHook = hookImplementations.get(h.getHookName());
                if (runtimeHook == null) return;
                runtimeHook.execute(hookPoint, payload == null ? Map.of() : payload, parseConfig(h.getConfigJson()));
            } catch (Exception ignored) {
            }
        });
    }

    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(configJson, Map.class);
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
