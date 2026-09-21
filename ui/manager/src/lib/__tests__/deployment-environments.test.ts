import { describe, it, expect } from "vitest";
import {
  deployedEnvironments,
  isAnyEnvironmentBusy,
  preferredChatEnvironment,
} from "../deployment-environments";
import type { EnvironmentStatus } from "@/lib/api/agents";

/**
 * The bug these exist for: the agents list, the chat picker and every chat
 * entry point read deployment status for PRODUCTION ONLY. An agent deployed to
 * `test` was labelled "Not deployed", was missing from the chat picker, and
 * could not be talked to — three symptoms of one wrong default, and it read to
 * the user as a broken agent.
 */
describe("deployedEnvironments", () => {
  it("returns only the environments that are READY", () => {
    const statuses: EnvironmentStatus[] = [
      { environment: "production", status: "NOT_FOUND" },
      { environment: "test", status: "READY" },
    ];
    expect(deployedEnvironments(statuses)).toEqual(["test"]);
  });

  it("returns both when both are live", () => {
    expect(
      deployedEnvironments([
        { environment: "test", status: "READY" },
        { environment: "production", status: "READY" },
      ]),
    ).toEqual(["production", "test"]);
  });

  it("orders by ENVIRONMENTS, not by response order", () => {
    // The statuses come from Promise.allSettled, so the response order races.
    // Sorting by the response would make the chips jump between renders.
    const reversed: EnvironmentStatus[] = [
      { environment: "test", status: "READY" },
      { environment: "production", status: "READY" },
    ];
    expect(deployedEnvironments(reversed)).toEqual(["production", "test"]);
  });

  it("treats IN_PROGRESS and ERROR as not live", () => {
    expect(
      deployedEnvironments([
        { environment: "production", status: "IN_PROGRESS" },
        { environment: "test", status: "ERROR" },
      ]),
    ).toEqual([]);
  });

  it("is empty for undefined — the query has not answered yet", () => {
    expect(deployedEnvironments(undefined)).toEqual([]);
  });
});

describe("isAnyEnvironmentBusy", () => {
  it("is true while any environment is mid-deploy", () => {
    expect(
      isAnyEnvironmentBusy([
        { environment: "production", status: "READY" },
        { environment: "test", status: "IN_PROGRESS" },
      ]),
    ).toBe(true);
  });

  it("is false when nothing is moving", () => {
    expect(isAnyEnvironmentBusy([{ environment: "production", status: "READY" }])).toBe(false);
    expect(isAnyEnvironmentBusy(undefined)).toBe(false);
  });
});

describe("preferredChatEnvironment", () => {
  it("prefers production when the agent is live there", () => {
    expect(preferredChatEnvironment(["production", "test"])).toBe("production");
  });

  it("falls back to wherever the agent actually runs", () => {
    // The whole point: a test-only agent must be reachable.
    expect(preferredChatEnvironment(["test"])).toBe("test");
  });

  it("defaults to production when nothing is live", () => {
    // Matches the backend's own @DefaultValue("production") rather than
    // inventing a third behaviour for a case the caller should not reach.
    expect(preferredChatEnvironment([])).toBe("production");
  });
});
