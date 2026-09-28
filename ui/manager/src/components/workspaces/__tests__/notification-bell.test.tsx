import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { NotificationBell } from "@/components/workspaces/notification-bell";
import * as workspacesApi from "@/lib/api/workspaces";
import type { WorkspaceInfo, WorkspaceNotification } from "@/lib/api/workspaces";

const RESOURCE_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";

function info(overrides: Partial<WorkspaceInfo> = {}): WorkspaceInfo {
  return {
    enabled: true,
    principal: "alice",
    defaultSpace: "user:alice",
    spaces: [{ id: "user:alice", kind: "personal", label: "alice" }],
    seesEverything: false,
    ...overrides,
  };
}

function notification(overrides: Partial<WorkspaceNotification> = {}): WorkspaceNotification {
  return {
    id: "n1",
    type: "SHARED_WITH_YOU",
    resourceId: RESOURCE_ID,
    resourceUri: `eddi://ai.labs.agent/agentstore/agents/${RESOURCE_ID}?version=1`,
    resourceName: "Support Agent",
    actor: "bob",
    actorLabel: "Bob Builder",
    level: "EDIT",
    createdAt: "2026-09-28T10:00:00Z",
    readAt: null,
    ...overrides,
  };
}

describe("NotificationBell", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => vi.restoreAllMocks());

  function render(list: WorkspaceNotification[], ws = info()) {
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(ws);
    const read = vi.fn();
    const shared = vi.fn();
    server.use(
      http.get("*/workspaces/notifications/count", () =>
        HttpResponse.json({ unread: list.filter((n) => !n.readAt).length })
      ),
      http.get("*/workspaces/notifications", () => HttpResponse.json(list)),
      http.post("*/workspaces/notifications/read", async ({ request }) => {
        read(await request.json());
        return HttpResponse.json({ marked: 1 });
      }),
      http.post(`*/descriptorstore/descriptors/${RESOURCE_ID}/shares`, ({ request }) => {
        const params = new URL(request.url).searchParams;
        shared(params.get("subject"), params.get("level"));
        return HttpResponse.json({ updated: [], skipped: [] });
      })
    );
    renderWithProviders(<NotificationBell />);
    return { read, shared };
  }

  it("shows the unread count, and says what was shared by whom", async () => {
    render([notification()]);

    expect(await screen.findByTestId("notification-count")).toHaveTextContent("1");
    await userEvent.click(screen.getByTestId("notification-bell"));

    const item = await screen.findByTestId("notification-n1");
    expect(item).toHaveTextContent("Bob Builder");
    expect(item).toHaveTextContent("Support Agent");
  });

  it("grants an access request in one click, to the person who asked", async () => {
    const { shared, read } = render([notification({ type: "ACCESS_REQUESTED", level: "VIEW", message: "for the audit" })]);

    await userEvent.click(await screen.findByTestId("notification-bell"));
    expect(await screen.findByText(/for the audit/)).toBeInTheDocument();
    await userEvent.click(screen.getByTestId("notification-grant-n1"));

    await waitFor(() => expect(shared).toHaveBeenCalledWith("user:bob", "VIEW"));
    await waitFor(() => expect(read).toHaveBeenCalledWith({ ids: ["n1"] }));
  });

  it("does not offer Grant on a share — there is nothing to grant", async () => {
    render([notification()]);

    await userEvent.click(await screen.findByTestId("notification-bell"));
    await screen.findByTestId("notification-n1");

    expect(screen.queryByTestId("notification-grant-n1")).not.toBeInTheDocument();
  });

  it("sends a chat-only share to the chat, since the configuration will not open", async () => {
    render([notification({ level: "USE" })]);

    await userEvent.click(await screen.findByTestId("notification-bell"));
    const item = await screen.findByTestId("notification-n1");

    expect(item.querySelector("a")?.getAttribute("href")).toContain(`/chat/production/${RESOURCE_ID}`);
  });

  it("says so when there is nothing new", async () => {
    render([]);

    await userEvent.click(await screen.findByTestId("notification-bell"));

    expect(await screen.findByTestId("notifications-empty")).toBeInTheDocument();
    expect(screen.queryByTestId("notification-count")).not.toBeInTheDocument();
  });

  it("does not render while workspaces are off", async () => {
    const spy = vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info({ enabled: false }));
    renderWithProviders(<NotificationBell />);

    await waitFor(() => expect(spy).toHaveBeenCalled());
    // Let the query settle before asserting absence.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId("notification-bell")).not.toBeInTheDocument();
  });
});
