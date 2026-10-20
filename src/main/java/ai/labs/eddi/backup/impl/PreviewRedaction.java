/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.apicalls.rest.RestApiCallsStore;
import ai.labs.eddi.configs.llm.rest.RestLlmStore;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The preview an upgrade or sync shows a caller who is not an administrator.
 * <p>
 * {@link StructuralMatcher} reads its targets unredacted so that an editor-run
 * upgrade restores the target's own credential rather than the read-redaction
 * marker — but the same {@code ResourceDiff} content is what the preview
 * endpoints return, and those admit {@code eddi-editor}. So the content is
 * redacted here, at the REST boundary, with the redactor and the skipped paths
 * the stores apply to a {@code GET}; the upgrade itself still works from the
 * unredacted read.
 */
final class PreviewRedaction {

    /**
     * Per resource type, the container paths the owning store does not treat as
     * configuration (tool-parameter descriptions, cache scopes keyed by tool name).
     */
    private static final Map<String, Set<String>> SKIPPED_PATHS = Map.of(
            AbstractBackupService.LLM_EXT, RestLlmStore.NOT_CONFIGURATION,
            AbstractBackupService.HTTPCALLS_EXT, RestApiCallsStore.NOT_CONFIGURATION);

    private PreviewRedaction() {
    }

    /**
     * {@code preview} with every plaintext credential in its source and target
     * content redacted, or {@code preview} itself when there is none.
     */
    static ImportPreview forNonAdmin(ImportPreview preview) {
        if (preview == null || preview.resources() == null || preview.resources().isEmpty()) {
            return preview;
        }
        boolean changed = false;
        List<ResourceDiff> resources = new ArrayList<>(preview.resources().size());
        for (ResourceDiff diff : preview.resources()) {
            Set<String> skipped = SKIPPED_PATHS.getOrDefault(diff.resourceType(), Set.of());
            String source = ConfigSecretRedactor.redactJson(diff.sourceContent(), skipped);
            String target = ConfigSecretRedactor.redactJson(diff.targetContent(), skipped);
            if (Objects.equals(source, diff.sourceContent()) && Objects.equals(target, diff.targetContent())) {
                resources.add(diff);
            } else {
                changed = true;
                resources.add(new ResourceDiff(diff.sourceId(), diff.resourceType(), diff.name(), diff.action(), diff.targetId(),
                        diff.targetVersion(), diff.matchStrategy(), source, target, diff.workflowIndex()));
            }
        }
        if (!changed) {
            return preview;
        }
        return new ImportPreview(preview.sourceAgentId(), preview.sourceAgentName(), preview.targetAgentId(),
                preview.targetAgentName(), resources, preview.error(), preview.warnings());
    }
}
