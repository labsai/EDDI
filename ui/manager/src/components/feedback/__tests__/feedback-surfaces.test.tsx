import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { createTestQueryClient, renderWithProviders } from "@/test/test-utils";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AuthContext, type AuthContextValue } from "@/components/auth/auth-context";
import { ratingFixtures } from "@/test/mocks/rating-handlers";
import { Sidebar } from "@/components/layout/sidebar";
import { DashboardPage } from "@/pages/dashboard";
import { ConversationDetailPage } from "@/pages/conversation-detail";
import { AgentFeedbackStrip } from "../agent-feedback-strip";

const editorAuth: AuthContextValue = {
  authenticated: true,
  loading: false,
  user: null,
  roles: ["eddi-editor"],
  method: "keycloak",
  login: () => {},
  logout: () => {},
};

function withAuth(ui: React.ReactElement, auth: AuthContextValue) {
  const client = createTestQueryClient();
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <AuthContext.Provider value={auth}>{ui}</AuthContext.Provider>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("Sidebar feedback badge", () => {
  it("shows the attention count, like the approvals badge", async () => {
    server.use(http.get("*/ratings/attention-count", () => HttpResponse.json({ count: 7 })));
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />);
    const badge = await screen.findByTestId("nav-feedback-badge");
    await waitFor(() => expect(badge.textContent).toBe("7"));
  });

  it("caps the number at 99+", async () => {
    server.use(http.get("*/ratings/attention-count", () => HttpResponse.json({ count: 140 })));
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />);
    await waitFor(() => expect(screen.getByTestId("nav-feedback-badge").textContent).toBe("99+"));
  });

  it("is absent at zero", async () => {
    let asked = false;
    server.use(
      http.get("*/ratings/attention-count", () => {
        asked = true;
        return HttpResponse.json({ count: 0 });
      }),
    );
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />);
    await waitFor(() => expect(asked).toBe(true));
    expect(screen.queryByTestId("nav-feedback-badge")).toBeNull();
  });

  it("does not ask for an all-agent count on behalf of a non-administrator", async () => {
    let asked = false;
    server.use(
      http.get("*/ratings/attention-count", () => {
        asked = true;
        return HttpResponse.json({ count: 3 });
      }),
    );
    withAuth(<Sidebar collapsed={false} onToggle={() => {}} />, editorAuth);
    await screen.findAllByRole("link");
    expect(asked).toBe(false);
    expect(screen.queryByTestId("nav-feedback-badge")).toBeNull();
  });
});

describe("Dashboard feedback tile", () => {
  it("appears when ratings need attention", async () => {
    server.use(http.get("*/ratings/attention-count", () => HttpResponse.json({ count: 4 })));
    renderWithProviders(<DashboardPage />);
    expect(await screen.findByTestId("feedback-attention-card")).toBeInTheDocument();
    expect(screen.getByTestId("feedback-attention-count").textContent).toContain("4");
  });

  it("stays away at zero", async () => {
    server.use(http.get("*/ratings/attention-count", () => HttpResponse.json({ count: 0 })));
    renderWithProviders(<DashboardPage />);
    await screen.findByTestId("platform-health-strip");
    expect(screen.queryByTestId("feedback-attention-card")).toBeNull();
  });
});

describe("Agent feedback strip", () => {
  it("summarizes the last 30 days and links to the filtered Feedback page", async () => {
    renderWithProviders(<AgentFeedbackStrip agentId="agent1" ratingsEnabled />);
    const strip = await screen.findByTestId("agent-feedback-strip");
    expect(strip).toHaveAttribute("href", "/manage/feedback?agentId=agent1&tab=all");
    await waitFor(() => expect(screen.getByTestId("strip-count").textContent).toBe("5"));
  });

  it("renders nothing for an agent without ratings that does not ask for them", async () => {
    server.use(
      http.get("*/ratings/summary", () =>
        HttpResponse.json({ groupBy: "none", buckets: [{ bucket: "all", count: 0, avgNormalized: null, distribution: { thumbsUp: 0, thumbsDown: 0, stars: {} }, csat: null, thumbsUpRate: null, commentCount: 0, topTags: [] }] }),
      ),
    );
    const { container } = renderWithProviders(<AgentFeedbackStrip agentId="agent9" ratingsEnabled={false} />);
    await waitFor(() => expect(container.querySelector("[data-testid=agent-feedback-strip]")).toBeNull());
  });

  it("renders nothing when the caller may not read the ratings, and does not poll the count", async () => {
    let counted = false;
    server.use(
      http.get("*/ratings/summary", () => new HttpResponse(null, { status: 403 })),
      http.get("*/ratings/attention-count", () => {
        counted = true;
        return HttpResponse.json({ count: 1 });
      }),
    );
    const { container } = renderWithProviders(<AgentFeedbackStrip agentId="agent1" ratingsEnabled />);
    await waitFor(() => expect(container.querySelector("[data-testid=agent-feedback-strip]")).toBeNull());
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(counted).toBe(false);
  });

  it("fetches nothing for a role that may not open the Feedback page", async () => {
    let asked = false;
    server.use(
      http.get("*/ratings/*", () => {
        asked = true;
        return HttpResponse.json({});
      }),
    );
    const { container } = withAuth(<AgentFeedbackStrip agentId="agent1" ratingsEnabled />, { ...editorAuth, roles: ["eddi-viewer"] });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(asked).toBe(false);
    expect(container.querySelector("[data-testid=agent-feedback-strip]")).toBeNull();
  });
});

describe("Conversation detail ratings", () => {
  function renderDetail() {
    const client = createTestQueryClient();
    return render(
      <MemoryRouter initialEntries={["/manage/conversationview/conv1"]}>
        <QueryClientProvider client={client}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <Routes>
              <Route path="/manage/conversationview/:id" element={<ConversationDetailPage />} />
            </Routes>
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>,
    );
  }

  it("shows the conversation rating with its review status, and reply thumbs at their step", async () => {
    let query = "";
    const [conversation, , , reply] = ratingFixtures();
    server.use(
      http.get("*/ratings", ({ request }) => {
        query = new URL(request.url).search;
        return HttpResponse.json({
          total: 2,
          index: 0,
          limit: 100,
          ratings: [
            { ...conversation!, conversationId: "conv1" },
            { ...reply!, conversationId: "conv1", stepIndex: 1 },
          ],
        });
      }),
    );
    renderDetail();
    const panel = await screen.findByTestId("conversation-rating-panel");
    expect(panel.textContent).toContain("It kept looping on my order number.");
    expect(await screen.findByTestId("step-rating-1")).toBeInTheDocument();
    expect(query).toContain("conversationId=conv1");
    expect(query).toContain("agentId=agent1");
  });

  it("shows nothing about ratings to a caller the list refuses", async () => {
    server.use(http.get("*/ratings", () => new HttpResponse(null, { status: 403 })));
    renderDetail();
    await screen.findByTestId("conversation-chat");
    expect(screen.queryByTestId("conversation-rating-panel")).toBeNull();
  });
});
