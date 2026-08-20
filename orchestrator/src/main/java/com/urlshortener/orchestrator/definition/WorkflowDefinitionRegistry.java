package com.urlshortener.orchestrator.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads every {@code workflows/*.yaml} file on the classpath (main + test resources) into a
 * validated, in-memory {@link WorkflowDefinition} registry at startup. Definitions are static
 * config, distinct from the mutable runtime state tracked in the JPA entities.
 */
@Component
@Slf4j
public class WorkflowDefinitionRegistry {

    private final Map<String, WorkflowDefinition> definitions = new LinkedHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @PostConstruct
    void load() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:workflows/*.yaml");
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    WorkflowDefinition definition = yamlMapper.readValue(in, WorkflowDefinition.class);
                    definition.validate();
                    definitions.put(definition.getId(), definition);
                    log.info("Loaded workflow definition '{}' ({} nodes) from {}",
                            definition.getId(), definition.getNodes().size(), resource.getFilename());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load workflow definitions", e);
        }
        if (definitions.isEmpty()) {
            log.warn("No workflow definitions found on classpath under workflows/*.yaml");
        }
    }

    public Optional<WorkflowDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public WorkflowDefinition require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown workflow definition: " + id));
    }

    public Map<String, WorkflowDefinition> all() {
        return Collections.unmodifiableMap(definitions);
    }
}
