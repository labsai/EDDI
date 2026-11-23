import { describe, expect, it } from "vitest";
import { teamsConfigProblems } from "../teams-channel";

const APP = "11111111-2222-3333-4444-555555555555";
const TENANT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

describe("teamsConfigProblems", () => {
  it("accepts a complete configuration", () => {
    expect(teamsConfigProblems({ appId: APP, tenantId: TENANT, connection: "teams-bot" })).toEqual([]);
  });

  it("names every missing or malformed field", () => {
    expect(teamsConfigProblems({})).toEqual(["appId", "tenantId", "connection"]);
    expect(
      teamsConfigProblems({
        appId: "not-a-guid",
        tenantId: TENANT,
        connection: "teams-bot",
        allowedTenantIds: `${TENANT}, nope`,
        conversationId: "19:abc@thread.tacv2;messageid=1",
      }),
    ).toEqual(["appId", "allowedTenantIds", "conversationId"]);
  });

  it("accepts further tenants and a channel id", () => {
    expect(
      teamsConfigProblems({
        appId: APP,
        tenantId: TENANT,
        connection: "teams-bot",
        allowedTenantIds: `${TENANT},${APP}`,
        conversationId: "19:general_1@thread.tacv2",
      }),
    ).toEqual([]);
  });
});
