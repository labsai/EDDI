import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@eddi/app-kit/test/mocks/server";
import { carryOverVaultGrants } from "../carry-grants";

/**
 * The new operator agent an upgrade builds inherits exactly the grants its
 * predecessor held — by the one-agent append, never by widening.
 */

const OLD = "aaaaaaaaaaaaaaaaaaaaaa01";
const NEW = "bbbbbbbbbbbbbbbbbbbbbb02";

function secret(tenantId: string, keyName: string, allowedAgents: string[]) {
  return { tenantId, keyName, allowedAgents, createdAt: "", lastAccessedAt: null, lastRotatedAt: null, checksum: "x", description: null };
}

let appends: string[];

function serve(options: {
  listing?: ReturnType<typeof secret>[] | number;
  issues?: { tenantId: string; keyName: string }[];
  lookups?: Record<string, ReturnType<typeof secret>>;
  appendStatus?: number;
}) {
  server.use(
    http.get("*/secretstore/secrets/default", () =>
      typeof options.listing === "number"
        ? HttpResponse.json({ error: "no" }, { status: options.listing })
        : HttpResponse.json(options.listing ?? []),
    ),
    http.get("*/administration/:env/deploy/:agentId/preflight", () =>
      HttpResponse.json({
        agentId: NEW,
        version: 1,
        enforcement: "ENFORCE",
        checked: true,
        ready: false,
        grantIssues: (options.issues ?? []).map((i) => ({ ...i, reference: `\${vault:${i.tenantId}/${i.keyName}}` })),
      }),
    ),
    http.get("*/secretstore/secrets/:tenant/:key", ({ params }) => {
      const found = options.lookups?.[`${String(params.tenant)}/${String(params.key)}`];
      return found ? HttpResponse.json(found) : HttpResponse.json({ error: "not found" }, { status: 404 });
    }),
    http.post("*/secretstore/secrets/:tenant/:key/grant/agents/:agentId", ({ params }) => {
      appends.push(`${String(params.tenant)}/${String(params.key)}->${String(params.agentId)}`);
      if (options.appendStatus) return HttpResponse.json({ error: "forbidden" }, { status: options.appendStatus });
      return HttpResponse.json({ changed: true, allowedAgents: [OLD, NEW] });
    }),
  );
}

const run = () => carryOverVaultGrants({ fromAgentId: OLD, toAgentId: NEW, version: 1, environment: "production" });

describe("carryOverVaultGrants", () => {
  beforeEach(() => {
    server.resetHandlers();
    appends = [];
  });

  it("appends the new agent to every default-tenant grant naming the old one, and to nothing else", async () => {
    serve({
      listing: [
        secret("default", "llm-key", [OLD]),
        secret("default", "other-key", ["cccccccccccccccccccccc03"]),
        secret("default", "open-key", ["*"]),
        secret("default", "empty-key", []),
      ],
    });
    expect(await run()).toEqual(["default/llm-key"]);
    expect(appends).toEqual([`default/llm-key->${NEW}`]);
  });

  it("finds a grant in another tenant through the preflight, but only when it names the old agent", async () => {
    serve({
      listing: [],
      issues: [
        { tenantId: "space-x", keyName: "held" },
        { tenantId: "space-x", keyName: "not-held" },
      ],
      lookups: {
        "space-x/held": secret("space-x", "held", [OLD]),
        "space-x/not-held": secret("space-x", "not-held", ["cccccccccccccccccccccc03"]),
      },
    });
    expect(await run()).toEqual(["space-x/held"]);
    expect(appends).toEqual([`space-x/held->${NEW}`]);
  });

  it("carries nothing and does not throw when the caller may not change grants", async () => {
    serve({ listing: [secret("default", "llm-key", [OLD])], appendStatus: 403 });
    expect(await run()).toEqual([]);
  });

  it("carries nothing and does not throw when the vault cannot be listed", async () => {
    serve({ listing: 503 });
    expect(await run()).toEqual([]);
    expect(appends).toEqual([]);
  });

  it("does nothing when there is no predecessor to inherit from", async () => {
    serve({ listing: [secret("default", "llm-key", [OLD])] });
    expect(await carryOverVaultGrants({ fromAgentId: "", toAgentId: NEW, version: 1, environment: "production" })).toEqual([]);
    expect(await carryOverVaultGrants({ fromAgentId: NEW, toAgentId: NEW, version: 1, environment: "production" })).toEqual([]);
    expect(appends).toEqual([]);
  });
});
