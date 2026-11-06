import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { ThemeProvider } from "@eddi/ui";
import { AuthContext, GUEST_CONTEXT, type AuthContextValue } from "@eddi/app-kit/components/auth/auth-context";
import { createTestQueryClient, userEvent } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import type { UserMemoryEntry } from "@eddi/app-kit/lib/api/user-memory";
import { WorkforceMemory } from "@/pages/workforce/workforce-memory";

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}));

/**
 * "What I remember about you" (T2.4): the signed-in person's own memory — who
 * saved what, who may read it, search, forget one, forget everything.
 */

const ME = "alice";

const ENTRIES: UserMemoryEntry[] = [
  {
    id: "m-private",
    userId: ME,
    key: "preferred_language",
    value: "German",
    category: "preference",
    visibility: "self",
    sourceAgentId: "agent-support",
    updatedAt: "2026-11-01T10:00:00Z",
  },
  {
    id: "m-team",
    userId: ME,
    key: "project_codename",
    value: { name: "Falcon", since: 2025 },
    category: "fact",
    visibility: "group",
    sourceAgentId: "agent-unlisted",
    groupIds: ["grp-launch"],
    updatedAt: "2026-11-03T10:00:00Z",
  },
  {
    id: "m-global",
    userId: ME,
    key: "timezone",
    value: "Europe/Vienna",
    category: "preference",
    visibility: "global",
    updatedAt: "2026-11-02T10:00:00Z",
  },
  {
    id: "m-gdpr",
    userId: ME,
    key: "_gdpr_restricted",
    value: true,
    category: "context",
    visibility: "global",
  },
];

function signedIn(username = ME): AuthContextValue {
  return {
    ...GUEST_CONTEXT,
    method: "keycloak",
    roles: ["eddi-user"],
    user: { username, firstName: "", lastName: "", email: "", fullName: "" },
  };
}

function renderMemory(auth: AuthContextValue = signedIn()) {
  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={["/workforce/memory"]}>
        <QueryClientProvider client={createTestQueryClient()}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <WorkforceMemory />
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

/** Serve `entries` for the signed-in user only, as the backend's ownership check does. */
function serveMemories(entries: UserMemoryEntry[], seen: string[] = []) {
  let current = [...entries];
  server.use(
    http.get("*/usermemorystore/memories/:userId", ({ params }) => {
      seen.push(String(params.userId));
      if (params.userId !== ME) return HttpResponse.json({ error: "forbidden" }, { status: 403 });
      return HttpResponse.json(current);
    }),
    http.delete("*/usermemorystore/memories/entry/:entryId", ({ params }) => {
      current = current.filter((e) => e.id !== params.entryId);
      return new HttpResponse(null, { status: 204 });
    }),
    http.delete("*/usermemorystore/memories/:userId", ({ params }) => {
      if (params.userId !== ME) return HttpResponse.json({ error: "forbidden" }, { status: 403 });
      current = current.filter((e) => e.key.startsWith("_gdpr_"));
      return new HttpResponse(null, { status: 204 });
    }),
    http.get("*/catalog/agents", () =>
      HttpResponse.json([{ agentId: "agent-support", name: "Support Bot", callerLevel: "USE" }]),
    ),
    http.get("*/catalog/groups", () =>
      HttpResponse.json([
        { groupId: "grp-launch", version: 1, name: "Launch Team", members: [], callerLevel: "USE" },
      ]),
    ),
  );
}

beforeEach(() => {
  vi.mocked(toast.success).mockClear();
  vi.mocked(toast.error).mockClear();
});

describe("WorkforceMemory — listing", () => {
  it("reads the signed-in user's own memory, newest first, and leaves the GDPR rows out", async () => {
    const seen: string[] = [];
    serveMemories(ENTRIES, seen);
    renderMemory();

    const items = await screen.findAllByTestId("memory-item");
    expect(seen).toContain(ME);
    expect(items.map((i) => within(i).getByTestId("memory-value").textContent)).toEqual([
      '{"name":"Falcon","since":2025}',
      "Europe/Vienna",
      "German",
    ]);
    expect(screen.queryByText(/_gdpr_/)).toBeNull();
    expect(screen.getByTestId("memory-reserved-note")).toBeInTheDocument();
    expect(screen.getByTestId("memory-count")).toHaveTextContent("Entries: 3");
  });

  it("says which agent saved each entry and who may read it", async () => {
    serveMemories(ENTRIES);
    renderMemory();

    const [team, global, priv] = await screen.findAllByTestId("memory-item");
    // A listed agent by name, an unlisted one by id, a legacy entry without one.
    expect(within(priv!).getByTestId("memory-agent")).toHaveTextContent("Saved by Support Bot");
    expect(within(team!).getByTestId("memory-agent")).toHaveTextContent("Saved by agent-unlisted");
    expect(within(global!).getByTestId("memory-agent")).toHaveTextContent("Saving agent not recorded");

    expect(priv).toHaveAttribute("data-visibility", "self");
    expect(within(priv!).getByTestId("memory-visibility")).toHaveTextContent("Private");
    expect(within(priv!).getByTestId("memory-scope")).toHaveTextContent("Only the agent that saved it uses it");
    expect(within(team!).getByTestId("memory-visibility")).toHaveTextContent("Team");
    expect(within(team!).getByTestId("memory-scope")).toHaveTextContent("Used by the agents of Launch Team");
    expect(within(global!).getByTestId("memory-visibility")).toHaveTextContent("All agents");
    expect(within(global!).getByTestId("memory-scope")).toHaveTextContent("Used by all your agents");
  });

  it("shows a stored value as text, never as markup", async () => {
    serveMemories([
      { ...ENTRIES[0]!, value: "<img src=x onerror=alert(1)> **bold**" },
    ]);
    const { container } = renderMemory();
    const value = await screen.findByTestId("memory-value");
    expect(value.textContent).toBe("<img src=x onerror=alert(1)> **bold**");
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("strong")).toBeNull();
  });

  it("searches what the person sees: names, values, agents and teams", async () => {
    serveMemories(ENTRIES);
    renderMemory();
    await screen.findAllByTestId("memory-item");

    await userEvent.type(screen.getByTestId("memory-search"), "launch team");
    expect(screen.getAllByTestId("memory-item")).toHaveLength(1);
    expect(screen.getByTestId("memory-count")).toHaveTextContent("Showing 1 of 3");

    await userEvent.clear(screen.getByTestId("memory-search"));
    await userEvent.type(screen.getByTestId("memory-search"), "support bot");
    expect(screen.getByTestId("memory-value")).toHaveTextContent("German");

    await userEvent.clear(screen.getByTestId("memory-search"));
    await userEvent.type(screen.getByTestId("memory-search"), "nothing like this");
    expect(screen.getByTestId("memory-no-match")).toBeInTheDocument();
  });

  it("asks for nothing when sign-in is off: there is no person to remember", () => {
    const seen: string[] = [];
    serveMemories(ENTRIES, seen);
    renderMemory({ ...GUEST_CONTEXT });
    expect(screen.getByTestId("memory-no-user")).toBeInTheDocument();
    expect(seen).toEqual([]);
  });

  it("says when nothing is remembered yet", async () => {
    serveMemories([]);
    renderMemory();
    expect(await screen.findByTestId("memory-empty")).toBeInTheDocument();
    expect(screen.queryByTestId("memory-forget-all")).toBeNull();
  });

  it("offers a retry when the memory cannot be read", async () => {
    server.use(
      http.get("*/usermemorystore/memories/:userId", () => HttpResponse.json({}, { status: 500 })),
    );
    renderMemory();
    expect(await screen.findByTestId("memory-error")).toHaveTextContent("Your memory could not be loaded.");
  });
});

describe("WorkforceMemory — forgetting", () => {
  it("forgets one entry by its id and keeps the rest", async () => {
    const deleted: string[] = [];
    serveMemories(ENTRIES);
    server.use(
      http.delete("*/usermemorystore/memories/entry/:entryId", ({ params }) => {
        deleted.push(String(params.entryId));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderMemory();
    const items = await screen.findAllByTestId("memory-item");
    const target = items[2]!; // "preferred language", the private one
    await userEvent.click(within(target).getByRole("button", { name: "Forget “preferred language”" }));

    await waitFor(() => expect(deleted).toEqual(["m-private"]));
    expect(toast.success).toHaveBeenCalledWith("Forgotten: preferred language");
  });

  it("removes the forgotten entry from the list once the server has deleted it", async () => {
    serveMemories(ENTRIES);
    renderMemory();
    const items = await screen.findAllByTestId("memory-item");
    await userEvent.click(within(items[0]!).getByTestId("memory-forget"));
    await waitFor(() => expect(screen.getAllByTestId("memory-item")).toHaveLength(2));
    expect(screen.queryByText('{"name":"Falcon","since":2025}')).toBeNull();
  });

  it("says why when the server refuses to forget an entry", async () => {
    serveMemories(ENTRIES);
    server.use(
      http.delete("*/usermemorystore/memories/entry/:entryId", () =>
        HttpResponse.json({ error: "Access denied" }, { status: 403 }),
      ),
    );
    renderMemory();
    const items = await screen.findAllByTestId("memory-item");
    await userEvent.click(within(items[0]!).getByTestId("memory-forget"));
    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    expect(vi.mocked(toast.error).mock.calls[0]![0]).toMatch(/^Could not forget this: /);
    expect(screen.getAllByTestId("memory-item")).toHaveLength(3);
  });

  it("forgets everything only after a confirm that names the consequence", async () => {
    const deletedAll: string[] = [];
    serveMemories(ENTRIES);
    server.use(
      http.delete("*/usermemorystore/memories/:userId", ({ params }) => {
        deletedAll.push(String(params.userId));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderMemory();
    await screen.findAllByTestId("memory-item");

    await userEvent.click(screen.getByTestId("memory-forget-all"));
    // Nothing is deleted by opening the dialog.
    expect(deletedAll).toEqual([]);
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent("Forget everything about you?");
    expect(dialog).toHaveTextContent(
      "All 3 entries are deleted, for every agent and team. Your agents will no longer know any of this in new conversations, and it cannot be undone. Your conversations themselves are kept.",
    );

    await userEvent.click(within(dialog).getByRole("button", { name: "Forget everything" }));
    await waitFor(() => expect(deletedAll).toEqual([ME]));
    expect(toast.success).toHaveBeenCalledWith("Everything your agents remembered about you is forgotten.");
  });

  it("deletes nothing when the confirm is cancelled", async () => {
    const deletedAll: string[] = [];
    serveMemories(ENTRIES);
    server.use(
      http.delete("*/usermemorystore/memories/:userId", ({ params }) => {
        deletedAll.push(String(params.userId));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderMemory();
    await screen.findAllByTestId("memory-item");
    await userEvent.click(screen.getByTestId("memory-forget-all"));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: /cancel/i }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(deletedAll).toEqual([]);
    expect(screen.getAllByTestId("memory-item")).toHaveLength(3);
  });
});
