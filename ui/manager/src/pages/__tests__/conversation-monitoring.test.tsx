import { describe, it, expect, vi } from "vitest";
import { screen, waitFor, within, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ConversationMonitoringPage } from "@/pages/conversation-monitoring";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
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
});
