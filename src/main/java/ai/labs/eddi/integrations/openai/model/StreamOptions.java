/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The OpenAI {@code stream_options} object.
 * <p>
 * Only {@code include_usage} is modelled; it is opt-in because the usage chunk
 * carries an empty {@code choices} array, which clients that did not ask for it
 * are entitled to treat as malformed.
 *
 * @since 6.1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {
}
