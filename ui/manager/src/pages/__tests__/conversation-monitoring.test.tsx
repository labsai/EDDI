import { describe, it, expect, vi } from "vitest";
import { screen, waitFor, within, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ConversationMonitoringPage } from "@/pages/conversation-monitoring";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}));

import { toast } from "sonner";

const ACTIVE_ROWS = [
  {
    conversationId: "conv-active-1",
    agentId: "agent1",
    agentVersion: 3,
    conversationState: "IN_PROGRESS",
    lastInteraction: Date.now() - 60_000,
  },
  {
    conversationId: "conv-active-2",
    agentId: "agent1",
    agentVersion: 3,
    conversationState: "AWAITING_HUMAN",
    lastInteraction: Date.now() - 30_000,
  },
];

/** Type an agent id into the AgentPicker and commit it via Enter. */
async function chooseAgent(user: ReturnType<typeof userEvent.setup>) {
  const input = screen.getByPlaceholderText("Select an agent");
  await user.type(input, "agent1");
  await user.keyboard("{Enter}");
}

describe("ConversationMonitoringPage — state badges", () => {
  it("renders localized state labels, not the raw backend enum", async () => {
    server.use(
      http.get("*/conversationstore/conversations/active/:agentId", () =>
        HttpResponse.json(ACTIVE_ROWS)
      )
    );

    renderWithProviders(<ConversationMonitoringPage />);
    const user = userEvent.setup();

    await chooseAgent(user);
    await screen.findByTestId("active-conversation-list");

    const row = screen.getByTestId("select-conv-active-1").closest("tr")!;
    expect(within(row).getByText("In Progress")).toBeInTheDocument();
    expect(within(row).queryByText("IN_PROGRESS")).not.toBeInTheDocument();
  });
});

describe("ConversationMonitoringPage — bulk end", () => {
  it("prompts before ending and posts the selected statuses to /end", async () => {
    let endBody: unknown = null;
    server.use(
      http.get("*/conversationstore/conversations/active/:agentId", () =>
        HttpResponse.json(ACTIVE_ROWS)
      ),
      http.post("*/conversationstore/conversations/end", async ({ request }) => {
        endBody = await request.json();
        return new HttpResponse(null, { status: 200 });
      })
    );

    renderWithProviders(<ConversationMonitoringPage />);
    const user = userEvent.setup();

    await chooseAgent(user);

    // Active conversations load for the selected agent+version.
    await screen.findByTestId("active-conversation-list");
    expect(screen.getByTestId("select-conv-active-1")).toBeInTheDocument();

    // Select all, then trigger the bulk action.
    await user.click(screen.getByTestId("select-all"));
    await user.click(screen.getByTestId("end-selected"));

    // Confirmation dialog appears (no request yet).
    const dialog = await screen.findByRole("dialog");
    expect(
      within(dialog).getByText("End selected conversations?")
    ).toBeInTheDocument();
    expect(endBody).toBeNull();

    // Confirm.
    await user.click(within(dialog).getByRole("button", { name: "End selected" }));

    await waitFor(() => {
      expect(endBody).toHaveLength(2);
    });
    // The paused (AWAITING_HUMAN) row is included in the payload.
    expect(endBody).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          conversationId: "conv-active-2",
          conversationState: "AWAITING_HUMAN",
        }),
      ])
    );
    expect(toast.success).toHaveBeenCalled();
  });

  it("does not call /end when the dialog is cancelled", async () => {
    let called = false;
    server.use(
      http.get("*/conversationstore/conversations/active/:agentId", () =>
        HttpResponse.json(ACTIVE_ROWS)
      ),
      http.post("*/conversationstore/conversations/end", () => {
        called = true;
        return new HttpResponse(null, { status: 200 });
      })
    );

    renderWithProviders(<ConversationMonitoringPage />);
    const user = userEvent.setup();

    await chooseAgent(user);
    await screen.findByTestId("active-conversation-list");

    await user.click(screen.getByTestId("select-conv-active-1"));
    await user.click(screen.getByTestId("end-selected"));

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await waitFor(() =>
      expect(screen.queryByText("End selected conversations?")).not.toBeInTheDocument()
    );
    expect(called).toBe(false);
  });
});

describe("ConversationMonitoringPage — purge ended", () => {
  it("prompts, then DELETEs with deleteOlderThanDays and reports the count", async () => {
    let purgeUrl = "";
    server.use(
      http.delete("*/conversationstore/conversations/", ({ request }) => {
        purgeUrl = request.url;
        return HttpResponse.json(4);
      })
    );

    renderWithProviders(<ConversationMonitoringPage />);
    const user = userEvent.setup();

    fireEvent.change(screen.getByTestId("purge-days"), { target: { value: "45" } });
    await user.click(screen.getByTestId("purge-ended"));

    const dialog = await screen.findByRole("dialog");
    expect(
      within(dialog).getByText("Purge ended conversations?")
    ).toBeInTheDocument();
    expect(purgeUrl).toBe("");

    await user.click(within(dialog).getByRole("button", { name: "Purge" }));

    await waitFor(() => {
      expect(purgeUrl).toContain("deleteOlderThanDays=45");
    });
    expect(toast.success).toHaveBeenCalled();
  });

  it("clamps the days input to a minimum of 1 (0 would purge ALL ended conversations)", async () => {
    renderWithProviders(<ConversationMonitoringPage />);

    const input = screen.getByTestId("purge-days") as HTMLInputElement;
    expect(input).toHaveAttribute("min", "1");

    // Trying to set 0 (which would purge every ENDED conversation) is clamped.
    fireEvent.change(input, { target: { value: "0" } });
    expect(input).toHaveValue(1);
  });

  it("purges with the clamped minimum of 1 day, never 0", async () => {
    let purgeUrl = "";
    server.use(
      http.delete("*/conversationstore/conversations/", ({ request }) => {
        purgeUrl = request.url;
        return HttpResponse.json(0);
      })
    );

    renderWithProviders(<ConversationMonitoringPage />);
    const user = userEvent.setup();

    fireEvent.change(screen.getByTestId("purge-days"), { target: { value: "0" } });
    await user.click(screen.getByTestId("purge-ended"));

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Purge" }));

    await waitFor(() => {
      expect(purgeUrl).toContain("deleteOlderThanDays=1");
    });
    expect(purgeUrl).not.toContain("deleteOlderThanDays=0");
  });
});

// The body of /end carries each conversation's state and the backend acts on
// it. It used to be the last poll's state — up to ten seconds old — so a
// conversation that had paused for approval meanwhile was ended as if it were
// running, and its pending approval was never cleaned up.
describe("ConversationMonitoringPage — bulk end uses the current state", () => {
  async function selectFirstAndConfirm(user: ReturnType<typeof userEvent.setup>) {
    await chooseAgent(user);
    await screen.findByTestId("active-conversation-list");
    await user.click(screen.getByTestId("select-conv-active-1"));
    await user.click(screen.getByTestId("end-selected"));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "End selected" }));
    return dialog;
  }

  it("re-reads the list and sends the state it has now", async () => {
    let reads = 0;
    let endBody: unknown = null;
    server.use(
      http.get("*/conversationstore/conversations/active/:agentId", () => {
        reads++;
        return HttpResponse.json(
          reads === 1
            ? [ACTIVE_ROWS[0]]
            : [{ ...ACTIVE_ROWS[0], conversationState: "READY" }]
        );
      }),
      http.post("*/conversationstore/conversations/end", async ({ request }) => {
        endBody = await request.json();
        return new HttpResponse(null, { status: 200 });
      })
    );

    renderWithProviders(<ConversationMonitoringPage />);
    await selectFirstAndConfirm(userEvent.setup());

    await waitFor(() => expect(endBody).not.toBeNull());
    expect(endBody).toEqual([
      expect.objectContaining({ conversationId: "conv-active-1", conversationState: "READY" }),
    ]);
  });

  it("does not end a conversation that paused for approval since the dialog was opened", async () => {
    let reads = 0;
    let ended = false;
    server.use(
      http.get("*/conversationstore/conversations/active/:agentId", () => {
        reads++;
        return HttpResponse.json(
          reads === 1
            ? [ACTIVE_ROWS[0]]
            : [{ ...ACTIVE_ROWS[0], conversationState: "AWAITING_HUMAN" }]
        );
      }),
      http.post("*/conversationstore/conversations/end", () => {
        ended = true;
        return new HttpResponse(null, { status: 200 });
      })
    );

    renderWithProviders(<ConversationMonitoringPage />);
    const dialog = await selectFirstAndConfirm(userEvent.setup());

    await waitFor(() => expect(toast.warning).toHaveBeenCalled());
    expect(ended).toBe(false);
    // The dialog stays open and now warns about the pending approval.
    expect(dialog).toBeInTheDocument();
    await waitFor(() =>
      expect(within(dialog).getByText(/Awaiting Human and will have its pending approval cancelled/)).toBeInTheDocument()
    );
  });
});

