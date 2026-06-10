import { describe, it, expect, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CoordinatorPage } from "@/pages/coordinator";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

// Mock BearerEventSource for SSE
vi.mock("@/lib/bearer-event-source", () => ({
  BearerEventSource: vi.fn().mockImplementation(() => ({
    addEventListener: vi.fn(),
    close: vi.fn(),
    onmessage: null,
    onerror: null,
    onopen: null,
  })),
}));

function renderCoordinator() {
  return renderWithProviders(<CoordinatorPage />, {
    initialRoute: "/manage/coordinator",
  });
}

describe("CoordinatorPage", () => {
  it("renders the page title", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByText("Coordinator Dashboard")).toBeInTheDocument();
    });
  });

  it("renders page subtitle", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByText(/Monitor conversation processing/)).toBeInTheDocument();
    });
  });

  it("renders coordinator type card showing NATS JetStream", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByTestId("coordinator-type-card")).toBeInTheDocument();
      expect(screen.getByText("NATS JetStream")).toBeInTheDocument();
    });
  });

  it("renders connection status card with CONNECTED", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByTestId("coordinator-connection-card")).toBeInTheDocument();
      expect(screen.getByText(/CONNECTED/)).toBeInTheDocument();
    });
  });

  it("renders tasks processed card with count", async () => {
    renderCoordinator();
    await waitFor(() => {
      const card = screen.getByTestId("coordinator-processed-card");
      expect(card).toBeInTheDocument();
      // The number 142897 is formatted with toLocaleString() — locale-dependent separator
      expect(within(card).getByText(/142/)).toBeInTheDocument();
    });
  });

  it("renders dead-lettered card with count", async () => {
    renderCoordinator();
    await waitFor(() => {
      const card = screen.getByTestId("coordinator-dead-letter-card");
      expect(card).toBeInTheDocument();
      expect(within(card).getByText("7")).toBeInTheDocument();
    });
  });

  it("renders success rate card with percentage", async () => {
    renderCoordinator();
    await waitFor(() => {
      const card = screen.getByTestId("coordinator-success-rate-card");
      expect(card).toBeInTheDocument();
      expect(card).toHaveTextContent(/%/);
    });
  });

  it("renders success rate bar", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByTestId("success-rate-bar")).toBeInTheDocument();
    });
  });

  // --- Dead-letter table ---

  it("renders dead-letter table with entries", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByTestId("dead-letters-table")).toBeInTheDocument();
    });
  });

  it("renders dead-letter table column headers", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByText("ID")).toBeInTheDocument();
      expect(screen.getByText("Conversation")).toBeInTheDocument();
      expect(screen.getByText("Error")).toBeInTheDocument();
      expect(screen.getByText("Time")).toBeInTheDocument();
      expect(screen.getByText("Actions")).toBeInTheDocument();
    });
  });

  it("shows dead-letter error messages", async () => {
    renderCoordinator();
    await waitFor(() => {
      // These match the actual DEAD_LETTERS_MOCK data in handlers.ts
      expect(screen.getByText("Connection timeout to external API")).toBeInTheDocument();
    });
  });

  it("shows dead-letter conversation IDs", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByText("conv-fail-001")).toBeInTheDocument();
      expect(screen.getByText("conv-fail-002")).toBeInTheDocument();
    });
  });

  // --- Replay button verifies API called ---

  it("calls replay API when replay button is clicked", async () => {
    let replayCalled = false;
    server.use(
      http.post("*/administration/coordinator/dead-letters/:id/replay", () => {
        replayCalled = true;
        return new HttpResponse(null, { status: 200 });
      })
    );

    renderCoordinator();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("replay-1")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("replay-1"));

    await waitFor(() => {
      expect(replayCalled).toBe(true);
    });
  });

  // --- Discard button verifies API called ---

  it("calls discard API when discard button is clicked", async () => {
    let discardCalled = false;
    server.use(
      http.delete("*/administration/coordinator/dead-letters/:id", () => {
        discardCalled = true;
        return new HttpResponse(null, { status: 204 });
      })
    );

    renderCoordinator();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("discard-1")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("discard-1"));

    await waitFor(() => {
      expect(discardCalled).toBe(true);
    });
  });

  // --- Payload toggle ---

  it("toggles payload visibility when payload button is clicked", async () => {
    renderCoordinator();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("toggle-payload-1")).toBeInTheDocument();
    });

    // Initially payload content should not be visible
    expect(screen.queryByText(/conv-fail-001/i)).toBeInTheDocument(); // The conversation ID shows in table
    // But the JSON payload expansion is not visible
    expect(screen.queryByTestId("payload-content-1")).not.toBeInTheDocument();

    await user.click(screen.getByTestId("toggle-payload-1"));

    // After toggle, payload JSON should be visible
    await waitFor(() => {
      expect(screen.getByTestId("payload-content-1")).toBeInTheDocument();
    });

    // Toggle again to hide
    await user.click(screen.getByTestId("toggle-payload-1"));

    await waitFor(() => {
      expect(screen.queryByTestId("payload-content-1")).not.toBeInTheDocument();
    });
  });

  // --- Purge all flow ---

  it("shows purge button and purge confirmation flow", async () => {
    renderCoordinator();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("purge-dead-letters-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("purge-dead-letters-btn"));

    await waitFor(() => {
      expect(screen.getByText("Purge all?")).toBeInTheDocument();
      expect(screen.getByText("Yes")).toBeInTheDocument();
      expect(screen.getByText("Cancel")).toBeInTheDocument();
    });
  });

  it("hides purge confirmation when cancel is clicked", async () => {
    renderCoordinator();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("purge-dead-letters-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("purge-dead-letters-btn"));

    await waitFor(() => {
      expect(screen.getByText("Purge all?")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Cancel"));

    await waitFor(() => {
      expect(screen.queryByText("Purge all?")).not.toBeInTheDocument();
      expect(screen.getByTestId("purge-dead-letters-btn")).toBeInTheDocument();
    });
  });

  it("calls purge API when Yes is confirmed", async () => {
    let purgeCalled = false;
    server.use(
      http.delete("*/administration/coordinator/dead-letters", () => {
        purgeCalled = true;
        return HttpResponse.json(3);
      })
    );

    renderCoordinator();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("purge-dead-letters-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("purge-dead-letters-btn"));

    await waitFor(() => {
      expect(screen.getByText("Yes")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Yes"));

    await waitFor(() => {
      expect(purgeCalled).toBe(true);
    });
  });

  // --- Refresh interval ---

  it("renders refresh interval selector with 10s default", async () => {
    renderCoordinator();
    await waitFor(() => {
      const select = screen.getByTestId("refresh-interval") as HTMLSelectElement;
      expect(select.value).toBe("10");
    });
  });

  it("changing refresh interval updates selector value", async () => {
    renderCoordinator();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("refresh-interval")).toBeInTheDocument();
    });

    const select = screen.getByTestId("refresh-interval") as HTMLSelectElement;
    await user.selectOptions(select, "30");
    expect(select.value).toBe("30");

    await user.selectOptions(select, "5");
    expect(select.value).toBe("5");
  });

  // --- Active queues ---

  it("shows active queues section with queue entries", async () => {
    renderCoordinator();
    await waitFor(() => {
      const queuesSection = screen.getByTestId("coordinator-queues");
      expect(queuesSection).toBeInTheDocument();
      expect(screen.getByText("Active Queues")).toBeInTheDocument();
      // Mock has 12 queue entries including conv-abc123
      expect(within(queuesSection).getByText("conv-abc123")).toBeInTheDocument();
    });
  });

  it("shows pending count in active queues", async () => {
    renderCoordinator();
    await waitFor(() => {
      // 3+1+2+4+1+2+1+3+2+1+2+1 = 23 total pending
      const queuesSection = screen.getByTestId("coordinator-queues");
      expect(within(queuesSection).getByText(/pending/)).toBeInTheDocument();
    });
  });

  // --- Dead-letter section title ---

  it("shows dead-letter queue section title", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByText("Dead-Letter Queue")).toBeInTheDocument();
    });
  });

  // --- Empty dead-letter state ---

  it("shows empty dead-letter state when no entries", async () => {
    server.use(
      http.get("*/administration/coordinator/dead-letters", () => {
        return HttpResponse.json([]);
      })
    );

    renderCoordinator();

    await waitFor(() => {
      expect(screen.getByTestId("dead-letters-empty")).toBeInTheDocument();
      expect(screen.getByText("No dead-letter entries")).toBeInTheDocument();
    });
  });

  // --- No active queues ---

  it("shows no active queues message when empty", async () => {
    server.use(
      http.get("*/administration/coordinator/status", () => {
        return HttpResponse.json({
          coordinatorType: "inMemory",
          connected: true,
          connectionStatus: "CONNECTED",
          activeConversations: 0,
          totalProcessed: 0,
          totalDeadLettered: 0,
          queueDepths: {},
        });
      })
    );

    renderCoordinator();

    await waitFor(() => {
      expect(screen.getByText(/No active conversations being processed/)).toBeInTheDocument();
    });
  });

  // --- In-Memory coordinator type ---

  it("shows In-Memory when coordinator type is inMemory", async () => {
    server.use(
      http.get("*/administration/coordinator/status", () => {
        return HttpResponse.json({
          coordinatorType: "inMemory",
          connected: false,
          connectionStatus: "DISCONNECTED",
          activeConversations: 0,
          totalProcessed: 50,
          totalDeadLettered: 3,
          queueDepths: {},
        });
      })
    );

    renderCoordinator();

    await waitFor(() => {
      expect(screen.getByText("In-Memory")).toBeInTheDocument();
    });
  });

  // --- No coordinator data ---

  it("shows empty state when coordinator status API fails", async () => {
    server.use(
      http.get("*/administration/coordinator/status", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );

    renderCoordinator();

    await waitFor(() => {
      expect(screen.getByText(/No coordinator data available/)).toBeInTheDocument();
    });
  });

  // --- Error categories (based on actual dead letter data) ---

  it("shows error category breakdown with rate-related category", async () => {
    renderCoordinator();
    await waitFor(() => {
      // DEAD_LETTERS_MOCK has "rate limit" in entry 2 → "Rate Limited" category
      expect(screen.getByText(/Rate Limited/)).toBeInTheDocument();
    });
  });

  // --- DISCONNECTED status ---

  it("shows disconnected status badge", async () => {
    server.use(
      http.get("*/administration/coordinator/status", () => {
        return HttpResponse.json({
          coordinatorType: "nats",
          connected: false,
          connectionStatus: "DISCONNECTED",
          activeConversations: 0,
          totalProcessed: 100,
          totalDeadLettered: 0,
          queueDepths: {},
        });
      })
    );

    renderCoordinator();

    await waitFor(() => {
      expect(screen.getByText("DISCONNECTED")).toBeInTheDocument();
    });
  });
});
