/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.rest;

import ai.labs.eddi.secrets.model.SecretMetadata;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * One entry of {@code GET /secretstore/secrets/{tenantId}}: the secret's
 * metadata (never its value or checksum) plus two fields an administrator needs
 * to judge it.
 *
 * @param unrestrictedDestination
 *            {@code true} when {@code allowedHosts} is empty, so the vault
 *            sends the secret to whatever host a configuration names. Read live
 *            from the metadata
 * @param referencedBy
 *            the LLM, MCP-calls, RAG, httpcalls and channel configurations the
 *            startup scan found referencing the secret
 *            ({@code UnrestrictedSecretsCheck}); absent until a scan has
 *            completed. A secret that is both unrestricted and referenced is
 *            the H1 residual of the 2026-10-17 security review — narrow it with
 *            {@code PUT …/{keyName}/grant}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecretListing(String tenantId, String keyName, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastAccessedAt, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastRotatedAt,
        String description, List<String> allowedAgents, List<String> allowedHosts, boolean unrestrictedDestination, List<String> referencedBy) {

    static SecretListing of(SecretMetadata metadata, List<String> referencedBy) {
        return new SecretListing(metadata.tenantId(), metadata.keyName(), metadata.createdAt(), metadata.lastAccessedAt(), metadata.lastRotatedAt(),
                metadata.description(), metadata.allowedAgents(), metadata.allowedHosts(), !SecretMetadata.restrictsHosts(metadata.allowedHosts()),
                referencedBy);
    }
}
