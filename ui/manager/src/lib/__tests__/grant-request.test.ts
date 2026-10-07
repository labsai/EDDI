import { describe, it, expect } from "vitest";
import { buildGrantRequestText, grantEndpoints } from "@/lib/grant-request";

describe("grantEndpoints", () => {
  it("builds one append call per plain vault secret, segments encoded", () => {
    expect(
      grantEndpoints(
        [
          { tenantId: "default", keyName: "a/b?c" },
          { tenantId: "default", keyName: "a/b?c" },
          { keyName: "no-tenant" },
          {},
        ],
        "agent 1",
      ),
    ).toEqual(["POST /secretstore/secrets/default/a%2Fb%3Fc/grant/agents/agent%201"]);
  });
});

describe("buildGrantRequestText", () => {
  it("puts every dry run before any change, and never a real token", () => {
    const text = buildGrantRequestText(
      ["POST /secretstore/secrets/default/k1/grant/agents/x", "POST /secretstore/secrets/default/k2/grant/agents/x"],
      "https://eddi.example/",
    );
    const curls = text.split("\n").filter((line) => line.startsWith("curl"));
    expect(curls).toEqual([
      'curl -X POST "https://eddi.example/secretstore/secrets/default/k1/grant/agents/x?dryRun=true" -H "Authorization: Bearer $EDDI_ADMIN_TOKEN"',
      'curl -X POST "https://eddi.example/secretstore/secrets/default/k2/grant/agents/x?dryRun=true" -H "Authorization: Bearer $EDDI_ADMIN_TOKEN"',
      'curl -X POST "https://eddi.example/secretstore/secrets/default/k1/grant/agents/x" -H "Authorization: Bearer $EDDI_ADMIN_TOKEN"',
      'curl -X POST "https://eddi.example/secretstore/secrets/default/k2/grant/agents/x" -H "Authorization: Bearer $EDDI_ADMIN_TOKEN"',
    ]);
  });
});
