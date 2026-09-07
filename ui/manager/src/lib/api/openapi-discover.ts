import { api } from "@/lib/api-client";
import { isAuthReference } from "@/lib/secret-reference";
import type { HttpCall } from "@/components/editors/apicalls-editor";

/**
 * Result of discovering endpoints from an OpenAPI spec.
 */
export interface DiscoverEndpointsResult {
  title: string;
  baseUrl: string;
  endpointCount: number;
  groups: Record<string, { targetServerUrl: string; httpCalls: HttpCall[] }>;
}

/** Body of `POST /apicallstore/apicalls/discover-endpoints`. */
interface DiscoverEndpointsRequest {
  specUrl: string;
  apiBaseUrl?: string;
  authHeaderRef?: string;
}

/**
 * Thrown before the request goes out, when `authHeaderRef` holds a literal.
 *
 * Refusing locally rather than letting EDDI answer 400 is the point: the whole
 * reason the parameter moved out of the URL is that a credential is logged by
 * every hop before the server sees it. Sending one to be rejected would leak it
 * exactly as before, and then report the leak as a validation error.
 */
export class LiteralCredentialError extends Error {
  constructor() {
    super("authHeaderRef must be a credential reference, not a literal");
    this.name = "LiteralCredentialError";
  }
}

/**
 * Parse an OpenAPI spec via the backend and return discovered endpoints
 * grouped by tag, with fully generated HttpCall objects.
 *
 * `POST`, not the `GET` this used to use. The old form took a live
 * `Authorization` value as `?apiAuth=` and wrote it verbatim into every
 * generated ApiCall, which then came straight back in the response body — so one
 * pasted key was simultaneously in the request URL, in ingress and proxy logs,
 * in the response, and in plaintext in MongoDB once imported. The `GET` survives
 * for credential-free discovery, deprecated for removal, and rejects any request
 * that still carries a credential parameter.
 *
 * @param authHeaderRef
 *   a `${vault:…}`, `${vars:…}` or `${caller:…}` reference EDDI resolves at call
 *   time. Discovery itself never needs a live credential — the generated configs
 *   only need to know *which* one to use.
 */
export async function discoverEndpoints(
  specUrl: string,
  apiBaseUrl = "",
  authHeaderRef = "",
): Promise<DiscoverEndpointsResult> {
  const trimmedRef = authHeaderRef.trim();
  if (trimmedRef && !isAuthReference(trimmedRef)) {
    throw new LiteralCredentialError();
  }

  const request: DiscoverEndpointsRequest = { specUrl };
  if (apiBaseUrl) request.apiBaseUrl = apiBaseUrl;
  if (trimmedRef) request.authHeaderRef = trimmedRef;

  return api.post<DiscoverEndpointsResult>(
    "/apicallstore/apicalls/discover-endpoints",
    request,
  );
}
