import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor, fireEvent, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { SessionList } from "@/components/workforce/session-list";

/**
 * The Workforce sidebar's conversations (T1.1), against the MSW backend:
 * grouped by date with the pinned ones on top, renamed inline, pinned and
 * deleted through the owner endpoints — optimistically, and put back when the
 * server refuses.
 */

interface Seen {
  method: string;
  path: string;
  body?: unknown;
}

let seen: Seen[];

beforeEach(() => {
  seen = [];
  server.events.removeAllListeners();
  server.events.on("request:start", async ({ request }) => {
    const url = new URL(request.url);
    if (!url.pathname.startsWith("/conversationstore/conversations/") || request.method === "GET") return;
    const body = request.method === "PATCH" ? await request.clone().json() : undefined;
    seen.push({ method: request.method, path: url.pathname, body });
  });
});

describe("SessionList", () => {
  it("lists pinned chats first and every chat under its date group, by title or agent name and date", async () => {
    renderWithProviders(<SessionList />);
    const pinned = await screen.findByTestId("session-group-pinned");
    expect(within(pinned).getByTestId("session-chat-conv1-label")).toHaveTextContent("Where is order 1042?");
    const today = screen.getByTestId("session-group-today");
    expect(within(today).getByTestId("session-chat-conv3-label")).toHaveTextContent("Refund for a damaged parcel");
    // conv2 has no title: the agent's name and the date stand in.
    expect(screen.getByTestId("session-chat-conv2-label").textContent).toMatch(/·/);
    // Older than yesterday lands in a later group.
    expect(within(screen.getByTestId("session-group-previous7")).getByTestId("session-chat-conv7")).toBeInTheDocument();
    // Opens the conversation by both ids.
    expect(screen.getByTestId("session-chat-conv1")).toHaveAttribute(
      "href",
      "/workforce/chat?agentId=agent1&conversationId=conv1",
    );
  });

  it("renames a chat inline and sends the title to the owner endpoint", async () => {
    renderWithProviders(<SessionList />);
    fireEvent.click(await screen.findByTestId("session-chat-conv3-rename"));
    const input = screen.getByTestId("session-chat-conv3-rename-input");
    fireEvent.change(input, { target: { value: "  Parcel   refund " } });
    fireEvent.keyDown(input, { key: "Enter" });

    expect(await screen.findByTestId("session-chat-conv3-label")).toHaveTextContent("Parcel refund");
    await waitFor(() =>
      expect(seen).toContainEqual({
        method: "PATCH",
        path: "/conversationstore/conversations/conv3",
        body: { title: "Parcel refund" },
      }),
    );
    // One request, though the field also blurred as it went away.
    expect(seen.filter((s) => s.method === "PATCH")).toHaveLength(1);
  });

  it("leaves the title alone on Escape", async () => {
    renderWithProviders(<SessionList />);
    fireEvent.click(await screen.findByTestId("session-chat-conv3-rename"));
    const input = screen.getByTestId("session-chat-conv3-rename-input");
    fireEvent.change(input, { target: { value: "Something else" } });
    fireEvent.keyDown(input, { key: "Escape" });
    expect(await screen.findByTestId("session-chat-conv3-label")).toHaveTextContent("Refund for a damaged parcel");
    expect(seen).toHaveLength(0);
  });

  it("puts the old title back when the server refuses the rename", async () => {
    server.use(
      http.patch("*/conversationstore/conversations/:id", () =>
        HttpResponse.json({ error: "Forbidden" }, { status: 403 }),
      ),
    );
    renderWithProviders(<SessionList />);
    fireEvent.click(await screen.findByTestId("session-chat-conv3-rename"));
    const input = screen.getByTestId("session-chat-conv3-rename-input");
    fireEvent.change(input, { target: { value: "Mine now" } });
    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() =>
      expect(screen.getByTestId("session-chat-conv3-label")).toHaveTextContent("Refund for a damaged parcel"),
    );
  });

  it("pins a chat to the top, and unpins it", async () => {
    renderWithProviders(<SessionList />);
    fireEvent.click(await screen.findByTestId("session-chat-conv3-pin"));
    await waitFor(() =>
      expect(within(screen.getByTestId("session-group-pinned")).getByTestId("session-chat-conv3")).toBeInTheDocument(),
    );
    expect(seen).toContainEqual({
      method: "PATCH",
      path: "/conversationstore/conversations/conv3",
      body: { pinned: true },
    });

    fireEvent.click(screen.getByTestId("session-chat-conv1-pin"));
    await waitFor(() =>
      expect(within(screen.getByTestId("session-group-pinned")).queryByTestId("session-chat-conv1")).toBeNull(),
    );
    expect(seen).toContainEqual({
      method: "PATCH",
      path: "/conversationstore/conversations/conv1",
      body: { pinned: false },
    });
  });

  it("deletes a chat only after confirmation", async () => {
    renderWithProviders(<SessionList />);
    fireEvent.click(await screen.findByTestId("session-chat-conv2-delete"));
    expect(seen).toHaveLength(0);
    fireEvent.click(await screen.findByTestId("alert-dialog-confirm"));
    await waitFor(() => expect(screen.queryByTestId("session-chat-conv2")).toBeNull());
    expect(seen).toContainEqual({ method: "DELETE", path: "/conversationstore/conversations/conv2", body: undefined });
  });

  it("brings a chat back when the delete is refused", async () => {
    server.use(
      http.delete("*/conversationstore/conversations/:id", () =>
        HttpResponse.json({ error: "Forbidden" }, { status: 403 }),
      ),
    );
    renderWithProviders(<SessionList />);
    fireEvent.click(await screen.findByTestId("session-chat-conv2-delete"));
    fireEvent.click(await screen.findByTestId("alert-dialog-confirm"));
    await waitFor(() => expect(screen.getByTestId("session-chat-conv2")).toBeInTheDocument());
  });

  it("lists team sessions by their question and does not list their members' conversations as chats", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations", ({ params }) =>
        HttpResponse.json(
          params.groupId === "grp1"
            ? [
                {
                  id: "gc-x",
                  groupId: "grp1",
                  userId: "admin",
                  state: "COMPLETED",
                  originalQuestion: "Ship the redesign?",
                  transcript: [],
                  memberConversationIds: { agent1: "conv3" },
                  lastModified: new Date().toISOString(),
                },
              ]
            : [],
        ),
      ),
    );
    renderWithProviders(<SessionList />);
    expect(await screen.findByTestId("session-team-gc-x-label")).toHaveTextContent("Ship the redesign?");
    await waitFor(() => expect(screen.queryByTestId("session-chat-conv3")).toBeNull());
    // A team session has no rename or pin on the backend.
    expect(screen.queryByTestId("session-team-gc-x-rename")).toBeNull();
    expect(screen.queryByTestId("session-team-gc-x-pin")).toBeNull();
    expect(screen.getByTestId("session-team-gc-x-delete")).toBeInTheDocument();
  });
});
