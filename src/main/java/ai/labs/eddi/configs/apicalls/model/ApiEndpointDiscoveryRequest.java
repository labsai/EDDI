/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

/**
 * Body of a {@code POST /apicallstore/apicalls/discover-endpoints} request.
 * <p>
 * {@code authHeaderRef} is a <em>reference</em>, never a credential. The
 * superseded {@code GET} form took a full {@code Authorization} header value as
 * {@code ?apiAuth=} and wrote it verbatim into every generated ApiCall, which
 * then came straight back in the response body — so one pasted key was
 * simultaneously in the request URL, in ingress and proxy logs, in the
 * response, and (once imported) in plaintext in MongoDB.
 * <p>
 * Discovery does not need a live credential: the OpenAPI document is fetched
 * unauthenticated or supplied inline, and the generated configs only need to
 * know <em>which</em> credential to use at call time. So the field is validated
 * to be a {@code ${vault:…}}, {@code ${vars:…}} or {@code ${caller:…}}
 * reference, and a literal is refused. That is the same rule the rest of the
 * platform applies to credential-bearing config fields — only a reference is
 * ever inherited, never a key.
 *
 * @param specUrl
 *            OpenAPI 3.x document URL, or the document itself inline
 * @param apiBaseUrl
 *            optional override for the spec's {@code servers[0]}
 * @param authHeaderRef
 *            optional {@code Authorization} header value for the generated
 *            calls; must be a vault/global-variable/caller reference
 */
public record ApiEndpointDiscoveryRequest(String specUrl, String apiBaseUrl, String authHeaderRef) {
}
