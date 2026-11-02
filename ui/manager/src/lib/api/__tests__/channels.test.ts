import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import {
  parseChannelResourceUri,
  createDefaultTarget,
  DEFAULT_OBSERVE_CONFIG,
  parseChannelWarnings,
  createChannel,
  updateChannel,
  type ChannelIntegrationConfiguration,
} from "@/lib/api/channels";

describe("parseChannelResourceUri", () => {
  it("parses eddi:// resource URIs", () => {
    const result = parseChannelResourceUri(
      "eddi://ai.labs.channel/channelstore/channels/abc123?version=3",
    );
    expect(result).toEqual({ id: "abc123", version: 3 });
  });

  it("parses path-based Location headers", () => {
    const result = parseChannelResourceUri(
      "/channelstore/channels/new-ch-42?version=1",
    );
    expect(result).toEqual({ id: "new-ch-42", version: 1 });
  });

  it("defaults version to 1 if missing", () => {
    const result = parseChannelResourceUri(
      "eddi://ai.labs.channel/channelstore/channels/xyz",
    );
    expect(result).toEqual({ id: "xyz", version: 1 });
  });

  it("handles version=0 correctly", () => {
    const result = parseChannelResourceUri(
      "eddi://ai.labs.channel/channelstore/channels/test?version=0",
    );
    expect(result).toEqual({ id: "test", version: 0 });
  });

  it("handles UUIDs in the path", () => {
    const result = parseChannelResourceUri(
      "eddi://ai.labs.channel/channelstore/channels/550e8400-e29b-41d4-a716-446655440000?version=7",
    );
    expect(result).toEqual({
      id: "550e8400-e29b-41d4-a716-446655440000",
      version: 7,
    });
  });

  it("parses an HTTP URL", () => {
    const result = parseChannelResourceUri(
      "http://localhost:7070/channelstore/channels/ch42?version=3",
    );
    expect(result.id).toBe("ch42");
    expect(result.version).toBe(3);
  });
});

describe("createDefaultTarget", () => {
  it("creates a target with AGENT type by default", () => {
    const target = createDefaultTarget("agent-1");
    expect(target.name).toBe("default");
    expect(target.type).toBe("AGENT");
    expect(target.targetId).toBe("agent-1");
    expect(target.triggers).toEqual([]);
    expect(target.observeMode).toBe(false);
    expect(target.observeConfig).toBeNull();
  });

  it("creates a target with empty targetId when none provided", () => {
    const target = createDefaultTarget();
    expect(target.targetId).toBe("");
  });
});

describe("DEFAULT_OBSERVE_CONFIG", () => {
  it("has expected default values", () => {
    expect(DEFAULT_OBSERVE_CONFIG.cooldownSeconds).toBe(60);
    expect(DEFAULT_OBSERVE_CONFIG.maxDailyResponses).toBe(50);
    expect(DEFAULT_OBSERVE_CONFIG.maxCostPerDay).toBe(5.0);
    expect(DEFAULT_OBSERVE_CONFIG.triggerKeywords).toEqual([]);
    expect(DEFAULT_OBSERVE_CONFIG.triggerMimeTypes).toEqual([]);
  });
});

describe("channel save warnings (X-Eddi-Warnings)", () => {
  const config: ChannelIntegrationConfiguration = {
    name: "Invoices",
    channelType: "email",
    platformConfig: { mode: "trigger", transport: "imap" },
    targets: [createDefaultTarget("agent1")],
    defaultTargetName: "default",
  };

  it("splits the header into codes, dropping blanks and repeats", () => {
    expect(parseChannelWarnings(null)).toEqual([]);
    expect(parseChannelWarnings("")).toEqual([]);
    expect(parseChannelWarnings(" email.open-trigger , ,email.tools-without-approvals,email.open-trigger")).toEqual([
      "email.open-trigger",
      "email.tools-without-approvals",
    ]);
  });

  it("returns the warnings of a create next to its location", async () => {
    server.use(
      http.post("*/channelstore/channels", () =>
        new HttpResponse(null, {
          status: 201,
          headers: {
            Location: "/channelstore/channels/abc?version=1",
            "X-Eddi-Warnings": "email.open-trigger,email.tools-without-approvals",
          },
        }),
      ),
    );
    await expect(createChannel(config)).resolves.toEqual({
      location: "/channelstore/channels/abc?version=1",
      warnings: ["email.open-trigger", "email.tools-without-approvals"],
    });
  });

  it("returns no warnings when the header is absent, and survives an update without a Location", async () => {
    server.use(http.put("*/channelstore/channels/:id", () => new HttpResponse(null, { status: 200 })));
    await expect(updateChannel("abc", 1, config)).resolves.toEqual({ location: "", warnings: [] });
  });

  it("returns the warnings of an update", async () => {
    server.use(
      http.put("*/channelstore/channels/:id", () =>
        new HttpResponse(null, {
          status: 200,
          headers: { Location: "/channelstore/channels/abc?version=2", "X-Eddi-Warnings": "email.open-trigger" },
        }),
      ),
    );
    await expect(updateChannel("abc", 1, config)).resolves.toEqual({
      location: "/channelstore/channels/abc?version=2",
      warnings: ["email.open-trigger"],
    });
  });
});
