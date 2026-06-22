import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";

import { QuotasPage } from "@/pages/quotas";

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

    expect(screen.getByTestId("quotas-dirty-indicator")).toBeInTheDocument();
    expect(screen.getByTestId("quotas-save")).toBeEnabled();
  });

  it("toggles enforcement off and shows disabled banner", async () => {
    const user = userEvent.setup();
    renderQuotas();

    await waitFor(() => {
      expect(screen.getByTestId("quotas-toggle-enabled")).toBeInTheDocument();
    });

    // Mock has enabled: true, toggling should show "Disabled"
    await user.click(screen.getByTestId("quotas-toggle-enabled"));

    expect(screen.getByText("Disabled")).toBeInTheDocument();
    // The disabled banner should now appear
    expect(screen.getByTestId("quotas-disabled-banner")).toBeInTheDocument();
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

  it("calls reset usage endpoint on reset button click", async () => {
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

    await waitFor(() => {
      expect(resetFn).toHaveBeenCalledTimes(1);
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
});
