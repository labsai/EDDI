import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { toast } from "sonner";

import { QuotasPage } from "@/pages/quotas";
import { parseQuotaLimit } from "@/lib/quota-limit";

// Mock sonner toast so we can assert on toast calls
vi.mock("sonner", () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

function renderQuotas() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  return render(
    <MemoryRouter initialEntries={["/manage/quotas"]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <QuotasPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("QuotasPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ─── Rendering ────────────────────────────────────────────────

  it("renders the page header and description", async () => {
    renderQuotas();
    expect(screen.getByText("Tenant Quotas")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Configure rate limits, usage caps, and cost budgets per tenant.",
      ),
    ).toBeInTheDocument();
  });

  it("renders the quotas-page container", () => {
    renderQuotas();
    expect(screen.getByTestId("quotas-page")).toBeInTheDocument();
  });

  it("renders save button (disabled by default)", () => {
    renderQuotas();
    const saveBtn = screen.getByTestId("quotas-save");
    expect(saveBtn).toBeInTheDocument();
    expect(saveBtn).toBeDisabled();
  });

  it("shows loading skeleton while data is fetching", () => {
    // Delay mock responses to observe loading state
    server.use(
      http.get("*/administration/quotas/:tenantId", async () => {
        await new Promise((r) => setTimeout(r, 200));
        return HttpResponse.json({
          tenantId: "default",
          maxConversationsPerDay: 5000,
          maxAgentsPerTenant: 100,
          maxApiCallsPerMinute: 500,
          maxMonthlyCostUsd: 2500,
          enabled: true,
        });
      }),
    );
    renderQuotas();
    expect(screen.getByTestId("quotas-loading")).toBeInTheDocument();
  });

  // ─── Configuration Card ──────────────────────────────────────

  it("loads and displays all quota configuration fields", async () => {
    renderQuotas();
    await waitFor(() => {
      expect(screen.getByTestId("quota-max-conversations")).toBeInTheDocument();
    });
    expect(screen.getByTestId("quota-max-agents")).toBeInTheDocument();
    expect(screen.getByTestId("quota-max-api-calls")).toBeInTheDocument();
    expect(screen.getByTestId("quota-max-cost")).toBeInTheDocument();
  });

  it("shows the toggle enabled button", async () => {
    renderQuotas();
    await waitFor(() => {
      expect(screen.getByTestId("quotas-toggle-enabled")).toBeInTheDocument();
    });
    // Mock data has enabled: true
    expect(screen.getByText("Enabled")).toBeInTheDocument();
  });

  it("renders quota field values from server data", async () => {
    renderQuotas();
    await waitFor(() => {
      expect(screen.getByTestId("quota-max-conversations")).toHaveValue(5000);
    });
    expect(screen.getByTestId("quota-max-agents")).toHaveValue(100);
    expect(screen.getByTestId("quota-max-api-calls")).toHaveValue(500);
    expect(screen.getByTestId("quota-max-cost")).toHaveValue(2500);
  });

  // ─── Usage Card ──────────────────────────────────────────────

  it("loads and displays usage data", async () => {
    renderQuotas();
    await waitFor(() => {
      expect(screen.getByTestId("usage-conversations")).toBeInTheDocument();
    });
    expect(screen.getByTestId("usage-api-calls")).toBeInTheDocument();
    expect(screen.getByTestId("usage-cost")).toBeInTheDocument();
    expect(screen.getByTestId("usage-tenant-id")).toBeInTheDocument();
  });

  it("displays tenant ID in usage card", async () => {
    renderQuotas();
    await waitFor(() => {
      expect(screen.getByTestId("usage-tenant-id")).toHaveTextContent("default");
    });
  });

  it("shows reset usage button", async () => {
    renderQuotas();
    await waitFor(() => {
      expect(screen.getByTestId("quotas-reset-usage")).toBeInTheDocument();
    });
    expect(screen.getByText("Reset Counters")).toBeInTheDocument();
  });

  // ─── Interactions ─────────────────────────────────────────────

  it("marks form as dirty when a field is changed", async () => {
    const user = userEvent.setup();
    renderQuotas();

    await waitFor(() => {
      expect(screen.getByTestId("quota-max-conversations")).toBeInTheDocument();
    });

    const input = screen.getByTestId("quota-max-conversations");
    await user.clear(input);
    await user.type(input, "999");

    expect(await screen.findByTestId("quotas-dirty-indicator")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("quotas-save")).toBeEnabled());
    expect(input).toHaveValue(999);
  });

  it("toggles enforcement off and shows disabled banner", async () => {
    const user = userEvent.setup();
    renderQuotas();

    await waitFor(() => {
      expect(screen.getByTestId("quotas-toggle-enabled")).toBeInTheDocument();
    });

    // Mock has enabled: true, toggling should show "Disabled"
    await user.click(screen.getByTestId("quotas-toggle-enabled"));

    expect(await screen.findByText("Disabled")).toBeInTheDocument();
    // The disabled banner should now appear
    expect(await screen.findByTestId("quotas-disabled-banner")).toBeInTheDocument();
  });

  it("saves quota changes via PUT", async () => {
    const user = userEvent.setup();
    renderQuotas();

    await waitFor(() => {
      expect(screen.getByTestId("quota-max-conversations")).toBeInTheDocument();
    });

    // Change a field to make dirty
    const input = screen.getByTestId("quota-max-conversations");
    await user.clear(input);
    await user.type(input, "1000");

    // Click save
    await user.click(screen.getByTestId("quotas-save"));

    // After successful save, dirty indicator should be gone
    await waitFor(() => {
      expect(screen.queryByTestId("quotas-dirty-indicator")).not.toBeInTheDocument();
    });
  });

  it("treats a cleared limit as unlimited (-1), not as a limit of 0", async () => {
    const user = userEvent.setup();
    let body: Record<string, unknown> | null = null;
    server.use(
      http.put("*/administration/quotas/:tenantId", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(body);
      }),
    );
    renderQuotas();
    const input = await screen.findByTestId("quota-max-conversations");
    await user.clear(input);
    await waitFor(() => expect(input).toHaveValue(null)); // stays empty while editing
    await user.click(screen.getByTestId("quotas-save"));
    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.maxConversationsPerDay).toBe(-1);
  });

  it("rejects negative and fractional limits and blocks Save until fixed", async () => {
    const user = userEvent.setup();
    renderQuotas();
    const input = await screen.findByTestId("quota-max-conversations");
    // Every assertion follows a state update the page makes after the keystroke,
    // so each one waits for it instead of reading the DOM on the same tick.
    await user.clear(input);
    await user.type(input, "-5");
    expect(await screen.findByTestId("quota-max-conversations-invalid")).toBeInTheDocument();
    await waitFor(() => expect(input).toHaveAttribute("aria-invalid", "true"));
    expect(screen.getByTestId("quotas-save")).toBeDisabled();

    await user.clear(input);
    await user.type(input, "2.5");
    expect(await screen.findByTestId("quota-max-conversations-invalid")).toBeInTheDocument();

    await user.clear(input);
    await user.type(input, "7");
    await waitFor(() => expect(screen.queryByTestId("quota-max-conversations-invalid")).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId("quotas-save")).toBeEnabled());
  });

  it("ties each limit's label to its input", async () => {
    renderQuotas();
    expect(await screen.findByLabelText("Max Conversations / Day")).toBe(
      screen.getByTestId("quota-max-conversations"),
    );
    expect(screen.getByLabelText("Max Monthly Cost (USD)")).toBe(screen.getByTestId("quota-max-cost"));
  });

  it("warns on tab close while there are unsaved changes", async () => {
    const user = userEvent.setup();
    renderQuotas();
    const input = await screen.findByTestId("quota-max-conversations");
    await user.clear(input);
    await user.type(input, "12");
    const ev = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(true);
  });

  // Regression: POST /usage/reset is semi-destructive (zeroes ALL live usage
  // counters) with no confirmation at the API level, so the UI must gate it.
  it("does NOT call the reset endpoint immediately — it opens a confirm dialog first", async () => {
    const user = userEvent.setup();
    const resetFn = vi.fn();
    server.use(
      http.post("*/administration/quotas/:tenantId/usage/reset", () => {
        resetFn();
        return new HttpResponse(null, { status: 200 });
      }),
    );

    renderQuotas();

    await waitFor(() => {
      expect(screen.getByTestId("quotas-reset-usage")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("quotas-reset-usage"));

    // The confirmation dialog appears instead of firing the request.
    expect(
      await screen.findByText("Reset usage counters?"),
    ).toBeInTheDocument();
    expect(resetFn).not.toHaveBeenCalled();
  });

  it("resets usage counters only after confirming in the dialog", async () => {
    const user = userEvent.setup();
    const resetFn = vi.fn();
    server.use(
      http.post("*/administration/quotas/:tenantId/usage/reset", () => {
        resetFn();
        return new HttpResponse(null, { status: 200 });
      }),
    );

    renderQuotas();

    await waitFor(() => {
      expect(screen.getByTestId("quotas-reset-usage")).toBeInTheDocument();
    });

    // Open the confirmation dialog.
    await user.click(screen.getByTestId("quotas-reset-usage"));
    const dialog = await screen.findByRole("dialog");

    // Confirm — this is what actually triggers POST .../usage/reset.
    await user.click(
      within(dialog).getByRole("button", { name: "Reset Counters" }),
    );

    await waitFor(() => {
      expect(resetFn).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(toast.success).toHaveBeenCalled();
    });
  });

  it("surfaces an error toast when the reset request fails", async () => {
    const user = userEvent.setup();
    server.use(
      http.post("*/administration/quotas/:tenantId/usage/reset", () => {
        return new HttpResponse(null, { status: 500 });
      }),
    );

    renderQuotas();

    await waitFor(() => {
      expect(screen.getByTestId("quotas-reset-usage")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("quotas-reset-usage"));
    const dialog = await screen.findByRole("dialog");
    await user.click(
      within(dialog).getByRole("button", { name: "Reset Counters" }),
    );

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalled();
    });
  });

  // ─── 404 Fallback (no quota record) ──────────────────────────

  it("renders with default values when backend returns 404 for quota", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId/usage", ({ params }) => {
        // Let /quotas/count fall through to the global handler — :tenantId also matches "count"
        if (params.tenantId === "count") return;
        return HttpResponse.json({
          tenantId: "default",
          conversationsToday: 0,
          apiCallsThisMinute: 0,
          monthlyCostUsd: 0,
          minuteWindowStart: new Date().toISOString(),
          dayStart: new Date().toISOString(),
        });
      }),
      http.get("*/administration/quotas/:tenantId", () => {
        return new HttpResponse(null, { status: 404 });
      }),
    );

    renderQuotas();

    // Should still render fields with default values (-1 = unlimited)
    await waitFor(() => {
      expect(screen.getByTestId("quota-max-conversations")).toHaveValue(-1);
    });
    expect(screen.getByTestId("quota-max-agents")).toHaveValue(-1);
    expect(screen.getByTestId("quota-max-api-calls")).toHaveValue(-1);
    expect(screen.getByTestId("quota-max-cost")).toHaveValue(-1);

    // Should show disabled state
    expect(screen.getByText("Disabled")).toBeInTheDocument();
    // Should show the enforcement-off banner
    expect(screen.getByTestId("quotas-disabled-banner")).toBeInTheDocument();
  });

  it("renders with zeroed usage when backend returns 404 for usage", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId/usage", () => {
        return new HttpResponse(null, { status: 404 });
      }),
    );

    renderQuotas();

    await waitFor(() => {
      expect(screen.getByTestId("usage-tenant-id")).toHaveTextContent("default");
    });
  });

  // ─── Enforcement disabled banner ─────────────────────────────

  it("shows enforcement disabled banner when quota is disabled", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId", ({ params }) => {
        // Let /quotas/count fall through to the global handler — :tenantId also matches "count"
        if (params.tenantId === "count") return;
        return HttpResponse.json({
          tenantId: "default",
          maxConversationsPerDay: -1,
          maxAgentsPerTenant: -1,
          maxApiCallsPerMinute: -1,
          maxMonthlyCostUsd: -1,
          enabled: false,
        });
      }),
    );

    renderQuotas();

    await waitFor(() => {
      expect(screen.getByTestId("quotas-disabled-banner")).toBeInTheDocument();
    });
    expect(screen.getByText("Enforcement is off.")).toBeInTheDocument();
  });

  it("does not show enforcement banner when quota is enabled", async () => {
    renderQuotas();

    await waitFor(() => {
      expect(screen.getByTestId("quotas-toggle-enabled")).toBeInTheDocument();
    });
    // Mock data has enabled: true
    expect(screen.queryByTestId("quotas-disabled-banner")).not.toBeInTheDocument();
  });

  // ─── Error handling ──────────────────────────────────────────

  it("does not crash on server error (non-404)", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId", () => {
        return new HttpResponse(null, { status: 500 });
      }),
      http.get("*/administration/quotas/:tenantId/usage", () => {
        return new HttpResponse(null, { status: 500 });
      }),
    );

    renderQuotas();

    // Should still render the page structure
    expect(screen.getByTestId("quotas-page")).toBeInTheDocument();
  });

  // ─── Empty and half-typed limits ──────────────────────────────

  describe("empty and half-typed limits", () => {
    it("parseQuotaLimit reads empty as unlimited and a lone '-' as not-a-number", () => {
      expect(parseQuotaLimit("")).toBe(-1);
      expect(parseQuotaLimit("  ")).toBe(-1);
      expect(parseQuotaLimit("0")).toBe(0);
      expect(parseQuotaLimit("250")).toBe(250);
      expect(parseQuotaLimit("-")).toBeNull();
    });

    it("saves a cleared field as -1 (unlimited), never 0 (blocked)", async () => {
      // Regression: Number("") is 0, so clearing a limit and saving blocked the
      // whole tenant.
      let body: Record<string, unknown> | null = null;
      server.use(
        http.put("*/administration/quotas/:tenantId", async ({ request }) => {
          body = (await request.json()) as Record<string, unknown>;
          return HttpResponse.json(body);
        }),
      );
      const user = userEvent.setup();
      renderQuotas();
      await waitFor(() => expect(screen.getByTestId("quota-max-conversations")).toHaveValue(5000));

      await user.clear(screen.getByTestId("quota-max-conversations"));
      await user.click(screen.getByTestId("quotas-save"));

      await waitFor(() => expect(body).not.toBeNull());
      expect(body!.maxConversationsPerDay).toBe(-1);
    });

    it("blocks saving while a field holds a half-typed number", async () => {
      renderQuotas();
      await waitFor(() => expect(screen.getByTestId("quota-max-agents")).toHaveValue(100));
      const input = screen.getByTestId("quota-max-agents") as HTMLInputElement;

      // What a browser reports for a lone "-" in a number input: empty value,
      // `validity.badInput` set. jsdom does not model badInput, so it is stubbed.
      Object.defineProperty(input, "validity", {
        configurable: true,
        get: () => ({ badInput: true }),
      });
      fireEvent.change(input, { target: { value: "" } });

      expect(screen.getByTestId("quota-max-agents-invalid")).toBeInTheDocument();
      expect(screen.getByTestId("quotas-save")).toBeDisabled();
    });

    it("associates each limit label with its input", async () => {
      renderQuotas();
      await waitFor(() => expect(screen.getByTestId("quota-max-api-calls")).toBeInTheDocument());
      expect(screen.getByLabelText("Max API Calls / Minute")).toBe(screen.getByTestId("quota-max-api-calls"));
    });
  });
  // ─── Cost budget legibility ───────────────────────────────────

  it("states the cost budget is enforced and what it counts", async () => {
    renderQuotas();
    await waitFor(() => expect(screen.getByTestId("quota-max-cost-note")).toBeInTheDocument());
    expect(screen.getByTestId("quota-max-cost-note")).toHaveTextContent("priced spend only");
    expect(screen.getByTestId("cost-budget-state")).toHaveTextContent(/used this month/);
    expect(screen.queryByText(/not enforced/i)).not.toBeInTheDocument();
  });

  it("says plainly when the budget has been reached", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId/usage", () =>
        HttpResponse.json({
          tenantId: "default",
          conversationsToday: 1,
          apiCallsThisMinute: 0,
          monthlyCostUsd: 2600,
          minuteWindowStart: new Date().toISOString(),
          dayStart: new Date().toISOString(),
        }),
      ),
    );
    renderQuotas();
    await waitFor(() => expect(screen.getByTestId("cost-budget-state")).toHaveTextContent(/Budget reached/));
    expect(screen.getByTestId("cost-budget-state")).toHaveTextContent(/fallback answer/);
  });

  it("does not claim a budget is applied while enforcement is off", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId", () =>
        HttpResponse.json({
          tenantId: "default",
          maxConversationsPerDay: -1,
          maxAgentsPerTenant: -1,
          maxApiCallsPerMinute: -1,
          maxMonthlyCostUsd: 100,
          enabled: false,
        }),
      ),
    );
    renderQuotas();
    await waitFor(() => expect(screen.getByTestId("cost-budget-state")).toHaveTextContent(/enforcement is off/));
  });

  it("reports an unset budget as unlimited", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId", () =>
        HttpResponse.json({
          tenantId: "default",
          maxConversationsPerDay: -1,
          maxAgentsPerTenant: -1,
          maxApiCallsPerMinute: -1,
          maxMonthlyCostUsd: -1,
          enabled: true,
        }),
      ),
    );
    renderQuotas();
    await waitFor(() => expect(screen.getByTestId("cost-budget-state")).toHaveTextContent(/never limited/));
  });

  it("does not claim spend is metered for a disabled tenant with no budget", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId", () =>
        HttpResponse.json({
          tenantId: "default",
          maxConversationsPerDay: -1,
          maxAgentsPerTenant: -1,
          maxApiCallsPerMinute: -1,
          maxMonthlyCostUsd: -1,
          enabled: false,
        }),
      ),
    );
    renderQuotas();
    await waitFor(() => expect(screen.getByTestId("cost-budget-state")).toHaveTextContent(/neither metered nor limited/));
    expect(screen.getByTestId("cost-budget-state")).not.toHaveTextContent(/metered but never limited/);
  });

  it("shows sub-cent spend against a small budget instead of rounding it away", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId", () =>
        HttpResponse.json({
          tenantId: "default",
          maxConversationsPerDay: -1,
          maxAgentsPerTenant: -1,
          maxApiCallsPerMinute: -1,
          maxMonthlyCostUsd: 0.01,
          enabled: true,
        }),
      ),
      http.get("*/administration/quotas/:tenantId/usage", () =>
        HttpResponse.json({
          tenantId: "default",
          conversationsToday: 1,
          apiCallsThisMinute: 0,
          monthlyCostUsd: 0.006,
          minuteWindowStart: new Date().toISOString(),
          dayStart: new Date().toISOString(),
        }),
      ),
    );
    renderQuotas();
    await waitFor(() => expect(screen.getByTestId("cost-budget-state")).toHaveTextContent("$0.006 of $0.01 used this month"));
    expect(screen.getByTestId("cost-budget-state")).toHaveTextContent("$0.004 left");
    // The usage card agrees with the status line rather than rounding to $0.01.
    expect(screen.getByTestId("usage-cost")).toHaveTextContent("$0.006");
    expect(screen.getByTestId("usage-cost")).toHaveTextContent("/ $0.01");
  });

  it("never shows a tiny positive budget as $0.00, and still shows zero spend as $0.00", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId", () =>
        HttpResponse.json({
          tenantId: "default",
          maxConversationsPerDay: -1,
          maxAgentsPerTenant: -1,
          maxApiCallsPerMinute: -1,
          maxMonthlyCostUsd: 0.00004,
          enabled: true,
        }),
      ),
      http.get("*/administration/quotas/:tenantId/usage", () =>
        HttpResponse.json({
          tenantId: "default",
          conversationsToday: 0,
          apiCallsThisMinute: 0,
          monthlyCostUsd: 0,
          minuteWindowStart: new Date().toISOString(),
          dayStart: new Date().toISOString(),
        }),
      ),
    );
    renderQuotas();
    await waitFor(() => expect(screen.getByTestId("cost-budget-state")).toHaveTextContent("$0.00 of $0.00004 used this month"));
    expect(screen.getByTestId("usage-cost")).toHaveTextContent("$0.00/ $0.00004");
  });

  it("keeps the decimals that tell spend and budget apart above a dollar", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId", () =>
        HttpResponse.json({
          tenantId: "default",
          maxConversationsPerDay: -1,
          maxAgentsPerTenant: -1,
          maxApiCallsPerMinute: -1,
          maxMonthlyCostUsd: 1.004,
          enabled: true,
        }),
      ),
      http.get("*/administration/quotas/:tenantId/usage", () =>
        HttpResponse.json({
          tenantId: "default",
          conversationsToday: 0,
          apiCallsThisMinute: 0,
          monthlyCostUsd: 1.003,
          minuteWindowStart: new Date().toISOString(),
          dayStart: new Date().toISOString(),
        }),
      ),
    );
    renderQuotas();
    await waitFor(() => expect(screen.getByTestId("cost-budget-state")).toHaveTextContent("$1.003 of $1.004 used this month"));
    expect(screen.getByTestId("cost-budget-state")).toHaveTextContent("$0.001 left");
    expect(screen.getByTestId("usage-cost")).toHaveTextContent("$1.003/ $1.004");
  });

  it("describes the saved quota, not an unsaved edit of the form", async () => {
    renderQuotas();
    await waitFor(() => expect(screen.getByTestId("cost-budget-state")).toHaveTextContent("$2500.00"));
    fireEvent.change(screen.getByTestId("quota-max-cost"), { target: { value: "100" } });
    // Saved limit is still 2500; the edit only applies once it is saved.
    expect(screen.getByTestId("cost-budget-state")).toHaveTextContent("$2500.00");
    expect(screen.getByTestId("cost-budget-state")).not.toHaveTextContent("$100.00");
  });
});
