import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ConversationsPage } from "@/pages/conversations";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

/** Capture every conversation-list request's query params. */
let requests: URLSearchParams[] = [];

/** Handler that always returns a *full* page of `limit` rows so the "Next"
 *  button stays enabled (offset pagination has no total count). */
function useCapturingListHandler() {
  server.use(
    http.get("*/conversationstore/conversations", ({ request }) => {
      const params = new URL(request.url).searchParams;
      requests.push(params);
      const limit = Number(params.get("limit") ?? "20");
      const index = Number(params.get("index") ?? "0");
      const rows = Array.from({ length: limit }, (_, i) => ({
        resource: `eddi://ai.labs.conversation/conversationstore/conversations/p${index}-c${i}`,
        name: "",
        description: "",
        createdOn: Date.now(),
        lastModifiedOn: Date.now(),
        agentId: "agent1",
        agentVersion: 3,
        conversationState: "READY",
        viewState: "UNSEEN",
        conversationStepSize: 1,
        environment: "production",
        agentName: "Support Agent",
      }));
      return HttpResponse.json(rows);
    })
  );
}

function lastRequest() {
  return requests[requests.length - 1]!;
}

describe("ConversationsPage — pagination", () => {
  beforeEach(() => {
    requests = [];
  });

  it("starts at index 0 and advances the index when Next is clicked", async () => {
    useCapturingListHandler();
    renderWithProviders(<ConversationsPage />);
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });
    // First page requested with index 0
    expect(lastRequest().get("index")).toBe("0");
    // Default page size drives the limit (50)
    expect(lastRequest().get("limit")).toBe("50");

    await user.click(screen.getByTestId("pagination-next"));

    await waitFor(() => {
      expect(lastRequest().get("index")).toBe("1");
    });
    // Page indicator reflects the new page
    expect(screen.getByTestId("page-indicator")).toHaveTextContent("2");
  });

  it("returns to a lower index when Previous is clicked", async () => {
    useCapturingListHandler();
    renderWithProviders(<ConversationsPage />);
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByTestId("conversation-grid")).toBeInTheDocument());
    await user.click(screen.getByTestId("pagination-next"));
    await waitFor(() => expect(lastRequest().get("index")).toBe("1"));

    await user.click(screen.getByTestId("pagination-prev"));
    await waitFor(() => expect(lastRequest().get("index")).toBe("0"));
  });

  it("changing page size updates the limit and resets to index 0", async () => {
    useCapturingListHandler();
    renderWithProviders(<ConversationsPage />);
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByTestId("conversation-grid")).toBeInTheDocument());
    // Advance a page first so we can prove the reset.
    await user.click(screen.getByTestId("pagination-next"));
    await waitFor(() => expect(lastRequest().get("index")).toBe("1"));

    await user.selectOptions(screen.getByTestId("page-size-select"), "100");

    await waitFor(() => {
      expect(lastRequest().get("limit")).toBe("100");
      expect(lastRequest().get("index")).toBe("0");
    });
  });
});

describe("ConversationsPage — filters", () => {
  beforeEach(() => {
    requests = [];
  });

  it("passes conversationState=EXECUTION_INTERRUPTED when the Interrupted pill is clicked", async () => {
    useCapturingListHandler();
    renderWithProviders(<ConversationsPage />);
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByTestId("conversation-grid")).toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: "Interrupted" }));

    await waitFor(() => {
      expect(lastRequest().get("conversationState")).toBe("EXECUTION_INTERRUPTED");
    });
  });

  it("passes the agentId query param when an agent is chosen in the filter", async () => {
    useCapturingListHandler();
    renderWithProviders(<ConversationsPage />);
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByTestId("conversation-grid")).toBeInTheDocument());

    // The AgentPicker accepts a typed id via Enter.
    const agentInput = screen.getByPlaceholderText("Filter by agent");
    await user.type(agentInput, "agent1");
    await user.keyboard("{Enter}");

    await waitFor(() => {
      expect(lastRequest().get("agentId")).toBe("agent1");
    });
  });
});
