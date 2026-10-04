import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ConversationsPage } from "@/pages/conversations";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

let requests: URLSearchParams[] = [];

function row(i: number) {
  return {
    resource: `eddi://ai.labs.conversation/conversationstore/conversations/c${i}`,
    name: "",
    description: "",
    createdOn: 1,
    lastModifiedOn: 1,
    agentId: "agent1",
    agentVersion: 1,
    conversationState: "READY",
    viewState: "SEEN",
    conversationStepSize: 1,
    environment: "production",
  };
}

/** `fullPages` pages of `limit` rows, then empty. */
function useListHandler(fullPages: number) {
  server.use(
    http.get("*/conversationstore/conversations", ({ request }) => {
      const params = new URL(request.url).searchParams;
      requests.push(params);
      const limit = Number(params.get("limit"));
      const index = Number(params.get("index"));
      return HttpResponse.json(
        index < fullPages ? Array.from({ length: limit }, (_, i) => row(i)) : []
      );
    })
  );
}

const last = () => requests[requests.length - 1]!;

describe("ConversationsPage — URL state and the end of the list", () => {
  beforeEach(() => {
    requests = [];
  });

  it("restores filters and page from the URL", async () => {
    useListHandler(10);
    renderWithProviders(<ConversationsPage />, {
      initialRoute:
        "/manage/conversations?q=hello&state=ENDED&agent=agent1&page=3&size=25",
    });

    await waitFor(() => expect(requests.length).toBeGreaterThan(0));
    expect(last().get("filter")).toBe("hello");
    expect(last().get("conversationState")).toBe("ENDED");
    expect(last().get("agentId")).toBe("agent1");
    expect(last().get("index")).toBe("2");
    expect(last().get("limit")).toBe("25");
  });

  it("writes a state-filter change to the URL and resets to the first page", async () => {
    useListHandler(10);
    renderWithProviders(<ConversationsPage />, {
      initialRoute: "/manage/conversations?page=3",
    });
    const user = userEvent.setup();
    await waitFor(() => expect(requests.length).toBeGreaterThan(0));
    expect(last().get("index")).toBe("2");

    await user.click(screen.getByRole("button", { name: "Ended" }));

    await waitFor(() => {
      expect(last().get("conversationState")).toBe("ENDED");
      expect(last().get("index")).toBe("0");
    });
  });

  it("does not nest the card's delete button inside the card link", async () => {
    useListHandler(1);
    renderWithProviders(<ConversationsPage />);
    await waitFor(() =>
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument()
    );

    const del = screen.getAllByRole("button", { name: "Delete conversation" })[0]!;
    expect(del.closest("a")).toBeNull();
    expect(screen.getAllByTestId(/^conversation-card-/)[0]!.tagName).toBe("A");
  });

  it("shows 'No more results' with a way back, not the deploy-an-agent empty state, on an empty later page", async () => {
    useListHandler(1);
    renderWithProviders(<ConversationsPage />);
    const user = userEvent.setup();
    await waitFor(() =>
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument()
    );

    await user.click(screen.getByTestId("pagination-next"));

    expect(await screen.findByText("No more results")).toBeInTheDocument();
    expect(screen.queryByText(/Deploy an agent/)).not.toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: "Back to previous page" })
    );
    await waitFor(() =>
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument()
    );
  });
});
