import { describe, it, expect } from "vitest";
import { parseResourceUri, type BotDescriptor } from "@/lib/api/bots";
import { groupBotsByName } from "@/hooks/use-bots";

describe("parseResourceUri", () => {
  it("parses standard bot resource URI", () => {
    const result = parseResourceUri(
      "eddi://ai.labs.bot/botstore/bots/abc123?version=3"
    );
    expect(result.id).toBe("abc123");
    expect(result.version).toBe(3);
  });

  it("defaults to version 1 when not specified", () => {
    const result = parseResourceUri(
      "eddi://ai.labs.bot/botstore/bots/xyz789"
    );
    expect(result.id).toBe("xyz789");
    expect(result.version).toBe(1);
  });
});

describe("groupBotsByName", () => {
  it("groups bots by resource ID keeping latest version", () => {
    const bots: BotDescriptor[] = [
      {
        resource: "eddi://ai.labs.bot/botstore/bots/a?version=1",
        name: "Support Bot",
        description: "v1",
        createdOn: 1000,
        lastModifiedOn: 1000,
      },
      {
        resource: "eddi://ai.labs.bot/botstore/bots/a?version=3",
        name: "Support Bot",
        description: "v3",
        createdOn: 1000,
        lastModifiedOn: 3000,
      },
      {
        resource: "eddi://ai.labs.bot/botstore/bots/b?version=1",
        name: "FAQ Bot",
        description: "only version",
        createdOn: 2000,
        lastModifiedOn: 2000,
      },
    ];

    const result = groupBotsByName(bots);
    expect(result).toHaveLength(2);

    const supportBot = result.find((b) => b.id === "a");
    expect(supportBot?.version).toBe(3);
    expect(supportBot?.description).toBe("v3");
  });

  it("does NOT merge bots with same name but different IDs", () => {
    const bots: BotDescriptor[] = [
      {
        resource: "eddi://ai.labs.bot/botstore/bots/id1?version=1",
        name: "",
        description: "",
        createdOn: 1000,
        lastModifiedOn: 1000,
      },
      {
        resource: "eddi://ai.labs.bot/botstore/bots/id2?version=1",
        name: "",
        description: "",
        createdOn: 2000,
        lastModifiedOn: 2000,
      },
      {
        resource: "eddi://ai.labs.bot/botstore/bots/id3?version=1",
        name: "",
        description: "",
        createdOn: 3000,
        lastModifiedOn: 3000,
      },
    ];

    const result = groupBotsByName(bots);
    // Previously this would return 1 (all grouped under ""), now returns 3
    expect(result).toHaveLength(3);
  });

  it("sorts by lastModifiedOn descending", () => {
    const bots: BotDescriptor[] = [
      {
        resource: "eddi://ai.labs.bot/botstore/bots/a?version=1",
        name: "Old Bot",
        description: "",
        createdOn: 1000,
        lastModifiedOn: 1000,
      },
      {
        resource: "eddi://ai.labs.bot/botstore/bots/b?version=1",
        name: "New Bot",
        description: "",
        createdOn: 2000,
        lastModifiedOn: 5000,
      },
    ];

    const result = groupBotsByName(bots);
    expect(result[0]?.name).toBe("New Bot");
  });

  it("returns empty array for no bots", () => {
    expect(groupBotsByName([])).toEqual([]);
  });
});
