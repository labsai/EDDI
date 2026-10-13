import { describe, it, expect, vi, afterEach } from "vitest";
import { screen, waitFor, fireEvent, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { GroupDetailPage } from "@/pages/group-detail";
import { server } from "@/test/mocks/server";
import { useGroupStreamStore } from "@/hooks/use-group-discussion-stream";

window.HTMLElement.prototype.scrollIntoView = vi.fn();
window.HTMLElement.prototype.scrollTo = vi.fn();

function renderGroupDetail(search = "?version=1") {
  return renderPage(`/manage/groups/grp1${search}`, <GroupDetailPage />, "/manage/groups/:id");
}

const row = (id: string, state: string, extra: Record<string, unknown> = {}) => ({
  id,
  groupId: "grp1",
  userId: "admin",
  state,
  originalQuestion: `Question ${id}`,
  created: "2026-06-01T10:00:00Z",
  lastModified: "2026-06-01T10:00:00Z",
  ...extra,
});

function full(id: string, state: string, extra: Record<string, unknown> = {}) {
  return {
    ...row(id, state),
    transcript: [],
    memberConversationIds: {},
    currentPhaseIndex: 0,
    currentPhaseName: "Opinion",
    depth: 0,
    taskList: null,
    dynamicMembers: [],
    createdAgentIds: [],
    retainedAgentIds: [],
    availableActions: state === "COMPLETED" ? ["followup", "continue", "close"] : [],
    ...extra,
  };
}

function serve(rows: ReturnType<typeof row>[], details: Record<string, unknown> = {}) {
  server.use(
    http.get("*/groups/:groupId/conversations", () => HttpResponse.json(rows)),
    http.get("*/groups/:groupId/conversations/:convId", ({ params }) =>
      HttpResponse.json(details[String(params.convId)] ?? full(String(params.convId), "COMPLETED")),
    ),
  );
}

afterEach(() => useGroupStreamStore.setState({ streams: {} }));

describe("GroupDetailPage — composer intent", () => {
  it("starts a NEW discussion by default and continues only once the user says so", async () => {
    serve([row("gc-1", "COMPLETED")]);
    renderGroupDetail();

    const toggle = await screen.findByTestId("composer-mode-toggle");
    expect(toggle).toBeInTheDocument();
    expect(screen.getByTestId("composer-mode-new")).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("composer-mode-continue")).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByPlaceholderText(/Ask a question for the group/i)).toBeInTheDocument();

    await userEvent.click(screen.getByTestId("composer-mode-continue"));
    expect(screen.getByPlaceholderText(/Continue this discussion/i)).toBeInTheDocument();
  });

  it("picking a discussion in the history means continue", async () => {
    serve([row("gc-1", "COMPLETED"), row("gc-2", "COMPLETED")]);
    renderGroupDetail();
    await screen.findByTestId("composer-mode-toggle");

    await userEvent.click(screen.getByTestId("discussion-item-gc-2"));
    await waitFor(() =>
      expect(screen.getByTestId("composer-mode-continue")).toHaveAttribute("aria-pressed", "true"),
    );
  });
});

describe("GroupDetailPage — discussion history", () => {
  it("loads older discussions a page at a time", async () => {
    const limits: string[] = [];
    server.use(
      http.get("*/groups/:groupId/conversations", ({ request }) => {
        const limit = Number(new URL(request.url).searchParams.get("limit"));
        limits.push(String(limit));
        // A full page each time, so there is always possibly more.
        return HttpResponse.json(Array.from({ length: limit }, (_, i) => row(`gc-${i}`, "COMPLETED")));
      }),
      http.get("*/groups/:groupId/conversations/:convId", ({ params }) =>
        HttpResponse.json(full(String(params.convId), "COMPLETED")),
      ),
    );
    renderGroupDetail();

    const more = await screen.findByTestId("load-more-discussions");
    expect(limits[0]).toBe("20");
    await userEvent.click(more);
    await waitFor(() => expect(limits).toContain("40"));
    await waitFor(() => expect(screen.getByTestId("discussion-item-gc-39")).toBeInTheDocument());
  });

  it("offers Cancel on a SYNTHESIZING discussion, not just the ones before synthesis", async () => {
    serve([row("gc-syn", "SYNTHESIZING")], { "gc-syn": full("gc-syn", "SYNTHESIZING") });
    renderGroupDetail();
    await screen.findByTestId("discussion-item-gc-syn");
    expect(screen.getByRole("button", { name: "Cancel discussion" })).toBeInTheDocument();
  });

  it("warns that deleting a running discussion stops it", async () => {
    serve([row("gc-run", "IN_PROGRESS")], { "gc-run": full("gc-run", "IN_PROGRESS") });
    renderGroupDetail();
    await userEvent.click(await screen.findByTestId("delete-discussion-gc-run"));
    expect(await screen.findByText(/still running\. It is stopped/i)).toBeInTheDocument();
  });

  it("the mobile history button is labelled, reports its state, and closes on Escape", async () => {
    serve([row("gc-1", "COMPLETED")]);
    renderGroupDetail();
    const trigger = await screen.findByTestId("history-dropdown-trigger");
    expect(trigger).toHaveAccessibleName("Discussions");
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await userEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByTestId("new-discussion-btn-mobile")).toBeInTheDocument();

    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(trigger).toHaveAttribute("aria-expanded", "false"));
    expect(trigger).toHaveFocus();
  });
});

describe("GroupDetailPage — fullscreen", () => {
  it("leaves fullscreen on Escape, unless a dialog owns the key", async () => {
    serve([row("gc-1", "COMPLETED")]);
    renderGroupDetail();
    const toggle = await screen.findByTestId("group-fullscreen-toggle");
    expect(toggle).toHaveAttribute("aria-pressed", "false");

    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-pressed", "true");

    // A dialog is open over the page: Escape belongs to it.
    const dialog = document.createElement("div");
    dialog.setAttribute("role", "dialog");
    document.body.appendChild(dialog);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(toggle).toHaveAttribute("aria-pressed", "true");
    dialog.remove();

    fireEvent.keyDown(window, { key: "Escape" });
    await waitFor(() => expect(toggle).toHaveAttribute("aria-pressed", "false"));
  });
});

describe("GroupDetailPage — live stream", () => {
  it("says so when the live connection drops, instead of silently switching to polling", async () => {
    serve([row("gc-1", "IN_PROGRESS")], { "gc-1": full("gc-1", "IN_PROGRESS") });
    renderGroupDetail();
    await screen.findByTestId("discussion-item-gc-1");
    expect(screen.queryByTestId("group-stream-interrupted")).not.toBeInTheDocument();

    act(() => {
      useGroupStreamStore.getState().update("grp1", (s) => ({
        ...s,
        conversationId: "gc-1",
        state: "IN_PROGRESS",
        isStreaming: false,
        interrupted: true,
      }));
    });
    expect(await screen.findByTestId("group-stream-interrupted")).toHaveAttribute("role", "status");
  });

  it("keeps the typed question when the start is refused before anything connected", async () => {
    serve([]);
    server.use(
      http.post("*/groups/:groupId/conversations/stream", () =>
        HttpResponse.json({ message: "A discussion needs at least two members" }, { status: 400 }),
      ),
    );
    renderGroupDetail();
    const input = await screen.findByTestId("discussion-input");
    await userEvent.type(input, "Will this survive?");
    await userEvent.click(screen.getByTestId("start-discussion-btn"));

    await waitFor(() => expect(screen.getByTestId("discussion-input")).not.toBeDisabled());
    expect(screen.getByTestId("discussion-input")).toHaveValue("Will this survive?");
    expect(screen.queryByText(/streaming live/i)).not.toBeInTheDocument();
  });
});
