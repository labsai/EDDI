import { beforeEach, describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { createTestQueryClient, renderPage } from "@/test/test-utils";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AuthContext, type AuthContextValue } from "@/components/auth/auth-context";
import { LOOKUP_CATEGORY, NOTES_CATEGORY, REVIEWED_SAMPLE_ID, resetOversightMocks } from "@/test/mocks/oversight-handlers";
import { OversightPage } from "../oversight";

const editorAuth: AuthContextValue = {
  authenticated: true,
  loading: false,
  user: null,
  roles: ["eddi-editor"],
  method: "keycloak",
  login: () => {},
  logout: () => {},
};

function renderOversight(path = "/manage/oversight") {
  return renderPage(path, <OversightPage />, "/manage/oversight");
}

describe("OversightPage", () => {
  beforeEach(() => resetOversightMocks());

  it("opens on analytics: KPI tiles and one row per category with its tier", async () => {
    renderOversight();
    expect(await screen.findByTestId("oversight-analytics")).toBeInTheDocument();
    expect(screen.getByTestId("oversight-kpi-decisions")).toHaveTextContent("40");
    expect(screen.getByTestId("oversight-kpi-unedited")).toHaveTextContent("89.5%");
    const notes = screen.getByTestId(`oversight-category-${NOTES_CATEGORY}`);
    await waitFor(() => expect(within(notes).getByTestId("oversight-tier-READY")).toBeInTheDocument());
    expect(within(screen.getByTestId(`oversight-category-${LOOKUP_CATEGORY}`)).getByTestId("oversight-tier-SUSPENDED")).toBeInTheDocument();
  });

  it("warns when more than a fifth of the samples expire unreviewed", async () => {
    server.use(
      http.get("*/hitl/analytics/summary", () =>
        HttpResponse.json({
          groupBy: "none",
          truncated: false,
          buckets: [
            {
              key: "all", total: 1, byOutcome: {}, humanDecisions: 0, uneditedApprovalRate: null, editRate: null, rejectionRate: null,
              topReasonCodes: [], latencyP50Ms: null, latencyP90Ms: null, latencyHistogram: [], timeoutShare: null, channelMix: {}, legacy: 0,
              samples: { sampled: 10, pending: 0, reviewed: 6, flagged: 0, expired: 4, expiredShare: 0.4, flaggedReasonCodes: [] },
            },
          ],
        }),
      ),
    );
    renderOversight();
    expect(await screen.findByTestId("oversight-capacity-warning")).toBeInTheDocument();
  });

  it("an editor must pick an agent first instead of meeting a 400", async () => {
    render(
      <MemoryRouter initialEntries={["/manage/oversight"]}>
        <QueryClientProvider client={createTestQueryClient()}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <AuthContext.Provider value={editorAuth}>
              <Routes>
                <Route path="/manage/oversight" element={<OversightPage />} />
              </Routes>
            </AuthContext.Provider>
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>,
    );
    expect(await screen.findByText(/one agent at a time/i)).toBeInTheDocument();
    expect(screen.queryByTestId("oversight-analytics")).toBeNull();
  });

  describe("audit samples", () => {
    it("lists pending samples with their redacted arguments and offers no bulk verdict", async () => {
      renderOversight("/manage/oversight?tab=samples");
      const row = await screen.findByTestId("oversight-sample-sample-1");
      expect(within(row).getByTestId("oversight-sample-args")).toHaveTextContent("Called back about the invoice");
      expect(screen.queryByRole("checkbox")).toBeNull();
      expect(screen.queryByText(/select all/i)).toBeNull();
    });

    it("a flag needs a reason code before it can be sent", async () => {
      renderOversight("/manage/oversight?tab=samples");
      const row = await screen.findByTestId("oversight-sample-sample-1");
      fireEvent.click(within(row).getByTestId("oversight-sample-flag"));
      const submit = within(row).getByTestId("oversight-sample-flag-submit");
      expect(submit).toBeDisabled();
      const select = within(row).getByTestId("oversight-sample-reason");
      await waitFor(() => expect(within(select).getAllByRole("option").length).toBeGreaterThan(1));
      fireEvent.change(select, { target: { value: "TONE" } });
      expect(submit).toBeEnabled();
      fireEvent.click(submit);
      await waitFor(() => expect(screen.queryByTestId("oversight-sample-sample-1")).toBeNull());
    });

    it("OK sends the version the reviewer saw", async () => {
      let body: unknown;
      server.use(
        http.patch("*/hitl/samples/:id", async ({ request }) => {
          body = await request.json();
          return HttpResponse.json({});
        }),
      );
      renderOversight("/manage/oversight?tab=samples");
      const row = await screen.findByTestId("oversight-sample-sample-1");
      fireEvent.click(within(row).getByTestId("oversight-sample-ok"));
      await waitFor(() => expect(body).toEqual({ verdict: "OK", expectedVersion: 1 }));
    });

    it("a sample reviewed meanwhile (409) is reported, not an error", async () => {
      renderOversight("/manage/oversight?tab=samples");
      const row = await screen.findByTestId(`oversight-sample-${REVIEWED_SAMPLE_ID}`);
      fireEvent.click(within(row).getByTestId("oversight-sample-ok"));
      // The list refreshes; the row stays because the mock keeps it pending.
      await waitFor(() => expect(screen.getByTestId(`oversight-sample-${REVIEWED_SAMPLE_ID}`)).toBeInTheDocument());
    });
  });

  describe("autonomy", () => {
    it("shows the recommendation's evidence and changes, and applies it against the reviewed version", async () => {
      let body: unknown;
      server.use(
        http.post("*/hitl/autonomy/:agentId/apply", async ({ request }) => {
          body = await request.json();
          expect(new URL(request.url).searchParams.get("category")).toBe(NOTES_CATEGORY);
          return HttpResponse.json({});
        }),
      );
      renderOversight("/manage/oversight?tab=autonomy");
      const card = await screen.findByTestId(`oversight-category-card-${NOTES_CATEGORY}`);
      expect(within(card).getByTestId(`oversight-category-card-${NOTES_CATEGORY}-evidence`)).toHaveTextContent("31");
      expect(within(card).getByTestId(`oversight-category-card-${NOTES_CATEGORY}-changes`)).toHaveTextContent("exempt");
      fireEvent.click(within(card).getByTestId(`oversight-category-card-${NOTES_CATEGORY}-apply`));
      await waitFor(() => expect(body).toEqual({ recommendationId: "rec-1", expectedAgentVersion: 3 }));
    });

    it("a promotion that leaves no gated tool needs a second click", async () => {
      server.use(
        http.get("*/hitl/autonomy", () =>
          HttpResponse.json({
            categories: [
              {
                agentId: "agent1", category: NOTES_CATEGORY, tier: "READY", pattern: "p", evidence: null, evidenceStatus: null,
                state: {
                  agentId: "agent1", category: NOTES_CATEGORY, evidenceResetAt: null, lastDemotionAt: null, version: 1, suspension: null,
                  recommendation: {
                    id: "rec-x", kind: "PROMOTE", status: "OPEN", computedAt: "2026-11-02T00:00:00Z", basedOnAgentVersion: 3, evidence: null,
                    pattern: "p", applicable: true, proposedHitlConfig: null, changes: ["After this the agent has no gated tools"], leavesNoGate: true,
                  },
                },
              },
            ],
          }),
        ),
      );
      let applied = false;
      server.use(http.post("*/hitl/autonomy/:agentId/apply", () => {
        applied = true;
        return HttpResponse.json({});
      }));
      renderOversight("/manage/oversight?tab=autonomy");
      const apply = await screen.findByTestId(`oversight-category-card-${NOTES_CATEGORY}-apply`);
      fireEvent.click(apply);
      expect(await screen.findByTestId(`oversight-category-card-${NOTES_CATEGORY}-no-gate-warning`)).toBeInTheDocument();
      expect(applied).toBe(false);
      fireEvent.click(apply);
      await waitFor(() => expect(applied).toBe(true));
    });

    it("lifting a suspension needs a note", async () => {
      renderOversight("/manage/oversight?tab=autonomy");
      const card = await screen.findByTestId(`oversight-category-card-${LOOKUP_CATEGORY}`);
      expect(within(card).getByTestId(`oversight-category-card-${LOOKUP_CATEGORY}-suspension`)).toHaveTextContent("http.get:/crm/*");
      const lift = within(card).getByTestId(`oversight-category-card-${LOOKUP_CATEGORY}-lift`);
      expect(lift).toBeDisabled();
      fireEvent.change(within(card).getByTestId(`oversight-category-card-${LOOKUP_CATEGORY}-note`), { target: { value: "checked" } });
      expect(lift).toBeEnabled();
      fireEvent.click(lift);
      await waitFor(() => expect(screen.queryByTestId(`oversight-category-card-${LOOKUP_CATEGORY}`)).toBeNull());
    });
  });
});
