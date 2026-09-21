/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import io.quarkus.smallrye.openapi.OpenApiFilter;
import java.util.ArrayList;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.util.Comparator;
import org.eclipse.microprofile.openapi.models.tags.Tag;

/**
 * Sorts OpenAPI tags alphabetically at build time, producing a stable,
 * logically grouped order in Swagger UI and all API consumers.
 *
 * @since 6.1.1
 */
@OpenApiFilter(OpenApiFilter.RunStage.BUILD)
public class OpenApiTagSortFilter implements OASFilter {

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        if (openAPI.getTags() != null) {
            var sorted = new ArrayList<>(openAPI.getTags());
            sorted.sort(Comparator.comparing(
                    Tag::getName));
            openAPI.setTags(sorted);
        }
    }
}
