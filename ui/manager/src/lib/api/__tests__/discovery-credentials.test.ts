import { describe, expect, it, beforeEach, afterEach, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { discoverMcpTools } from "../mcp-discover";
import { discoverEndpoints, LiteralCredentialError } from "../openapi-discover";
import { isAuthReference } from "@/lib/secret-reference";

const MCP_URL = `${window.location.origin}/mcpcallsstore/mcpcalls/discover-tools`;
const OPENAPI_URL = `${window.location.origin}/apicallstore/apicalls/discover-endpoints`;

/** What one probe actually put on the wire. */
interface Captured {
  method: string;
  search: string;
  body: unknown;
  header: (name: string) => string | null;
}

function capture(url: string, respond: () => Response) {
  const seen: Captured[] = [];
  const handler = async ({ request }: { request: Request }) => {
    const parsed = new global.URL(request.url);
    seen.push({
      method: request.method,
      search: parsed.search,
      body: await request.clone().json().catch(() => null),
      header: (name) => request.headers.get(name),
    });
    return respond();
  };
  server.use(http.post(url, handler), http.get(url, handler));
  return seen;
}

/**
 * Discovery, after EDDI stopped accepting a credential in the URL.
 *
 * Both probes used to pass the third-party key as a query parameter —
 * `?apiKey=` for MCP, `?apiAuth=` for OpenAPI. A credential in a URL is written
 * to ingress logs, reverse-proxy logs, browser history and APM traces before any
 * handler sees it, so EDDI removed the parameters rather than validating them,
 * and now answers 400 to any request still carrying one. It judges the parameter
 * by name, and both `apiKey` and `apiAuth` match its credential-word list, so
 * neither call survived.
 *
 * The two ends of that are what these tests pin: the credential must not appear
 * in the URL, and it must still reach the server.
 */
describe("discoverMcpTools", () => {
  it("puts the key in a header, never in the query string", async () => {
    // The whole point. A test asserting only "the header is set" would pass
    // against a client that ALSO still appended the parameter.
    const seen = capture(MCP_URL, () =>
      HttpResponse.json({ tools: [], count: 0 }),
    );

    await discoverMcpTools("https://mcp.example.com", "http", "sk-live-secret");

    expect(seen).toHaveLength(1);
    expect(seen[0]!.method).toBe("POST");
    expect(seen[0]!.header("X-Mcp-Authorization")).toBe("sk-live-secret");
    expect(seen[0]!.search).toBe("");
  });

  it("sends the url and transport in the body", async () => {
    const seen = capture(MCP_URL, () =>
      HttpResponse.json({ tools: [], count: 0 }),
    );

    await discoverMcpTools("https://mcp.example.com", "sse", "");

    expect(seen[0]!.body).toEqual({
      url: "https://mcp.example.com",
      transport: "sse",
    });
  });

  it("omits the header entirely for an unauthenticated server", async () => {
    // An empty header is still a header, and it reaches the probed server as
    // one — some reject a blank Authorization outright.
    const seen = capture(MCP_URL, () =>
      HttpResponse.json({ tools: [], count: 0 }),
    );

    await discoverMcpTools("https://mcp.example.com");

    expect(seen[0]!.header("X-Mcp-Authorization")).toBeNull();
  });
});

describe("discoverEndpoints", () => {
  const result = { title: "t", baseUrl: "b", endpointCount: 0, groups: {} };

  it("posts the spec url in a body rather than a query string", async () => {
    const seen = capture(OPENAPI_URL, () => HttpResponse.json(result));

    await discoverEndpoints("https://api.example.com/openapi.json");

    expect(seen[0]!.method).toBe("POST");
    expect(seen[0]!.search).toBe("");
    expect(seen[0]!.body).toEqual({
      specUrl: "https://api.example.com/openapi.json",
    });
  });

  it("passes a vault reference through as authHeaderRef", async () => {
    const seen = capture(OPENAPI_URL, () => HttpResponse.json(result));

    await discoverEndpoints("https://api.example.com/spec.json", "", "${vault:jira}");

    expect(seen[0]!.body).toMatchObject({ authHeaderRef: "${vault:jira}" });
  });

  it("refuses a literal credential before the request goes out", async () => {
    // Refusing locally rather than letting EDDI answer 400 is the point: the
    // reason the parameter moved out of the URL is that a credential is logged
    // by every hop before the server sees it. Sending one to be rejected would
    // leak it exactly as before and then call the leak a validation error.
    const seen = capture(OPENAPI_URL, () => HttpResponse.json(result));

    await expect(
      discoverEndpoints("https://api.example.com/spec.json", "", "sk-live-secret"),
    ).rejects.toBeInstanceOf(LiteralCredentialError);

    expect(seen).toHaveLength(0);
  });

  it("treats a blank reference as absent, not as a literal", async () => {
    // Most specs are public and discovery never needs a live credential, so the
    // empty case is the common one and must not be an error.
    const seen = capture(OPENAPI_URL, () => HttpResponse.json(result));

    await discoverEndpoints("https://api.example.com/spec.json", "", "   ");

    expect(seen[0]!.body).not.toHaveProperty("authHeaderRef");
  });
});

describe("isAuthReference", () => {
  it("accepts every scheme EDDI resolves", () => {
    for (const value of [
      "${vault:key}",
      "${eddivault:key}",
      "${vars:region}",
      "${caller:token}",
    ]) {
      expect(isAuthReference(value)).toBe(true);
    }
  });

  it("requires the reference to come first, as EDDI's startsWith does", () => {
    // The constraint that surprises people, and the reason the field says the
    // stored secret has to include its own `Bearer ` prefix.
    expect(isAuthReference("Bearer ${vault:key}")).toBe(false);
  });

  it("rejects a literal and an empty value", () => {
    for (const value of ["sk-live-abc", "", "   ", null, undefined]) {
      expect(isAuthReference(value)).toBe(false);
    }
  });
});

describe("no credential-bearing query parameter survives", () => {
  let warn: ReturnType<typeof vi.spyOn>;
  beforeEach(() => {
    warn = vi.spyOn(console, "warn").mockImplementation(() => {});
  });
  afterEach(() => warn.mockRestore());

  it("neither probe sends a parameter EDDI would reject by name", async () => {
    // EDDI matches the parameter NAME against a credential-word list — `apiKey`
    // runs together to "apikey", `apiAuth` ends in the word "auth" — so this
    // asserts the class of bug rather than the two spellings that caused it.
    const looksLikeCredential = (search: string) =>
      [...new global.URLSearchParams(search).keys()].some((name) =>
        /token|secret|credential|password|apikey|auth/i.test(name.replace(/[^a-z0-9]/gi, "")),
      );

    const mcp = capture(MCP_URL, () => HttpResponse.json({ tools: [], count: 0 }));
    await discoverMcpTools("https://mcp.example.com", "http", "sk-live");
    expect(looksLikeCredential(mcp[0]!.search)).toBe(false);

    const openapi = capture(OPENAPI_URL, () =>
      HttpResponse.json({ title: "t", baseUrl: "b", endpointCount: 0, groups: {} }),
    );
    await discoverEndpoints("https://api.example.com/spec.json", "", "${vault:k}");
    expect(looksLikeCredential(openapi[0]!.search)).toBe(false);
  });
});
