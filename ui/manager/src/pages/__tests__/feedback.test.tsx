import { describe, expect, it, vi } from "vitest";
import { act, fireEvent, screen, waitFor, within, render } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { toast } from "sonner";
import { server } from "@/test/mocks/server";
import { createTestQueryClient, renderPage } from "@/test/test-utils";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AuthContext, type AuthContextValue } from "@/components/auth/auth-context";
import { CHANGED_RATING_ID, ratingFixtures } from "@/test/mocks/rating-handlers";
import { FeedbackPage } from "../feedback";

const editorAuth: AuthContextValue = {
  authenticated: true,
  loading: false,
  user: null,
  roles: ["eddi-editor"],
  method: "keycloak",
  login: () => {},
  logout: () => {},
};

/** Exposes the current URL so filter tests can assert it. */
function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.search}</span>;
}

function renderFeedback(path = "/manage/feedback") {
  return renderPage(path, <><FeedbackPage /><LocationProbe /></>, "/manage/feedback");
}

function renderAsEditor(path = "/manage/feedback") {
  const client = createTestQueryClient();
  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={client}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <AuthContext.Provider value={editorAuth}>
            <Routes>
              <Route path="/manage/feedback" element={<FeedbackPage />} />
            </Routes>
          </AuthContext.Provider>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("FeedbackPage", () => {
  it("opens on 'Needs attention': NEW ratings that are low or carry a comment", async () => {
    renderFeedback();
    expect(screen.getByTestId("feedback-tab-attention")).toHaveAttribute("aria-selected", "true");
    // r1 (2★ + comment), r3 (4★ + comment), r4 (thumbs down) — not r2 (5★, no comment) nor r5 (reviewed).
    expect(await screen.findByTestId("rating-row-r1")).toBeInTheDocument();
    expect(screen.getByTestId("rating-row-r3")).toBeInTheDocument();
    expect(screen.getByTestId("rating-row-r4")).toBeInTheDocument();
    expect(screen.queryByTestId("rating-row-r2")).toBeNull();
    expect(screen.queryByTestId("rating-row-r5")).toBeNull();
  });

  it("renders comments as plain text, never as markup or a formula", async () => {
    server.use(
      http.get("*/ratings", () =>
        HttpResponse.json({
          total: 1,
          index: 0,
          limit: 25,
          ratings: [{ ...ratingFixtures()[0], comment: "<img src=x onerror=alert(1)> =HYPERLINK(\"x\")" }],
        }),
      ),
    );
    renderFeedback();
    const comment = await screen.findByTestId("rating-comment-r1");
    expect(comment.textContent).toBe("<img src=x onerror=alert(1)> =HYPERLINK(\"x\")");
    expect(comment.querySelector("img")).toBeNull();
  });

  it("offers 'Open conversation' only where the transcript may be opened", async () => {
    renderFeedback();
    await screen.findByTestId("rating-row-r1");
    expect(screen.getByTestId("rating-open-r1")).toHaveAttribute("href", "/manage/conversationview/conv-1");
    expect(screen.queryByTestId("rating-open-r3")).toBeNull();
  });

  it("shows the user column only when the server sent user ids", async () => {
    server.use(
      http.get("*/ratings", () =>
        HttpResponse.json({ total: 1, index: 0, limit: 25, ratings: [{ ...ratingFixtures()[0], userId: null }] }),
      ),
    );
    renderFeedback();
    const table = await screen.findByTestId("ratings-table");
    expect(within(table).queryByText("User")).toBeNull();
  });

  it("marks a rating reviewed, and it leaves the attention list", async () => {
    renderFeedback();
    fireEvent.click(await screen.findByTestId("rating-review-r1"));
    await waitFor(() => expect(screen.queryByTestId("rating-row-r1")).toBeNull());
    expect(screen.getByTestId("rating-row-r3")).toBeInTheDocument();
  });

  it("answers a 409 RATING_CHANGED with a notice and a reload, not an error", async () => {
    const info = vi.spyOn(toast, "info");
    const changed = { ...ratingFixtures()[0], id: CHANGED_RATING_ID };
    let reads = 0;
    server.use(
      http.get("*/ratings", () => {
        reads += 1;
        return HttpResponse.json({ total: 1, index: 0, limit: 25, ratings: [changed] });
      }),
    );
    renderFeedback();
    fireEvent.click(await screen.findByTestId(`rating-review-${CHANGED_RATING_ID}`));
    await waitFor(() => expect(info).toHaveBeenCalled());
    await waitFor(() => expect(reads).toBeGreaterThan(1));
  });

  it("saves an internal note", async () => {
    renderFeedback();
    fireEvent.click(await screen.findByTestId("rating-note-btn-r1"));
    fireEvent.change(screen.getByTestId("rating-note-input"), { target: { value: "Called the customer back." } });
    fireEvent.click(screen.getByTestId("rating-note-save"));
    expect(await screen.findByTestId("rating-note-r1")).toHaveTextContent("Called the customer back.");
  });

  it("refuses a note over 2000 characters", async () => {
    renderFeedback();
    fireEvent.click(await screen.findByTestId("rating-note-btn-r1"));
    fireEvent.change(screen.getByTestId("rating-note-input"), { target: { value: "x".repeat(2001) } });
    expect(screen.getByTestId("rating-note-save")).toBeDisabled();
  });

  it("bulk-reviews the selected ratings", async () => {
    renderFeedback();
    await screen.findByTestId("rating-row-r1");
    fireEvent.click(screen.getByTestId("rating-select-r1"));
    fireEvent.click(screen.getByTestId("rating-select-r4"));
    fireEvent.click(screen.getByTestId("feedback-review-selected"));
    await waitFor(() => expect(screen.queryByTestId("rating-row-r1")).toBeNull());
    expect(screen.queryByTestId("rating-row-r4")).toBeNull();
    expect(screen.getByTestId("rating-row-r3")).toBeInTheDocument();
  });

  it("marks everything needing attention reviewed through the filter endpoint", async () => {
    let query = "";
    server.use(
      http.post("*/ratings/review", ({ request }) => {
        query = new URL(request.url).search;
        return HttpResponse.json({ reviewed: 3 });
      }),
    );
    renderFeedback("/manage/feedback?agentId=agent1");
    await screen.findByTestId("rating-row-r1");
    fireEvent.click(screen.getByTestId("feedback-review-all"));
    await waitFor(() => expect(query).toContain("needsAttention=true"));
    expect(query).toContain("agentId=agent1");
  });

  it("'All ratings' shows KPIs, charts and the by-version table with low samples greyed", async () => {
    renderFeedback("/manage/feedback?tab=all&agentId=agent1");
    expect(await screen.findByTestId("feedback-overview")).toBeInTheDocument();
    expect(await screen.findByTestId("feedback-version-2")).toHaveAttribute("data-low-sample", "true");
    expect(screen.getByTestId("feedback-dist-star-2")).toBeInTheDocument();
    expect(screen.getByTestId("feedback-tag-Wrong answer")).toBeInTheDocument();
    expect(await screen.findByTestId("rating-row-r2")).toBeInTheDocument();
  });

  it("keeps filters in the URL, and a reason click filters by it", async () => {
    renderFeedback("/manage/feedback?tab=all");
    fireEvent.change(await screen.findByTestId("feedback-filter-kind"), { target: { value: "message" } });
    await waitFor(() => expect(screen.getByTestId("location").textContent).toContain("kind=message"));
    expect(await screen.findByTestId("rating-row-r4")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId("rating-row-r1")).toBeNull());

    fireEvent.click(await screen.findByTestId("feedback-tag-Too slow"));
    await waitFor(() => expect(screen.getByTestId("location").textContent).toContain("tag=Too+slow"));
  });

  it("asks a non-administrator to pick an agent instead of failing", async () => {
    let listed = false;
    server.use(
      http.get("*/ratings", () => {
        listed = true;
        return HttpResponse.json({ total: 0, index: 0, limit: 25, ratings: [] });
      }),
    );
    renderAsEditor();
    expect(await screen.findByTestId("empty-state")).toBeInTheDocument();
    expect(listed).toBe(false);
  });

  it("treats a backend AGENT_ID_REQUIRED as the same prompt", async () => {
    server.use(
      http.get("*/ratings", () => HttpResponse.json({ error: "AGENT_ID_REQUIRED", message: "agentId is required" }, { status: 400 })),
    );
    renderFeedback();
    expect(await screen.findByTestId("empty-state")).toBeInTheDocument();
    expect(screen.queryByTestId("error-state")).toBeNull();
  });

  it("explains how to enable ratings when there are none", async () => {
    server.use(http.get("*/ratings", () => HttpResponse.json({ total: 0, index: 0, limit: 25, ratings: [] })));
    renderFeedback("/manage/feedback?tab=all");
    const empty = await screen.findByTestId("empty-state");
    expect(empty.textContent).toContain("ratingConfig");
  });

  it("warns when the export was cut at the row cap", async () => {
    const warning = vi.spyOn(toast, "warning");
    const createUrl = vi.fn(() => "blob:x");
    Object.defineProperty(URL, "createObjectURL", { value: createUrl, configurable: true });
    server.use(
      http.get("*/ratings/export", () =>
        new HttpResponse("id\r\n", { headers: { "Content-Type": "text/csv", "X-Ratings-Truncated": "true" } }),
      ),
    );
    renderFeedback();
    await screen.findByTestId("rating-row-r1");
    await act(async () => {
      fireEvent.click(screen.getByTestId("feedback-export-btn"));
    });
    await waitFor(() => expect(warning).toHaveBeenCalled());
    expect(createUrl).toHaveBeenCalled();
  });
});
