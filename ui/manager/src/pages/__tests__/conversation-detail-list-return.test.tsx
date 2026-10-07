import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ConversationDetailPage } from "@/pages/conversation-detail";
import { server } from "@/test/mocks/server";

function renderDetail(state?: unknown) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <MemoryRouter
      initialEntries={[{ pathname: "/manage/conversationview/conv1", state }]}
    >
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route
              path="/manage/conversationview/:id"
              element={<ConversationDetailPage />}
            />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("ConversationDetailPage — returning to the filtered list", () => {
  it("links Back to the list the user came from, filters intact", async () => {
    renderDetail({ from: "/manage/conversations?state=ENDED&page=3" });

    const back = await screen.findByText("Back to Conversations");
    expect(back.closest("a")).toHaveAttribute(
      "href",
      "/manage/conversations?state=ENDED&page=3",
    );
  });

  it("falls back to the bare list when it was opened directly", async () => {
    renderDetail();
    const back = await screen.findByText("Back to Conversations");
    expect(back.closest("a")).toHaveAttribute("href", "/manage/conversations");
  });

  it("ignores a `from` that is not a conversations path", async () => {
    renderDetail({ from: "https://evil.example/x" });
    const back = await screen.findByText("Back to Conversations");
    expect(back.closest("a")).toHaveAttribute("href", "/manage/conversations");
  });
});

describe("ConversationDetailPage — accessibility and export", () => {
  it("names the delete button and the transcript search, and counts matches", async () => {
    renderDetail();
    const user = userEvent.setup();

    expect(await screen.findByTestId("delete-conversation-btn")).toHaveAccessibleName(
      "Delete conversation",
    );
    const search = screen.getByTestId("transcript-search");
    expect(search).toHaveAccessibleName("Search the transcript");

    await user.type(search, "order");
    await waitFor(() =>
      expect(screen.getByTestId("transcript-match-count")).toHaveTextContent(
        /Matching steps: \d+/,
      ),
    );
  });
});

describe("ConversationDetailPage — in-progress polling", () => {
  it("re-fetches while the conversation is IN_PROGRESS", async () => {
    let calls = 0;
    server.use(
      http.get("*/conversationstore/conversations/simple/:id", () => {
        calls += 1;
        return HttpResponse.json({
          agentId: "agent1",
          agentVersion: 1,
          conversationId: "conv1",
          conversationState: "IN_PROGRESS",
          environment: "production",
          conversationSteps: [],
          conversationOutputs: [],
        });
      }),
    );
    renderDetail();
    await waitFor(() => expect(calls).toBe(1));
    await waitFor(() => expect(calls).toBeGreaterThanOrEqual(2), {
      timeout: 6000,
    });
  }, 10000);
});
