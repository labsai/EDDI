import { describe, it, expect } from "vitest";
import { ApiClientError } from "@/lib/api-client";
import { toAdminAction, type AdminAction, type AuditEntry } from "@/lib/api/audit";
import {
  adminActionsFailure,
  filterAdminActions,
  olderPagesCanMatch,
  statusTone,
} from "@/lib/admin-actions";

const NOW = Date.parse("2026-10-04T12:00:00Z");
const at = (hoursAgo: number) => new Date(NOW - hoursAgo * 3_600_000).toISOString();
const action = (id: string, method: string, hoursAgo: number): AdminAction => ({
  id,
  actor: "alice",
  method,
  path: "/x",
  endpoint: null,
  status: 200,
  timestamp: at(hoursAgo),
});

describe("toAdminAction", () => {
  const entry = {
    id: "a1",
    userId: "alice",
    taskId: "ai.labs.admin",
    taskType: "admin",
    input: { method: "post", path: "/administration/production/deploy/x", resource: "RestAgentAdministration#deployAgent" },
    output: { status: 202 },
    actions: ["ADMIN_POST"],
    timestamp: "2026-10-04T10:00:00Z",
  } as unknown as AuditEntry;

  it("reads the caller, method, path, endpoint and status the filter records", () => {
    expect(toAdminAction(entry)).toEqual({
      id: "a1",
      actor: "alice",
      method: "POST",
      path: "/administration/production/deploy/x",
      endpoint: "RestAgentAdministration#deployAgent",
      status: 202,
      timestamp: "2026-10-04T10:00:00.000Z",
    });
  });

  it("falls back to the ADMIN_<method> action, an anonymous actor, and fractional epoch seconds", () => {
    const sparse = { ...entry, userId: null, input: {}, output: null, timestamp: 1759572000.5 } as unknown as AuditEntry;
    const read = toAdminAction(sparse);
    expect(read.method).toBe("POST");
    expect(read.actor).toBe("anonymous");
    expect(read.status).toBeNull();
    expect(read.timestamp).toBe(new Date(1759572000500).toISOString());
  });
});

describe("filterAdminActions", () => {
  const rows = [action("1", "POST", 0.5), action("2", "DELETE", 5), action("3", "POST", 30), action("4", "PUT", 24 * 20)];

  it("keeps everything without filters", () => {
    expect(filterAdminActions(rows, { method: "", window: "any" }, NOW)).toHaveLength(4);
  });

  it("narrows by method", () => {
    expect(filterAdminActions(rows, { method: "POST", window: "any" }, NOW).map((r) => r.id)).toEqual(["1", "3"]);
  });

  it("narrows by time window, both bounds of a day", () => {
    expect(filterAdminActions(rows, { method: "", window: "hour" }, NOW).map((r) => r.id)).toEqual(["1"]);
    expect(filterAdminActions(rows, { method: "", window: "day" }, NOW).map((r) => r.id)).toEqual(["1", "2"]);
    expect(filterAdminActions(rows, { method: "", window: "month" }, NOW).map((r) => r.id)).toEqual(["1", "2", "3", "4"]);
  });

  it("keeps a row whose time does not parse rather than hiding it", () => {
    const odd = { ...action("5", "POST", 0), timestamp: "not-a-time" };
    expect(filterAdminActions([odd], { method: "", window: "hour" }, NOW)).toHaveLength(1);
  });
});

describe("olderPagesCanMatch", () => {
  it("is true while the oldest loaded row is still inside the window", () => {
    expect(olderPagesCanMatch([action("1", "POST", 1)], "day", NOW)).toBe(true);
    expect(olderPagesCanMatch([action("1", "POST", 1), action("2", "POST", 48)], "day", NOW)).toBe(false);
    expect(olderPagesCanMatch([action("1", "POST", 48)], "any", NOW)).toBe(true);
  });
});

describe("adminActionsFailure / statusTone", () => {
  it("tells 'not an admin' and 'not this version' apart from a real failure", () => {
    expect(adminActionsFailure(new ApiClientError(403, "Forbidden"))).toBe("forbidden");
    expect(adminActionsFailure(new ApiClientError(401, "Unauthorized"))).toBe("forbidden");
    expect(adminActionsFailure(new ApiClientError(404, "Not Found"))).toBe("unsupported");
    expect(adminActionsFailure(new ApiClientError(500, "boom"))).toBe("error");
    expect(adminActionsFailure(new Error("network"))).toBe("error");
  });

  it("colours 2xx, refusals and failures differently", () => {
    expect(statusTone(204)).toBe("ok");
    expect(statusTone(403)).toBe("refused");
    expect(statusTone(500)).toBe("failed");
    expect(statusTone(null)).toBe("unknown");
  });
});
