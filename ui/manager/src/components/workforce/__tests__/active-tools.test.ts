import { describe, expect, it } from "vitest";
import {
  buildToolGroups,
  collapseToolGroups,
  distinctToolUris,
  shortToolId,
  toolResourceSlug,
  type ToolGroup,
} from "@/components/workforce/active-tools";

const API_A = "eddi://ai.labs.apicalls/apicallstore/apicalls/6ad75fdf8741aa00bb11cc22?version=1";
const API_B = "eddi://ai.labs.apicalls/apicallstore/apicalls/6ad75fe08742dd33ee44ff55?version=3";
const MCP = "eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/6ad760118743aa00bb11cc99?version=2";
const OTHER = "eddi://ai.labs.unknown/somestore/things/6ad760228744aa00bb11cc77?version=1";

const named: Record<string, string> = { [API_A]: "Get program content", [API_B]: "Send event", [MCP]: "Docs search" };
const nameOf = (uri: string) => named[uri];

const labels = (groups: ToolGroup[]) => groups.map((g) => [g.kind, g.tools.map((t) => t.label || t.feature)]);

describe("buildToolGroups", () => {
  it("groups tool resources by type, named from their descriptors, with the full URI as tooltip", () => {
    const groups = buildToolGroups({ toolUris: [MCP, API_A, API_B], nameOf });

    expect(labels(groups)).toEqual([
      ["apicalls", ["Get program content", "Send event"]],
      ["mcpcalls", ["Docs search"]],
    ]);
    expect(groups[0]!.tools.map((t) => t.title)).toEqual([API_A, API_B]);
  });

  it("links each known resource to its editor at the referenced version", () => {
    const [api, mcp] = buildToolGroups({ toolUris: [API_B, MCP], nameOf });

    expect(api!.tools[0]!.href).toBe("/manage/resources/apicalls/6ad75fe08742dd33ee44ff55?version=3");
    expect(mcp!.tools[0]!.href).toBe("/manage/resources/mcpcalls/6ad760118743aa00bb11cc99?version=2");
  });

  it("puts a resource of an unknown type under 'resource', without a link", () => {
    const [group] = buildToolGroups({ toolUris: [OTHER], nameOf: () => undefined });

    expect(group!.kind).toBe("resource");
    expect(group!.tools[0]!.href).toBeUndefined();
  });

  it("lists a repeated URI once", () => {
    const [group] = buildToolGroups({ toolUris: [API_A, API_A, ` ${API_A} `], nameOf });

    expect(group!.tools.map((t) => t.key)).toEqual([API_A]);
  });

  it("falls back to a short id, never the raw URI, once a name cannot be read", () => {
    const [group] = buildToolGroups({ toolUris: [API_A], nameOf: () => "  ", isLoading: () => false });

    expect(group!.tools[0]!.label).toBe("6ad75f…cc22");
    expect(group!.tools[0]!.label).not.toContain("eddi://");
    expect(group!.tools[0]!.loading).toBe(false);
  });

  it("marks a tool whose name is still loading, so no short id flickers first", () => {
    const [group] = buildToolGroups({ toolUris: [API_A], nameOf: () => undefined, isLoading: () => true });

    expect(group!.tools[0]!.loading).toBe(true);
  });

  it("adds built-ins and enabled features in their own groups, after the resources", () => {
    const groups = buildToolGroups({
      toolUris: [API_A],
      nameOf,
      builtIns: ["calculator", "calculator", " "],
      httpCallTools: true,
      mcpCallTools: false,
      memoryTools: true,
    });

    expect(labels(groups)).toEqual([
      ["apicalls", ["Get program content"]],
      ["builtin", ["calculator"]],
      ["feature", ["httpCalls", "memory"]],
    ]);
  });

  it("returns no groups when nothing is configured", () => {
    expect(buildToolGroups({ toolUris: null, nameOf })).toEqual([]);
  });
});

describe("collapseToolGroups", () => {
  const groups = buildToolGroups({
    toolUris: [API_A, API_B, MCP],
    nameOf,
    builtIns: ["calculator", "datetime"],
    memoryTools: true,
  });

  it("keeps everything, and reports nothing hidden, when the total fits", () => {
    expect(collapseToolGroups(groups, 6)).toEqual({ groups, hidden: 0 });
    expect(collapseToolGroups(groups, 10).hidden).toBe(0);
  });

  it("shows the first tools across groups in order and counts the rest", () => {
    const { groups: shown, hidden } = collapseToolGroups(groups, 4);

    expect(labels(shown)).toEqual([
      ["apicalls", ["Get program content", "Send event"]],
      ["mcpcalls", ["Docs search"]],
      ["builtin", ["calculator"]],
    ]);
    expect(hidden).toBe(2);
  });
});

describe("helpers", () => {
  it("shortens only long ids", () => {
    expect(shortToolId("6ad75fdf8741aa00bb11cc22")).toBe("6ad75f…cc22");
    expect(shortToolId("short")).toBe("short");
  });

  it("drops empty entries and duplicates, keeping the order", () => {
    expect(distinctToolUris([API_B, "", API_A, API_B])).toEqual([API_B, API_A]);
    expect(distinctToolUris(undefined)).toEqual([]);
  });

  it("maps a URI's authority to the resource slug", () => {
    expect(toolResourceSlug(API_A)).toBe("apicalls");
    expect(toolResourceSlug(MCP)).toBe("mcpcalls");
    expect(toolResourceSlug(OTHER)).toBeNull();
    expect(toolResourceSlug("not a uri")).toBeNull();
  });
});
