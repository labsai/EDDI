import { afterEach, describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { api, createsResources, SPACE_HEADER } from "../api-client";

const PATH = "/agentstore/agents";
const URL = `${window.location.origin}${PATH}`;

/**
 * Where a new resource lands follows the workspace in view: the Manager tells
 * the server through `X-EDDI-Space`, and only on the requests that create.
 */
describe("api X-EDDI-Space", () => {
  afterEach(() => api.setCreateSpace(null));

  function capture(method: "post" | "get" | "put") {
    const seen: { header: string | null } = { header: "unset" };
    server.use(
      http[method](URL, ({ request }) => {
        seen.header = request.headers.get(SPACE_HEADER);
        return HttpResponse.json({});
      })
    );
    return seen;
  }

  it("sends the chosen space on a POST", async () => {
    const seen = capture("post");
    api.setCreateSpace("team:engineering");

    await api.post(PATH, {});

    expect(seen.header).toBe("team:engineering");
  });

  it("sends nothing when no space is chosen, so the server's default applies", async () => {
    const seen = capture("post");

    await api.post(PATH, {});

    expect(seen.header).toBeNull();
  });

  it("only goes on requests that create resources, so a chat turn never trips over it", async () => {
    // A header naming a space the caller has left is refused by the server —
    // which, on a chat POST, meant a failed message.
    expect(createsResources("/agentstore/agents")).toBe(true);
    expect(createsResources("/llmstore/llms?x=1")).toBe(true);
    expect(createsResources("/administration/agents/setup-api")).toBe(true);
    expect(createsResources("/agents/abc/def")).toBe(false);
    expect(createsResources("/descriptorstore/descriptors/abc/shares")).toBe(false);
    expect(createsResources("/conversationstore/conversations/end")).toBe(false);
    expect(createsResources("/workspaces/notifications/read")).toBe(false);
  });

  it("never sends it on reads or updates, which do not create anything", async () => {
    // A header on a PUT would be ignored today, and misread the day an update
    // endpoint learns to move things.
    api.setCreateSpace("team:engineering");
    const onGet = capture("get");
    await api.get(PATH);
    const onPut = capture("put");
    await api.put(PATH, {});

    expect(onGet.header).toBeNull();
    expect(onPut.header).toBeNull();
  });
});
