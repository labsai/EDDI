/* The widget's URL shapes are a public contract (docs/chat-ui-embedding.md).
   These pin what react-router matched before it was removed, so an embedder's
   link keeps meaning the same thing. */

import { afterEach, describe, expect, it } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { DEFAULT_CHAT_PATH, parseChatPath, useChatLocation, useChatRoute } from "@/location";
import { ChatLocationProvider } from "@/components/ChatLocationProvider";

describe("parseChatPath", () => {
  it.each([
    ["/chat/managed/support/user-7", { intent: "support", userId: "user-7" }],
    ["/chat/production/agent-1", { environment: "production", agentId: "agent-1" }],
    ["/chat/test/agent-1/u-9", { environment: "test", agentId: "agent-1", userId: "u-9" }],
    ["/chat/demo/showcase", { environment: "demo", agentId: "showcase" }],
    // `managed` with two segments is an environment called "managed", as before:
    // only the four-segment shape is the managed route.
    ["/chat/managed/support", { environment: "managed", agentId: "support" }],
    // Literals match case-insensitively, params keep their case.
    ["/Chat/MANAGED/Sales/Bob", { intent: "Sales", userId: "Bob" }],
    ["/chat/Production/Agent-1", { environment: "Production", agentId: "Agent-1" }],
    // Trailing slashes are ignored.
    ["/chat/production/agent-1/", { environment: "production", agentId: "agent-1" }],
    ["/chat/managed/support/user-7//", { intent: "support", userId: "user-7" }],
    // Params are percent-decoded; a malformed escape stays as written.
    ["/chat/managed/support/jane%40example.com", { intent: "support", userId: "jane@example.com" }],
    ["/chat/production/a%2Fb", { environment: "production", agentId: "a/b" }],
    ["/chat/production/100%", { environment: "production", agentId: "100%" }],
  ])("%s", (path, params) => {
    expect(parseChatPath(path)).toEqual(params);
  });

  it.each([
    "/",
    "/chat",
    "/chat/",
    "/chat/production",
    "/chat//agent-1",
    "/chat/production/agent-1/u/extra",
    "/manage/chat/production/agent-1",
    "chat/production/agent-1",
  ])("%s matches nothing (the catch-all)", (path) => {
    expect(parseChatPath(path)).toBeNull();
  });
});

function Probe() {
  const { location } = useChatLocation();
  const { params, searchParams, replaceSearch } = useChatRoute();
  return (
    <>
      <span data-testid="where">{location.pathname + location.search}</span>
      <span data-testid="params">{JSON.stringify(params)}</span>
      <span data-testid="user">{searchParams.get("userId") ?? ""}</span>
      <button
        data-testid="strip"
        onClick={() => {
          const next = new URLSearchParams(searchParams);
          next.delete("token");
          replaceSearch(next);
        }}
      />
    </>
  );
}

describe("ChatLocationProvider", () => {
  afterEach(() => {
    window.history.replaceState(null, "", "/");
  });

  it("memory mode reads the path and query it was given", () => {
    render(
      <ChatLocationProvider initialPath="/chat/production/agent-1?userId=u-1&theme=dark">
        <Probe />
      </ChatLocationProvider>,
    );
    expect(screen.getByTestId("params").textContent).toBe(
      JSON.stringify({ environment: "production", agentId: "agent-1" }),
    );
    expect(screen.getByTestId("user").textContent).toBe("u-1");
  });

  it("browser mode follows window.location and rewrites the query in place", () => {
    window.history.pushState(null, "", "/chat/managed/support/u-7?token=secret&theme=dark#x");
    const before = window.history.length;
    render(
      <ChatLocationProvider>
        <Probe />
      </ChatLocationProvider>,
    );
    expect(screen.getByTestId("params").textContent).toBe(
      JSON.stringify({ intent: "support", userId: "u-7" }),
    );
    act(() => screen.getByTestId("strip").click());
    // No history entry is added (the token must not be one Back away), the path
    // and hash are kept, and only the token is gone.
    expect(window.history.length).toBe(before);
    expect(window.location.pathname + window.location.search + window.location.hash).toBe(
      "/chat/managed/support/u-7?theme=dark#x",
    );
    expect(screen.getByTestId("where").textContent).toBe("/chat/managed/support/u-7?theme=dark");
  });

  it("browser mode follows back and forward", () => {
    window.history.replaceState(null, "", "/chat/production/a");
    render(
      <ChatLocationProvider>
        <Probe />
      </ChatLocationProvider>,
    );
    act(() => {
      window.history.pushState(null, "", "/chat/production/b");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(screen.getByTestId("where").textContent).toBe("/chat/production/b");
  });
});

describe("no router", () => {
  it("nothing imports react-router any more", () => {
    // 92 KB of the widget's first load (frontend unification plan, §1.2).
    const pkg = JSON.parse(readFileSync(resolve(__dirname, "../package.json"), "utf8"));
    expect(Object.keys(pkg.dependencies ?? {})).not.toContain("react-router-dom");
    expect(DEFAULT_CHAT_PATH).toBe("/chat/production/default");
  });
});
