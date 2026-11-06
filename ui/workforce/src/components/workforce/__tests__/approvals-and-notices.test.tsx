import { afterEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "sonner";
import { ThemeProvider } from "@eddi/ui";
import { resetUserEventStreamForTests } from "@eddi/core/user-events";
import { AuthContext, GUEST_CONTEXT } from "@eddi/app-kit/components/auth/auth-context";
import { UserEventBridge } from "@eddi/app-kit/components/layout/user-event-bridge";
import { appUserEventStream } from "@eddi/app-kit/lib/user-event-stream";
import { createTestQueryClient } from "@eddi/app-kit/test/test-utils";
import { closeMockUserEventStreams, pushMockUserEvent } from "@eddi/app-kit/test/mocks/handlers";
import { ApprovalsIndicator } from "@/components/workforce/approvals-indicator";
import { WorkforceNotices } from "@/components/workforce/workforce-notices";
import { BROWSER_NOTICES_KEY } from "@/lib/workforce-notices";

/**
 * T2.3 and T2.8 in Workforce, against the shared mock API: the topbar's count
 * of what waits on a human, its panel, and the toasts the user event stream
 * raises. The mock's pending approvals are `conv-awaiting-1` (user-123),
 * `conv-awaiting-2` (user-456, auto-reject in 15 min) and the team pause
 * `gconv-awaiting-1` (manager-user).
 */

function Where() {
  const location = useLocation();
  return <div data-testid="where">{location.pathname + location.search}</div>;
}

function mount({ path = "/workforce", roles = ["eddi-approver"], username = "user-123" } = {}) {
  const queryClient = createTestQueryClient();
  render(
    <AuthContext.Provider
      value={{
        ...GUEST_CONTEXT,
        method: "keycloak",
        roles,
        user: { username, firstName: "", lastName: "", email: "", fullName: username },
      }}
    >
      <MemoryRouter initialEntries={[path]}>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <UserEventBridge />
            <WorkforceNotices />
            <ApprovalsIndicator />
            <Where />
            <Toaster />
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
  return queryClient;
}

const streamOpen = () => waitFor(() => expect(appUserEventStream().status).toBe("open"));

afterEach(() => {
  closeMockUserEventStreams();
  resetUserEventStreamForTests();
  localStorage.removeItem(BROWSER_NOTICES_KEY);
  vi.unstubAllGlobals();
});

describe("ApprovalsIndicator (T2.3)", () => {
  it("counts what waits on a human and lists one's own apart from what to review", async () => {
    mount();
    expect(await screen.findByTestId("workforce-approvals-count")).toHaveTextContent("3");
    const button = screen.getByTestId("workforce-approvals-button");
    expect(button).toHaveAttribute("aria-label", "Waiting on a human: 3");

    fireEvent.click(button);
    expect(button).toHaveAttribute("aria-expanded", "true");
    const mine = screen.getByTestId("workforce-approvals-mine");
    expect(within(mine).getByTestId("workforce-approval-conv-awaiting-1")).toHaveAttribute(
      "href",
      "/workforce/chat?agentId=agent1&conversationId=conv-awaiting-1",
    );
    const review = screen.getByTestId("workforce-approvals-review");
    expect(within(review).getByTestId("workforce-approval-conv-awaiting-2")).toHaveAttribute("href", "/manage/approvals");
    expect(within(review).getByTestId("workforce-approval-conv-awaiting-2")).toHaveTextContent("Rejected automatically at");
    expect(within(review).getByTestId("workforce-approval-gconv-awaiting-1")).toHaveTextContent("Team session waiting for approval");
  });

  it("jumps to the paused turn in Workforce and closes the panel", async () => {
    mount();
    fireEvent.click(await screen.findByTestId("workforce-approvals-button"));
    fireEvent.click(await screen.findByTestId("workforce-approval-conv-awaiting-1"));
    expect(screen.getByTestId("where")).toHaveTextContent("/workforce/chat?agentId=agent1&conversationId=conv-awaiting-1");
    expect(screen.queryByTestId("workforce-approvals-panel")).not.toBeInTheDocument();
  });

  it("Escape closes the panel and gives focus back to the button", async () => {
    mount();
    const button = await screen.findByTestId("workforce-approvals-button");
    fireEvent.click(button);
    expect(screen.getByTestId("workforce-approvals-panel")).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByTestId("workforce-approvals-panel")).not.toBeInTheDocument();
    expect(button).toHaveFocus();
  });

  it("asks the browser for notification permission only from the Turn on click, never on load", async () => {
    const requestPermission = vi.fn(async () => "granted" as NotificationPermission);
    const NotificationStub = Object.assign(vi.fn(), { permission: "default" as NotificationPermission, requestPermission });
    requestPermission.mockImplementation(async () => {
      NotificationStub.permission = "granted";
      return "granted";
    });
    vi.stubGlobal("Notification", NotificationStub);

    mount();
    fireEvent.click(await screen.findByTestId("workforce-approvals-button"));
    expect(requestPermission).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId("workforce-browser-notices-on"));
    await waitFor(() => expect(screen.getByTestId("workforce-browser-notices-off")).toBeInTheDocument());
    expect(requestPermission).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(BROWSER_NOTICES_KEY)).toBe("on");
  });
});

describe("WorkforceNotices (T2.8)", () => {
  it("a finished job raises a toast whose Open goes to the chat", async () => {
    mount();
    await streamOpen();
    act(() => {
      pushMockUserEvent({ kind: "job.finished", surface: "regular", conversationId: "c1", agentId: "a1", jobKind: "continuation", state: "done" });
    });
    expect(await screen.findByText("A long-running job finished.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(screen.getByTestId("where")).toHaveTextContent("/workforce/chat?agentId=a1&conversationId=c1");
  });

  it("stays quiet about the conversation already on screen", async () => {
    mount({ path: "/workforce/chat?agentId=a1&conversationId=c1" });
    await streamOpen();
    act(() => {
      pushMockUserEvent({ kind: "job.failed", surface: "regular", conversationId: "c1", agentId: "a1", jobKind: "schedule" });
      pushMockUserEvent({ kind: "team.ended", surface: "group", groupId: "g1", groupConversationId: "gc1", state: "COMPLETED" });
    });
    // The second event's toast proves the first was handled, and skipped.
    expect(await screen.findByText("A team discussion finished.")).toBeInTheDocument();
    expect(screen.queryByText("A scheduled run failed.")).not.toBeInTheDocument();
  });

  it("tells the person their own paused conversation was decided, and an approver nothing about others'", async () => {
    mount();
    // The pending lists are loaded before the decision arrives.
    await screen.findByTestId("workforce-approvals-count");
    await streamOpen();
    act(() => {
      pushMockUserEvent({ kind: "approval.decided", surface: "regular", conversationId: "conv-awaiting-2", agentId: "agent2", state: "APPROVED" });
      pushMockUserEvent({ kind: "approval.decided", surface: "regular", conversationId: "conv-awaiting-1", agentId: "agent1", state: "REJECTED" });
    });
    expect(await screen.findByText("Your paused conversation was rejected.")).toBeInTheDocument();
    expect(screen.queryByText("Your paused conversation was approved and continues.")).not.toBeInTheDocument();
  });

  it("a team waiting for someone else's turn raises nothing; one waiting for yours does", async () => {
    mount();
    await streamOpen();
    act(() => {
      pushMockUserEvent({ kind: "team.waiting_for_human", surface: "group", groupId: "g1", groupConversationId: "gc1", yourTurn: false });
      pushMockUserEvent({ kind: "team.waiting_for_human", surface: "group", groupId: "g1", groupConversationId: "gc2", yourTurn: true });
    });
    expect(await screen.findAllByText("A team is waiting for your turn.")).toHaveLength(1);
  });

  it("sends a browser notification only when opted in and the tab is in the background", async () => {
    const NotificationStub = Object.assign(
      vi.fn(function (this: { close: () => void; title?: string }, title: string) {
        this.title = title;
        this.close = vi.fn();
      }),
      { permission: "granted" as NotificationPermission, requestPermission: vi.fn() },
    );
    vi.stubGlobal("Notification", NotificationStub);
    const visibility = vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    try {
      mount();
      await streamOpen();
      act(() => {
        pushMockUserEvent({ kind: "job.run_completed", surface: "regular", conversationId: "c1", agentId: "a1", jobKind: "schedule" });
      });
      await screen.findByText("A scheduled run finished.");
      expect(NotificationStub).not.toHaveBeenCalled();

      localStorage.setItem(BROWSER_NOTICES_KEY, "on");
      act(() => {
        pushMockUserEvent({ kind: "job.run_completed", surface: "regular", conversationId: "c2", agentId: "a1", jobKind: "schedule" });
      });
      await waitFor(() => expect(NotificationStub).toHaveBeenCalledTimes(1));
      expect(NotificationStub.mock.calls[0]?.[0]).toBe("A scheduled run finished.");
    } finally {
      visibility.mockRestore();
    }
  });
});
