import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes, useSearchParams } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { createTestQueryClient, renderPage } from "@/test/test-utils";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AuthContext, type AuthContextValue } from "@/components/auth/auth-context";
import { Sidebar } from "@/components/layout/sidebar";
import { renderWithProviders } from "@/test/test-utils";
import { FAILING_CHANNEL, alertEventFixtures } from "@/test/mocks/alert-handlers";
import type { AlertRule } from "@/lib/api/alerts";
import { AlertsPage } from "../alerts";

const editorAuth: AuthContextValue = {
  authenticated: true,
  loading: false,
  user: null,
  roles: ["eddi-editor"],
  method: "keycloak",
  login: () => {},
  logout: () => {},
};

function renderAlerts(path = "/manage/alerts") {
  return renderPage(path, <AlertsPage />, "/manage/alerts");
}

function renderAsEditor(path = "/manage/alerts", ui: React.ReactElement = <AlertsPage />) {
  const client = createTestQueryClient();
  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={client}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <AuthContext.Provider value={editorAuth}>
            <Routes>
              <Route path="/manage/alerts" element={ui} />
            </Routes>
          </AuthContext.Provider>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("AlertsPage — inbox", () => {
  it("opens on the unacknowledged alerts, with what matched and the delivery per target", async () => {
    renderAlerts();
    expect(await screen.findByTestId("alert-event-row-alert-1")).toBeInTheDocument();
    expect(screen.getByTestId("alert-event-row-alert-2")).toBeInTheDocument();
    // alert-3 is acknowledged: not in the open inbox.
    expect(screen.queryByTestId("alert-event-row-alert-3")).toBeNull();
    expect(screen.getByTestId("alert-event-comment-alert-1").textContent).toBe("It kept looping on my order number.");
    expect(within(screen.getByTestId("alert-event-row-alert-2")).getByText("The webhook answered HTTP 503")).toBeInTheDocument();
  });

  it("renders a comment excerpt as plain text, never as markup", async () => {
    server.use(
      http.get("*/alerts/events", () =>
        HttpResponse.json({
          total: 1,
          index: 0,
          limit: 25,
          events: [
            {
              id: "x",
              ruleId: "r",
              ruleName: "r",
              source: "RATING",
              kind: "ALERT",
              agentId: "agent1",
              firedAt: new Date().toISOString(),
              summary: "s",
              match: { matched: ["comment"], commentExcerpt: "<img src=x onerror=alert(1)>" },
              deliveries: [],
              state: "INBOX_ONLY",
              duplicates: 0,
            },
          ],
        }),
      ),
    );
    renderAlerts();
    const comment = await screen.findByTestId("alert-event-comment-x");
    expect(comment.textContent).toBe("<img src=x onerror=alert(1)>");
    expect(comment.querySelector("img")).toBeNull();
  });

  it("acknowledges one alert, and it leaves the open inbox", async () => {
    renderAlerts();
    fireEvent.click(await screen.findByTestId("alert-acknowledge-alert-1"));
    await waitFor(() => expect(screen.queryByTestId("alert-event-row-alert-1")).toBeNull());
    expect(screen.getByTestId("alert-event-row-alert-2")).toBeInTheDocument();
  });

  it("acknowledges the selected alerts in bulk", async () => {
    let sent: unknown = null;
    server.use(
      http.post("*/alerts/events/acknowledge", async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json({ acknowledged: 2 });
      }),
    );
    renderAlerts();
    fireEvent.click(await screen.findByTestId("alert-events-select-all"));
    fireEvent.click(screen.getByTestId("alerts-ack-selected"));
    await waitFor(() => expect(sent).toEqual({ ids: ["alert-1", "alert-2"] }));
  });

  it("offers Retry only for a failed delivery, and retries it", async () => {
    renderAlerts();
    expect(await screen.findByTestId("alert-retry-alert-2")).toBeInTheDocument();
    expect(screen.queryByTestId("alert-retry-alert-1")).toBeNull();
    fireEvent.click(screen.getByTestId("alert-retry-alert-2"));
    await waitFor(() => expect(screen.getByTestId("alert-event-state-alert-2").textContent).toBe("Delivering"));
  });

  it("filters by source and can show acknowledged alerts", async () => {
    renderAlerts();
    await screen.findByTestId("alert-event-row-alert-1");
    fireEvent.change(screen.getByTestId("alerts-filter-source"), { target: { value: "CONVERSATION_ERROR" } });
    await waitFor(() => expect(screen.queryByTestId("alert-event-row-alert-1")).toBeNull());
    fireEvent.click(screen.getByTestId("alerts-show-acknowledged"));
    expect(await screen.findByTestId("alert-event-row-alert-3")).toBeInTheDocument();
    expect(screen.getByTestId("alert-event-acknowledged-alert-3")).toBeInTheDocument();
  });

  it("moves back to the last page that exists when acknowledging empties the page it is on", async () => {
    // 26 open events: page 2 holds one. Acknowledging it leaves 25, so page 2 no longer exists.
    const base = alertEventFixtures()[0];
    let open = Array.from({ length: 26 }, (_, i) => ({ ...base, id: `gen-${i}`, acknowledgedAt: null, acknowledgedBy: null }));
    server.use(
      http.get("*/alerts/events", ({ request }) => {
        const url = new URL(request.url);
        const index = Number(url.searchParams.get("index") ?? 0);
        const limit = Number(url.searchParams.get("limit") ?? 25);
        return HttpResponse.json({ total: open.length, index, limit, events: open.slice(index, index + limit) });
      }),
      http.post("*/alerts/events/acknowledge", async ({ request }) => {
        const body = (await request.json()) as { ids: string[] };
        open = open.filter((e) => !body.ids.includes(e.id));
        return HttpResponse.json({ acknowledged: body.ids.length });
      }),
    );
    renderAlerts();
    await screen.findByTestId("alert-event-row-gen-0");
    fireEvent.click(screen.getByTestId("alerts-next"));
    expect(await screen.findByTestId("alert-event-row-gen-25")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("alert-event-select-gen-25"));
    fireEvent.click(screen.getByTestId("alerts-ack-selected"));
    expect(await screen.findByTestId("alert-event-row-gen-0")).toBeInTheDocument();
    expect(screen.queryByText("No open alerts")).toBeNull();
    expect(screen.queryByTestId("alerts-next")).toBeNull();
  });

  it("clears the selection when the source or the acknowledged filter changes", async () => {
    renderAlerts();
    fireEvent.click(await screen.findByTestId("alert-event-select-alert-1"));
    expect(screen.getByTestId("alerts-ack-selected")).toBeEnabled();
    fireEvent.change(screen.getByTestId("alerts-filter-source"), { target: { value: "RATING" } });
    expect(screen.getByTestId("alerts-ack-selected")).toBeDisabled();

    fireEvent.click(await screen.findByTestId("alert-event-select-alert-1"));
    expect(screen.getByTestId("alerts-ack-selected")).toBeEnabled();
    fireEvent.click(screen.getByTestId("alerts-show-acknowledged"));
    expect(screen.getByTestId("alerts-ack-selected")).toBeDisabled();
  });

  it("starts the inbox afresh when the agent changes: no selection carried over", async () => {
    function SwitchAgent() {
      const [, setParams] = useSearchParams();
      return (
        <button type="button" data-testid="switch-agent" onClick={() => setParams({ agentId: "6aba70011a25f8be7920e099" })}>
          switch
        </button>
      );
    }
    renderPage(
      "/manage/alerts",
      <>
        <AlertsPage />
        <SwitchAgent />
      </>,
      "/manage/alerts",
    );
    fireEvent.click(await screen.findByTestId("alert-event-select-alert-1"));
    expect(screen.getByTestId("alerts-ack-selected")).toBeEnabled();
    fireEvent.click(screen.getByTestId("switch-agent"));
    await waitFor(() => expect(screen.getByTestId("alerts-ack-selected")).toBeDisabled());
  });

  it("asks a non-administrator to pick an agent instead of listing everything", async () => {
    let asked = false;
    server.use(
      http.get("*/alerts/events", () => {
        asked = true;
        return HttpResponse.json({ total: 0, index: 0, limit: 25, events: [] });
      }),
    );
    renderAsEditor();
    expect(await screen.findByText("Pick an agent to see its alerts")).toBeInTheDocument();
    expect(asked).toBe(false);
  });
});

describe("AlertsPage — rules", () => {
  it("lists the rules", async () => {
    renderAlerts("/manage/alerts?tab=rules");
    expect(await screen.findByTestId("alert-rule-row-rule-ratings")).toBeInTheDocument();
    expect(screen.getByTestId("alert-rule-row-rule-errors")).toBeInTheDocument();
  });

  it("creates a rule with a Slack target; the body carries the source, scope, condition and throttle", async () => {
    let sent: AlertRule | null = null;
    server.use(
      http.post("*/alerts/rules", async ({ request }) => {
        sent = (await request.json()) as AlertRule;
        return HttpResponse.json({ ...sent, id: "rule-new" }, { status: 201 });
      }),
    );
    renderAlerts("/manage/alerts?tab=rules");
    fireEvent.click(await screen.findByTestId("alerts-new-rule"));
    expect(screen.getByTestId("alert-rule-save")).toBeDisabled(); // no name yet
    fireEvent.change(screen.getByTestId("alert-rule-name"), { target: { value: "Failing turns" } });
    fireEvent.change(screen.getByTestId("alert-rule-source"), { target: { value: "CONVERSATION_ERROR" } });
    fireEvent.click(screen.getByTestId("alert-rule-kind-COST_CEILING"));
    fireEvent.click(screen.getByTestId("alert-rule-add-target"));
    fireEvent.change(screen.getByTestId("alert-rule-target-channel-0"), { target: { value: "C0123ABC" } });
    fireEvent.click(screen.getByTestId("alert-rule-save"));
    await waitFor(() => expect(sent).not.toBeNull());
    expect(sent!.source).toBe("CONVERSATION_ERROR");
    expect(sent!.scope).toEqual({ allAgents: true, agentIds: [] });
    expect(sent!.condition.errorKinds).toEqual(["TURN_ERROR", "TURN_DEADLINE", "COST_CEILING"]);
    expect(sent!.delivery).toEqual([{ type: "SLACK", channelId: "C0123ABC" }]);
    expect(sent!.throttle).toEqual({ windowMinutes: 60, maxPerWindow: 10 });
  });

  it("refuses a throttle out of range: inline error on the field, Save and Send test disabled", async () => {
    renderAlerts("/manage/alerts?tab=rules");
    fireEvent.click(await screen.findByTestId("alerts-new-rule"));
    fireEvent.change(screen.getByTestId("alert-rule-name"), { target: { value: "Failing turns" } });
    fireEvent.change(screen.getByTestId("alert-rule-source"), { target: { value: "CONVERSATION_ERROR" } });
    fireEvent.click(screen.getByTestId("alert-rule-kind-COST_CEILING"));
    expect(screen.getByTestId("alert-rule-save")).toBeEnabled();

    fireEvent.change(screen.getByTestId("alert-rule-window"), { target: { value: "1441" } });
    expect(screen.getByTestId("alert-rule-window-error")).toBeInTheDocument();
    expect(screen.getByTestId("alert-rule-window")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByTestId("alert-rule-save")).toBeDisabled();
    expect(screen.getByTestId("alert-rule-test")).toBeDisabled();

    fireEvent.change(screen.getByTestId("alert-rule-window"), { target: { value: "1440" } });
    fireEvent.change(screen.getByTestId("alert-rule-max-per-window"), { target: { value: "2.5" } });
    expect(screen.queryByTestId("alert-rule-window-error")).toBeNull();
    expect(screen.getByTestId("alert-rule-max-per-window-error")).toBeInTheDocument();
    expect(screen.getByTestId("alert-rule-save")).toBeDisabled();

    fireEvent.change(screen.getByTestId("alert-rule-max-per-window"), { target: { value: "1000" } });
    expect(screen.queryByTestId("alert-rule-max-per-window-error")).toBeNull();
    expect(screen.getByTestId("alert-rule-save")).toBeEnabled();
  });

  it("builds an output-match condition: property path, operator by value type, value and includeValue", async () => {
    let sent: AlertRule | null = null;
    server.use(
      http.post("*/alerts/rules", async ({ request }) => {
        sent = (await request.json()) as AlertRule;
        return HttpResponse.json({ ...sent, id: "rule-om" }, { status: 201 });
      }),
    );
    renderAlerts("/manage/alerts?tab=rules");
    fireEvent.click(await screen.findByTestId("alerts-new-rule"));
    fireEvent.change(screen.getByTestId("alert-rule-name"), { target: { value: "Angry users" } });
    fireEvent.change(screen.getByTestId("alert-rule-source"), { target: { value: "OUTPUT_MATCH" } });
    fireEvent.change(screen.getByTestId("output-match-source"), { target: { value: "output.json" } });
    fireEvent.change(screen.getByTestId("output-match-path"), { target: { value: "sentiment" } });
    fireEvent.change(screen.getByTestId("output-match-type"), { target: { value: "list" } });
    expect(within(screen.getByTestId("output-match-op")).getAllByRole("option").map((o) => o.getAttribute("value"))).toEqual(["in"]);
    fireEvent.change(screen.getByTestId("output-match-value"), { target: { value: "angry, frustrated" } });
    fireEvent.click(screen.getByTestId("output-match-include-value"));
    fireEvent.click(screen.getByTestId("alert-rule-save"));
    await waitFor(() => expect(sent).not.toBeNull());
    expect(sent!.source).toBe("OUTPUT_MATCH");
    expect(sent!.condition).toEqual({ path: "output.json.sentiment", op: "in", value: ["angry", "frustrated"], includeValue: true });
  });

  it("flags an invalid path inline and blocks saving; actions offers contains and exists only", async () => {
    renderAlerts("/manage/alerts?tab=rules");
    fireEvent.click(await screen.findByTestId("alerts-new-rule"));
    fireEvent.change(screen.getByTestId("alert-rule-name"), { target: { value: "x" } });
    fireEvent.change(screen.getByTestId("alert-rule-source"), { target: { value: "OUTPUT_MATCH" } });
    fireEvent.change(screen.getByTestId("output-match-path"), { target: { value: "a.*" } });
    expect(screen.getByTestId("output-match-path-error")).toBeInTheDocument();
    expect(screen.getByTestId("alert-rule-save")).toBeDisabled();
    fireEvent.change(screen.getByTestId("output-match-source"), { target: { value: "actions" } });
    expect(screen.queryByTestId("output-match-path")).toBeNull();
    expect(within(screen.getByTestId("output-match-op")).getAllByRole("option").map((o) => o.getAttribute("value"))).toEqual([
      "contains",
      "exists",
    ]);
  });

  it("shows a stored webhook masked and says it is kept unless replaced", async () => {
    renderAlerts("/manage/alerts?tab=rules");
    fireEvent.click(await screen.findByTestId("alert-rule-edit-rule-errors"));
    expect((screen.getByTestId("alert-rule-target-url-0") as HTMLInputElement).value).toBe("https://hooks.example.com/***");
    expect(screen.getByTestId("alert-rule-target-url-kept-0")).toBeInTheDocument();
  });

  describe("webhook targets keep their targetId", () => {
    const twoHooks: AlertRule = {
      id: "rule-two",
      name: "Two hooks",
      enabled: true,
      scope: { allAgents: true, agentIds: [] },
      source: "CONVERSATION_ERROR",
      condition: { errorKinds: ["TURN_ERROR"] },
      delivery: [
        { type: "WEBHOOK", url: "https://hooks.example.com/***", signingSecret: "${vault:a}", urlConfigured: true, targetId: "tA" },
        { type: "WEBHOOK", url: "https://hooks.example.com/***", signingSecret: "${vault:b}", urlConfigured: true, targetId: "tB" },
      ],
      throttle: { windowMinutes: 60, maxPerWindow: 10 },
      digest: "HOURLY",
    };

    function serveTwoHooks() {
      const sent: { put?: AlertRule; test?: AlertRule } = {};
      server.use(
        http.get("*/alerts/rules", () => HttpResponse.json({ total: 1, index: 0, limit: 50, rules: [twoHooks] })),
        http.put("*/alerts/rules/:ruleId", async ({ request }) => {
          sent.put = (await request.json()) as AlertRule;
          return HttpResponse.json(sent.put);
        }),
        http.post("*/alerts/rules/test", async ({ request }) => {
          sent.test = (await request.json()) as AlertRule;
          return HttpResponse.json({ deliveries: [] });
        }),
      );
      return sent;
    }

    async function openTwoHooks() {
      renderAlerts("/manage/alerts?tab=rules");
      fireEvent.click(await screen.findByTestId("alert-rule-edit-rule-two"));
    }

    it("removing one of two masked webhooks on one host sends the other with its own targetId", async () => {
      const sent = serveTwoHooks();
      await openTwoHooks();
      fireEvent.click(screen.getByTestId("alert-rule-target-remove-0"));
      fireEvent.click(screen.getByTestId("alert-rule-save"));
      await waitFor(() => expect(sent.put).toBeDefined());
      expect(sent.put!.delivery.map((d) => [d.targetId, d.signingSecret])).toEqual([["tB", "${vault:b}"]]);
    });

    it("reordering keeps each target's targetId with it", async () => {
      const sent = serveTwoHooks();
      await openTwoHooks();
      fireEvent.click(screen.getByTestId("alert-rule-target-down-0"));
      fireEvent.click(screen.getByTestId("alert-rule-save"));
      await waitFor(() => expect(sent.put).toBeDefined());
      expect(sent.put!.delivery.map((d) => [d.targetId, d.signingSecret])).toEqual([
        ["tB", "${vault:b}"],
        ["tA", "${vault:a}"],
      ]);
    });

    it("editing a masked target's other fields keeps its targetId", async () => {
      const sent = serveTwoHooks();
      await openTwoHooks();
      fireEvent.change(screen.getByTestId("alert-rule-target-secret-1"), { target: { value: "${vault:b2}" } });
      fireEvent.click(screen.getByTestId("alert-rule-save"));
      await waitFor(() => expect(sent.put).toBeDefined());
      expect(sent.put!.delivery.map((d) => [d.targetId, d.signingSecret])).toEqual([
        ["tA", "${vault:a}"],
        ["tB", "${vault:b2}"],
      ]);
    });

    it("a new target goes without a targetId — the server assigns one", async () => {
      const sent = serveTwoHooks();
      await openTwoHooks();
      fireEvent.click(screen.getByTestId("alert-rule-add-target"));
      fireEvent.change(screen.getByTestId("alert-rule-target-channel-2"), { target: { value: "C0123ABC" } });
      fireEvent.click(screen.getByTestId("alert-rule-save"));
      await waitFor(() => expect(sent.put).toBeDefined());
      expect(sent.put!.delivery.map((d) => d.targetId ?? null)).toEqual(["tA", "tB", null]);
    });

    it("a test send of the saved rule sends the targetIds", async () => {
      const sent = serveTwoHooks();
      await openTwoHooks();
      fireEvent.click(screen.getByTestId("alert-rule-test"));
      await waitFor(() => expect(sent.test).toBeDefined());
      expect(sent.test!.id).toBe("rule-two");
      expect(sent.test!.delivery.map((d) => d.targetId)).toEqual(["tA", "tB"]);
    });

    it("the mock server restores the stored URL by targetId: editing and saving the stored webhook rule succeeds", async () => {
      renderAlerts("/manage/alerts?tab=rules");
      fireEvent.click(await screen.findByTestId("alert-rule-edit-rule-errors"));
      fireEvent.change(screen.getByTestId("alert-rule-name"), { target: { value: "Broken turns (renamed)" } });
      fireEvent.click(screen.getByTestId("alert-rule-save"));
      await waitFor(() => expect(screen.queryByTestId("alert-rule-editor")).toBeNull());
      expect(await screen.findByText("Broken turns (renamed)")).toBeInTheDocument();
    });
  });

  it("refuses an invalid Slack channel before sending anything", async () => {
    renderAlerts("/manage/alerts?tab=rules");
    fireEvent.click(await screen.findByTestId("alerts-new-rule"));
    fireEvent.change(screen.getByTestId("alert-rule-name"), { target: { value: "x" } });
    fireEvent.click(screen.getByTestId("alert-rule-add-target"));
    fireEvent.change(screen.getByTestId("alert-rule-target-channel-0"), { target: { value: "#general" } });
    expect(screen.getByTestId("alert-rule-save")).toBeDisabled();
    expect(screen.getByTestId("alert-rule-test")).toBeDisabled();
  });

  it("sends a test alert and shows the outcome per target", async () => {
    renderAlerts("/manage/alerts?tab=rules");
    fireEvent.click(await screen.findByTestId("alert-rule-edit-rule-ratings"));
    fireEvent.click(screen.getByTestId("alert-rule-add-target"));
    fireEvent.change(screen.getByTestId("alert-rule-target-channel-1"), { target: { value: FAILING_CHANNEL } });
    fireEvent.click(screen.getByTestId("alert-rule-test"));
    const results = await screen.findByTestId("alert-rule-test-results");
    expect(within(results).getByText(/Slack C0123ABC: delivered/)).toBeInTheDocument();
    expect(within(results).getByText(/Slack refused the message/)).toBeInTheDocument();
  });

  it("shows two targets with the same label as two rows, without a duplicate-key warning", async () => {
    const errors = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      renderAlerts("/manage/alerts?tab=rules");
      fireEvent.click(await screen.findByTestId("alert-rule-edit-rule-ratings"));
      fireEvent.click(screen.getByTestId("alert-rule-add-target"));
      fireEvent.change(screen.getByTestId("alert-rule-target-channel-1"), { target: { value: "C0123ABC" } });
      fireEvent.click(screen.getByTestId("alert-rule-test"));
      const results = await screen.findByTestId("alert-rule-test-results");
      await waitFor(() => expect(within(results).getAllByText(/Slack C0123ABC: delivered/)).toHaveLength(2));
      expect(errors.mock.calls.some((call) => String(call[0]).includes("same key"))).toBe(false);
    } finally {
      errors.mockRestore();
    }
  });

  it("deletes a rule after confirmation", async () => {
    renderAlerts("/manage/alerts?tab=rules");
    fireEvent.click(await screen.findByTestId("alert-rule-delete-rule-ratings"));
    fireEvent.click(await screen.findByRole("button", { name: "Delete" }));
    await waitFor(() => expect(screen.queryByTestId("alert-rule-row-rule-ratings")).toBeNull());
  });

  it("an editor gets neither 'Every agent' nor webhook targets", async () => {
    renderAsEditor("/manage/alerts?tab=rules&agentId=agent1");
    fireEvent.click(await screen.findByTestId("alerts-new-rule"));
    expect(screen.queryByTestId("alert-rule-all-agents")).toBeNull();
    fireEvent.click(screen.getByTestId("alert-rule-add-target"));
    const type = screen.getByTestId("alert-rule-target-type-0");
    expect(within(type).queryByText("Webhook")).toBeNull();
  });
});

describe("Sidebar alerts badge", () => {
  it("shows the unacknowledged count to an administrator", async () => {
    server.use(http.get("*/alerts/events/unacknowledged-count", () => HttpResponse.json({ count: 4 })));
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />);
    await waitFor(() => expect(screen.getByTestId("nav-alerts-badge").textContent).toBe("4"));
  });

  it("does not ask for an all-agent count on behalf of an editor", async () => {
    let asked = false;
    server.use(
      http.get("*/alerts/events/unacknowledged-count", () => {
        asked = true;
        return HttpResponse.json({ count: 3 });
      }),
    );
    renderAsEditor("/manage/alerts", <Sidebar collapsed={false} onToggle={() => {}} />);
    await screen.findAllByRole("link");
    expect(asked).toBe(false);
    expect(screen.queryByTestId("nav-alerts-badge")).toBeNull();
  });
});
