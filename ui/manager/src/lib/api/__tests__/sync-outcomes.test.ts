import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import {
  executeSync,
  executeSyncBatch,
  importAgentUpgrade,
  importAgentMerge,
  exportAgentSelective,
  hasFailures,
  type UpgradeResult,
} from "../backup";

const ORIGIN = window.location.origin;
const SYNC = `${ORIGIN}/backup/import/sync`;
const SYNC_BATCH = `${ORIGIN}/backup/import/sync/batch`;
const IMPORT = `${ORIGIN}/backup/import`;

const zip = () => new File(["zip"], "agent.zip", { type: "application/zip" });

const result = (over: Partial<UpgradeResult> = {}): UpgradeResult => ({
  agentUri: "eddi://ai.labs.agent/agentstore/agents/a1?version=2",
  agentUpdated: true,
  updated: 3,
  created: 1,
  skipped: 0,
  failures: [],
  ...over,
});

const failure = {
  sourceId: "llm-1",
  resourceType: "langchain",
  name: "Main model",
  reason: "target rejected the configuration",
};

/**
 * Upgrade and sync answer three different 2xx codes, and EDDI's own javadoc
 * says so: "All three are 2xx, so a client must branch on the status code
 * rather than on response.ok."
 *
 * These functions used to return `Promise<void>` (sync) or the `Location`
 * header alone (upgrade), so 201 wrote-something, 200 already-identical and 207
 * some-resources-failed were indistinguishable, and the per-resource failure
 * list went in the bin. A half-applied sync was reported as a clean one.
 */
describe("executeSync", () => {
  const run = () => executeSync("https://remote", "a1", null, "a1", null, null, "");

  it("reads 207 as partial and keeps the failures", async () => {
    server.use(
      http.post(SYNC, () =>
        HttpResponse.json(result({ failures: [failure] }), { status: 207 }),
      ),
    );

    const execution = await run();

    expect(execution.outcome).toBe("partial");
    expect(execution.result?.failures).toEqual([failure]);
  });

  it("reads 201 as having written something", async () => {
    server.use(http.post(SYNC, () => HttpResponse.json(result(), { status: 201 })));
    expect((await run()).outcome).toBe("wrote");
  });

  it("reads 200 as already identical", async () => {
    // Distinct from "wrote" on purpose: no agent version was burned, and
    // telling an operator their sync landed when nothing changed sends them
    // looking for a difference that does not exist.
    server.use(
      http.post(SYNC, () =>
        HttpResponse.json(result({ agentUpdated: false, updated: 0, created: 0 }), {
          status: 200,
        }),
      ),
    );
    expect((await run()).outcome).toBe("identical");
  });

  it("falls back to the body when a backend answers 200 with failures", async () => {
    // Belt and braces for a deployment that predates the status split. The
    // failure list is the authority either way.
    server.use(
      http.post(SYNC, () =>
        HttpResponse.json(result({ failures: [failure] }), { status: 200 }),
      ),
    );
    expect((await run()).outcome).toBe("partial");
  });
});

describe("importAgentUpgrade", () => {
  it("reports a partial upgrade rather than just its Location header", async () => {
    server.use(
      http.post(IMPORT, () =>
        HttpResponse.json(result({ failures: [failure] }), {
          status: 207,
          headers: { Location: "/agentstore/agents/a1?version=2" },
        }),
      ),
    );

    const execution = await importAgentUpgrade(zip(), "a1");

    expect(execution.outcome).toBe("partial");
    expect(execution.location).toBe("/agentstore/agents/a1?version=2");
    expect(execution.result?.failures).toHaveLength(1);
  });
});

describe("executeSyncBatch", () => {
  it("flags a batch where one mapping failed", async () => {
    server.use(
      http.post(SYNC_BATCH, () =>
        HttpResponse.json(
          [
            { sourceAgentId: "a1", targetAgentId: "a1", result: result(), error: null },
            { sourceAgentId: "a2", targetAgentId: null, result: null, error: "no such agent" },
          ],
          { status: 207 },
        ),
      ),
    );

    const execution = await executeSyncBatch("https://remote", [], "");

    expect(execution.partial).toBe(true);
    expect(execution.results[1]?.error).toBe("no such agent");
  });

  it("keeps the per-agent reasons when the whole batch failed", async () => {
    // EDDI answers 500 when EVERY mapping failed, and the body is still the
    // per-agent list. Throwing on the status would discard the reasons in the
    // one case where they matter most.
    server.use(
      http.post(SYNC_BATCH, () =>
        HttpResponse.json(
          [{ sourceAgentId: "a1", targetAgentId: null, result: null, error: "unreachable" }],
          { status: 500 },
        ),
      ),
    );

    const execution = await executeSyncBatch("https://remote", [], "");

    expect(execution.partial).toBe(true);
    expect(execution.results[0]?.error).toBe("unreachable");
  });

  it("survives a body that is not a list", async () => {
    // These calls bypass `ApiClient` — they send zip bodies and custom sync
    // headers — so they also miss its guard against a non-JSON 2xx. A reverse
    // proxy answering 200 with an HTML page used to die here on
    // `results.some is not a function`, an unhandled TypeError in place of an
    // outcome the caller could report.
    server.use(http.post(SYNC_BATCH, () => HttpResponse.text("<html>nope</html>")));

    const execution = await executeSyncBatch("https://remote", [], "");

    expect(execution.results).toEqual([]);
    expect(execution.partial).toBe(false);
  });

  it("reports a clean batch as not partial", async () => {
    server.use(
      http.post(SYNC_BATCH, () =>
        HttpResponse.json([
          { sourceAgentId: "a1", targetAgentId: "a1", result: result(), error: null },
        ]),
      ),
    );
    expect((await executeSyncBatch("https://remote", [], "")).partial).toBe(false);
  });
});

describe("importAgentMerge", () => {
  it("reports schedules the selection left out", async () => {
    // `selectedResources` is one flat list over every preview row, so naming
    // extension ids alone silently drops every schedule in the archive. This
    // header is the only place that is stated.
    server.use(
      http.post(IMPORT, () =>
        HttpResponse.json({}, { headers: { "X-Schedules-Skipped": "2" } }),
      ),
    );

    expect((await importAgentMerge(zip(), ["ext-1"])).schedulesSkipped).toBe(2);
  });

  it("reports null when the header is absent, which is the usual case", async () => {
    server.use(http.post(IMPORT, () => HttpResponse.json({})));
    expect((await importAgentMerge(zip())).schedulesSkipped).toBeNull();
  });
});

describe("exportAgentSelective", () => {
  /** The query string one export put on the wire. */
  async function capture(
    run: () => Promise<void>,
  ): Promise<URLSearchParams> {
    let search = new URLSearchParams();
    server.use(
      http.post(`${ORIGIN}/backup/export/:agentId`, ({ request }) => {
        search = new URL(request.url).searchParams;
        return HttpResponse.json({});
      }),
    );
    await run();
    return search;
  }

  it("omits the snippet and schedule parameters when nothing is said about them", async () => {
    // Omitting means "export every referenced snippet and every schedule",
    // which is the behaviour every existing caller relies on.
    const search = await capture(() => exportAgentSelective("a1", 1, ["ext-1"]));

    expect(search.has("selectedSnippets")).toBe(false);
    expect(search.has("selectedSchedules")).toBe(false);
  });

  it("sends an EMPTY parameter to export none of them", async () => {
    // The inversion that makes `[]` and `undefined` opposites here: passing the
    // parameter empty means "none", and dropping it would export all of them.
    const search = await capture(() =>
      exportAgentSelective("a1", 1, ["ext-1"], { snippets: [], schedules: [] }),
    );

    expect(search.get("selectedSnippets")).toBe("");
    expect(search.get("selectedSchedules")).toBe("");
  });

  it("sends the listed ids", async () => {
    const search = await capture(() =>
      exportAgentSelective("a1", 1, [], { schedules: ["s1", "s2"] }),
    );

    expect(search.get("selectedSchedules")).toBe("s1,s2");
  });
});

describe("hasFailures", () => {
  it("is false for a clean result, and for no result at all", () => {
    expect(hasFailures(result())).toBe(false);
    expect(hasFailures(null)).toBe(false);
    expect(hasFailures(undefined)).toBe(false);
  });

  it("is true once a resource failed", () => {
    expect(hasFailures(result({ failures: [failure] }))).toBe(true);
  });
});
