/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.templates;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Org/team preset templates (I10): packaged group configurations an enterprise
 * can instantiate by assigning agents to named roles — "research pod" and
 * "decision board" instead of {@code contextScope: OWN_FEEDBACK}. Ships last by
 * design, so every template references only features that exist; instantiating
 * each template against the save-time validators doubles as an integration test
 * of the whole Wave 1–3 config surface.
 * <p>
 * Templates live on the classpath under {@code group-templates/} (the
 * {@code initial-agents/} pattern: an index file naming each resource). A
 * template file carries a {@code manifest} (id, title, description,
 * requiredRoles) and a {@code config} — a complete, valid
 * {@link AgentGroupConfiguration} whose member {@code agentId}s (and
 * {@code moderatorAgentId}) are {@code $role} placeholders that
 * {@link #instantiate} substitutes from the caller's role assignments.
 * Placeholders are the ONLY templating: everything else is literal config, so
 * what you read in the JSON is what the store validates and saves.
 *
 * @author ginccc
 */
@ApplicationScoped
public class GroupTemplateService {

    private static final Logger LOGGER = Logger.getLogger(GroupTemplateService.class);

    private static final String TEMPLATE_ROOT = "/group-templates/";
    private static final String INDEX_RESOURCE = TEMPLATE_ROOT + "index.txt";
    private static final String PLACEHOLDER_PREFIX = "$";

    private final ObjectMapper objectMapper;

    /** Insertion-ordered: the index file's order is the display order. */
    private final Map<String, GroupTemplate> templates = new LinkedHashMap<>();

    @Inject
    public GroupTemplateService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** One required role: the key a caller's roleAssignments must provide. */
    public record RequiredRole(String role, String description) {
    }

    public record TemplateManifest(String templateId, String title, String description,
            List<RequiredRole> requiredRoles) {
    }

    /**
     * A loaded template. {@code configNode} stays JSON until instantiation so a
     * listing never pays for (or fails on) config materialization.
     */
    public record GroupTemplate(TemplateManifest manifest, JsonNode configNode) {
    }

    @PostConstruct
    public void loadTemplates() {
        List<String> fileNames = readIndex();
        for (String fileName : fileNames) {
            try (InputStream in = getClass().getResourceAsStream(TEMPLATE_ROOT + fileName)) {
                if (in == null) {
                    LOGGER.errorf("Group template '%s' is named in the index but missing from the classpath", fileName);
                    continue;
                }
                JsonNode root = objectMapper.readTree(in);
                TemplateManifest manifest = objectMapper.treeToValue(root.path("manifest"), TemplateManifest.class);
                JsonNode configNode = root.path("config");
                if (manifest == null || manifest.templateId() == null || manifest.templateId().isBlank()
                        || configNode.isMissingNode() || !configNode.isObject()) {
                    LOGGER.errorf("Group template '%s' lacks a manifest.templateId or a config object — skipped", fileName);
                    continue;
                }
                templates.put(manifest.templateId(), new GroupTemplate(manifest, configNode));
            } catch (Exception e) {
                // One malformed template must not take down startup or hide the
                // others — but it must be LOUD in the log.
                LOGGER.errorf(e, "Failed to load group template '%s'", fileName);
            }
        }
        LOGGER.infof("Loaded %d group template(s): %s", templates.size(), templates.keySet());
    }

    private List<String> readIndex() {
        List<String> names = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream(INDEX_RESOURCE)) {
            if (in == null) {
                LOGGER.warnf("No group-template index at %s — no templates available", INDEX_RESOURCE);
                return names;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        names.add(line);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to read the group-template index");
        }
        return names;
    }

    /** All template manifests, in index order. */
    public List<TemplateManifest> list() {
        return templates.values().stream().map(GroupTemplate::manifest).toList();
    }

    /** The template with this id, or {@code null}. */
    public GroupTemplate find(String templateId) {
        return templateId != null ? templates.get(templateId) : null;
    }

    /**
     * Materializes a template into a savable {@link AgentGroupConfiguration}.
     * <p>
     * Fails loudly and completely BEFORE building anything: every required role
     * must be assigned a non-blank agent id, and no unknown assignment keys are
     * accepted (a typo'd role name must not silently leave a {@code $placeholder}
     * in the stored roster). The returned config has not been validated by the
     * store yet — the caller saves it through the normal store path, where every
     * save-time validator applies exactly as it would to a hand-written config.
     *
     * @param name
     *            the new group's name; {@code null}/blank keeps the template's
     * @throws IllegalArgumentException
     *             naming the missing/unknown roles, or an unreadable config
     */
    public AgentGroupConfiguration instantiate(String templateId, String name, Map<String, String> roleAssignments) {
        GroupTemplate template = find(templateId);
        if (template == null) {
            throw new IllegalArgumentException("No such template: " + templateId);
        }
        Map<String, String> assignments = roleAssignments != null ? roleAssignments : Map.of();

        List<String> missing = new ArrayList<>();
        for (RequiredRole required : template.manifest().requiredRoles()) {
            String assigned = assignments.get(required.role());
            if (assigned == null || assigned.isBlank()) {
                missing.add(required.role());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing role assignment(s): " + String.join(", ", missing));
        }
        List<String> known = template.manifest().requiredRoles().stream().map(RequiredRole::role).toList();
        List<String> unknown = assignments.keySet().stream().filter(k -> !known.contains(k)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown role(s): " + String.join(", ", unknown)
                    + " — this template's roles are: " + String.join(", ", known));
        }

        AgentGroupConfiguration config;
        try {
            config = objectMapper.treeToValue(template.configNode(), AgentGroupConfiguration.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Template '" + templateId + "' carries an unreadable config: "
                    + e.getMessage(), e);
        }

        if (config.getMembers() != null) {
            List<GroupMember> resolved = new ArrayList<>(config.getMembers().size());
            for (GroupMember member : config.getMembers()) {
                resolved.add(new GroupMember(resolvePlaceholder(member.agentId(), assignments, templateId),
                        member.displayName(), member.speakingOrder(), member.role(), member.memberType()));
            }
            config.setMembers(resolved);
        }
        config.setModeratorAgentId(resolvePlaceholder(config.getModeratorAgentId(), assignments, templateId));
        if (name != null && !name.isBlank()) {
            config.setName(name.trim());
        }
        return config;
    }

    /**
     * {@code $role} → the assigned agent id. A placeholder with no assignment is a
     * template/manifest mismatch — an authoring bug, thrown as such rather than
     * stored as a literal {@code $role} agent id.
     */
    private static String resolvePlaceholder(String value, Map<String, String> assignments, String templateId) {
        if (value == null || !value.startsWith(PLACEHOLDER_PREFIX)) {
            return value;
        }
        String role = value.substring(PLACEHOLDER_PREFIX.length());
        String assigned = assignments.get(role);
        if (assigned == null || assigned.isBlank()) {
            throw new IllegalArgumentException("Template '" + templateId + "' uses placeholder '" + value
                    + "' but its manifest declares no such required role — fix the template");
        }
        return assigned.trim();
    }
}
