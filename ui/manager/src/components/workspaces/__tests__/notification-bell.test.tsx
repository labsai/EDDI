import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
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

  it("a failed dismiss gives back only its own notification, not one dismissed after it", async () => {
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info());
    const list = [notification({ id: "n1" }), notification({ id: "n2" })];
    let failFirst!: () => void;
    const firstFails = new Promise<void>((r) => (failFirst = r));
    let gets = 0;
    const hang = () => new Promise<Response>(() => {});
    server.use(
      // Serve the first read of each, then hang: the rollback is what is under
      // test, not the refetch that would later repair the cache anyway.
      http.get("*/workspaces/notifications/count", () => (gets++ < 1 ? HttpResponse.json({ unread: 2 }) : hang())),
      http.get("*/workspaces/notifications", () => (gets++ < 2 ? HttpResponse.json(list) : hang())),
      http.post("*/workspaces/notifications/read", async ({ request }) => {
        const body = (await request.json()) as string[] | { ids?: string[] };
        const ids = Array.isArray(body) ? body : (body.ids ?? []);
        if (!ids.includes("n1")) return hang();
        await firstFails;
        return HttpResponse.json({ message: "nope" }, { status: 500 });
      })
    );
    renderWithProviders(<NotificationBell />);
    expect(await screen.findByTestId("notification-count")).toHaveTextContent("2");
    await userEvent.click(screen.getByTestId("notification-bell"));

    await userEvent.click(await screen.findByTestId("notification-dismiss-n1"));
    await userEvent.click(await screen.findByTestId("notification-dismiss-n2"));
    await waitFor(() => expect(screen.queryByTestId("notification-count")).toBeNull());

    failFirst();
    // n1 is unread again and counted; n2 stays dismissed.
    expect(await screen.findByTestId("notification-dismiss-n1")).toBeInTheDocument();
    expect(screen.queryByTestId("notification-dismiss-n2")).toBeNull();
    expect(screen.getByTestId("notification-count")).toHaveTextContent("1");
  });

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

  it("sends a chat-only share to the Manager's chat, since the configuration will not open", async () => {
    render([notification({ level: "USE" })]);

    await userEvent.click(await screen.findByTestId("notification-bell"));
    const item = await screen.findByTestId("notification-n1");

    expect(item.querySelector("a")?.getAttribute("href")).toContain(`/manage/chat?agentId=${RESOURCE_ID}`);
  });

  it("says so when there is nothing new", async () => {
    render([]);

    await userEvent.click(await screen.findByTestId("notification-bell"));

    expect(await screen.findByTestId("notifications-empty")).toBeInTheDocument();
    expect(screen.queryByTestId("notification-count")).not.toBeInTheDocument();
  });

  it("shows the update notice even while workspaces are off, and links to the Updates page", async () => {
    // The update check is on by default (the default handler), so the bell must
    // not depend on workspaces to carry its notice.
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info({ enabled: false }));
    server.use(
      http.get("*/workspaces/notifications/count", () => HttpResponse.json({ unread: 1 })),
      http.get("*/workspaces/notifications", () =>
        HttpResponse.json([
          notification({
            id: "u1",
            type: "UPDATE_AVAILABLE",
            resourceId: "9.9.9",
            resourceUri: "https://github.com/labsai/EDDI/releases/tag/9.9.9",
            resourceName: "9.9.9",
            actor: "system",
            actorLabel: "EDDI",
          }),
        ]),
      ),
    );
    renderWithProviders(<NotificationBell />);

    expect(await screen.findByTestId("notification-count")).toHaveTextContent("1");
    await userEvent.click(screen.getByTestId("notification-bell"));

    expect(await screen.findByTestId("notification-u1")).toHaveTextContent("9.9.9 is available");
    expect(screen.getByTestId("notification-update-u1")).toHaveAttribute("href", "/manage/updates");
  });

  it("does not render while workspaces are off and the update check is off too", async () => {
    server.use(
      http.get("*/administration/update-check", () =>
        HttpResponse.json({ enabled: false, lockedByConfig: true, uiEnabled: true }),
      ),
    );
    const spy = vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info({ enabled: false }));
    renderWithProviders(<NotificationBell />);

    await waitFor(() => expect(spy).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId("notification-bell")).not.toBeInTheDocument();
  });
});
