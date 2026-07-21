/* ──────────────────────────────────────────────
   ChatWidget — integration smoke tests

   These exist partly as a guard: nothing imported ChatWidget before, so a
   syntax error in the largest file in the project could survive a fully green
   suite. Mounting it here means the build breaks the tests too.
   ────────────────────────────────────────────── */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { ChatWidget } from "./ChatWidget";
import { ChatProvider } from "@/store/chat-store";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
});

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
});

/** Route the widget the way the app does: /chat/:environment/:agentId */
function renderWidget() {
  return render(
    <MemoryRouter initialEntries={["/chat/production/agent-1"]}>
      <ChatProvider>
        <Routes>
          <Route path="/chat/:environment/:agentId" element={<ChatWidget />} />
        </Routes>
      </ChatProvider>
    </MemoryRouter>,
  );
}

/** Minimal backend: start → Location header, then a snapshot read. */
function mockBackend(snapshot: Record<string, unknown>) {
  globalThis.fetch = vi.fn(async (url: string | URL | Request) => {
    const href = String(url);
    if (href.includes("/start")) {
      return new Response(null, {
        status: 201,
        headers: { Location: "/agents/conv-1" },
      });
    }
    if (href.includes("/approval-status")) {
      return new Response(
        JSON.stringify({
          conversationId: "conv-1",
          state: "AWAITING_HUMAN",
          pausedAt: "2026-07-21T10:00:00Z",
          pauseReason: "manager approval required",
          timeoutPolicy: "AUTO_REJECT",
          approvalTimeout: "PT15M",
          pauseDetails: null,
        }),
        { status: 200 },
      );
    }
    if (href.includes("/agentstore/")) {
      return new Response("{}", { status: 200 });
    }
    return new Response(JSON.stringify(snapshot), { status: 200 });
  }) as typeof fetch;
}

describe("ChatWidget", () => {
  it("mounts and starts a conversation", async () => {
    mockBackend({
      conversationState: "READY",
      conversationSteps: [{ output: "Hello!" }],
    });

    renderWidget();

    expect(await screen.findByText("Hello!")).toBeInTheDocument();
  });

  it("renders bare-string output items instead of dropping them", async () => {
    // HITL writes its placeholder as a raw String in output[].
    mockBackend({
      conversationState: "READY",
      conversationOutputs: [
        { output: ["Waiting for approval of send_email."], quickReplies: [] },
      ],
    });

    renderWidget();

    expect(
      await screen.findByText("Waiting for approval of send_email."),
    ).toBeInTheDocument();
  });

  it("shows the paused card and hides the composer while awaiting approval", async () => {
    mockBackend({
      conversationState: "AWAITING_HUMAN",
      conversationOutputs: [{ output: [], quickReplies: [] }],
    });

    renderWidget();

    const card = await screen.findByTestId("paused-card");
    expect(card).toHaveTextContent(/reviewer must approve/i);

    await waitFor(() => {
      expect(screen.getByTestId("chat-input")).toBeDisabled();
    });
  });

  it("does not offer approve or reject to the end user", async () => {
    mockBackend({
      conversationState: "AWAITING_HUMAN",
      conversationOutputs: [{ output: [], quickReplies: [] }],
    });

    renderWidget();
    await screen.findByTestId("paused-card");

    expect(screen.queryByRole("button", { name: /approve/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /reject/i })).toBeNull();
  });
});
