import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

import { VariablesPage } from "@/pages/variables";
import { SchedulesPage } from "@/pages/schedules";
import { QuotasPage } from "@/pages/quotas";
import { ChannelDetailPage } from "@/pages/channel-detail";

/**
 * These pages had no `isError` branch at all, so a 500 rendered the *empty*
 * state — "No schedules yet", "No global variables defined" — telling the
 * operator their platform is empty when in fact the request failed. On an admin
 * console that is worse than an error: it is confidently wrong.
 *
 * Each test forces a 500 and asserts the page shows an error with a retry, and
 * critically that the misleading empty state is NOT shown.
 */

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

/**
 * `common.error` renders as "Something went wrong". Matching that exact string
 * rather than /error|failed/ matters: the Schedules page has a "Failed" tab and
 * a broad pattern makes `getByText` throw on multiple matches.
 */
async function expectErrorNotEmpty(emptyTestId?: string) {
  await waitFor(
    () => {
      expect(screen.getByText(/something went wrong/i)).toBeInTheDocument();
    },
    { timeout: 5000 },
  );
  // getAllBy: other rows may legitimately offer their own retry affordance.
  expect(screen.getAllByRole("button", { name: /^retry$/i }).length).toBeGreaterThan(0);
  if (emptyTestId) {
    expect(screen.queryByTestId(emptyTestId)).not.toBeInTheDocument();
  }
}

beforeEach(() => {
  server.resetHandlers();
});

describe("a failed refetch keeps data already on screen", () => {
  /**
   * These pages poll (schedules and quotas every 10s) and refetch on window
   * focus. Gating the full-panel ErrorState on `isError` alone meant one blip
   * replaced rows the operator was reading, and on a flaky link the panel
   * flickered in and out. The error must degrade to an inline stale-data notice
   * once there is something to show.
   */
  it("Schedules keeps its rows and shows a stale notice, not the error panel", async () => {
    let call = 0;
    server.use(
      http.get("*/schedulestore/schedules", () => {
        call += 1;
        if (call === 1) {
          return HttpResponse.json([
            {
              id: "s1",
              name: "Nightly sync",
              enabled: true,
              triggerType: "CRON",
              cron: "0 0 * * *",
              fireStatus: "PENDING",
              timeZone: "UTC",
            },
          ]);
        }
        return HttpResponse.json({ message: "boom" }, { status: 503 });
      }),
    );

    const { queryClient } = renderPage(
      "/manage/schedules",
      <SchedulesPage />,
      "/manage/schedules",
    );

    await waitFor(() => expect(screen.getByText("Nightly sync")).toBeInTheDocument());

    // Force the second (failing) fetch, standing in for the 10s poll.
    await queryClient.refetchQueries();

    await waitFor(() =>
      expect(screen.getByTestId("refetch-error-notice")).toBeInTheDocument(),
    );
    // The row must still be there, and the destructive panel must not be.
    expect(screen.getByText("Nightly sync")).toBeInTheDocument();
    expect(screen.queryByText(/something went wrong/i)).not.toBeInTheDocument();
  });
});

describe("page error states", () => {
  it("Variables shows an error, not 'no variables defined'", async () => {
    server.use(
      http.get("*/variablestore/variables/*", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
    );
    renderPage("/manage/variables", <VariablesPage />, "/manage/variables");
    await expectErrorNotEmpty("variables-empty");
  });

  it("Schedules shows an error, not 'No schedules yet'", async () => {
    server.use(
      http.get("*/schedulestore/schedules", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
    );
    renderPage("/manage/schedules", <SchedulesPage />, "/manage/schedules");
    await expectErrorNotEmpty("schedules-empty");
  });

  it("Quotas shows an error instead of empty config and usage cards", async () => {
    server.use(
      http.get("*/administration/quotas/*", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
    );
    renderPage("/manage/quotas", <QuotasPage />, "/manage/quotas");
    await expectErrorNotEmpty();
  });

  it("Quotas shows a usage error without hiding the still-working config form", async () => {
    // Usage is a separate query. When only it fails the card used to render its
    // header and Reset button over an empty body, reading as "no usage".
    server.use(
      http.get("*/administration/quotas/:tenant/usage", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
    );
    renderPage("/manage/quotas", <QuotasPage />, "/manage/quotas");

    await waitFor(
      () => expect(screen.getByTestId("quotas-usage-error")).toBeInTheDocument(),
      { timeout: 5000 },
    );
    // The quota config half loaded fine and must remain usable.
    expect(screen.getByTestId("quotas-toggle-enabled")).toBeInTheDocument();
  });

  it("Channel detail keeps an open edit form when a background refetch fails", async () => {
    // The first version of this fix returned early on isError, which also fires
    // for a background refetch — discarding a form the user was editing. The
    // error must be surfaced without unmounting the form.
    const { queryClient } = renderPage(
      "/manage/channels/channel1",
      <ChannelDetailPage />,
      "/manage/channels/:id",
    );
    await waitFor(() => expect(screen.getByTestId("save-channel-btn")).toBeInTheDocument(), {
      timeout: 5000,
    });

    server.use(
      http.get("*/channelstore/channels/*", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
    );

    // Actually run the failing refetch. Registering the handler alone left
    // isError false, so the assertions below passed no matter what the component
    // did — the test guarded nothing until this line existed.
    await queryClient.refetchQueries();
    await waitFor(() => {
      const q = queryClient.getQueryCache().getAll();
      expect(q.some((entry) => entry.state.status === "error")).toBe(true);
    });

    // The form must still be mounted; it is never replaced by the error page.
    expect(screen.getByTestId("save-channel-btn")).toBeInTheDocument();
    expect(screen.queryByTestId("channel-detail-error")).not.toBeInTheDocument();
  });

  it("Channel detail shows an error instead of an endless skeleton", async () => {
    server.use(
      http.get("*/channelstore/channels/*", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
    );
    renderPage(
      "/manage/channels/does-not-exist",
      <ChannelDetailPage />,
      "/manage/channels/:id",
    );
    await waitFor(
      () => expect(screen.getByTestId("channel-detail-error")).toBeInTheDocument(),
      { timeout: 5000 },
    );
    expect(screen.queryByTestId("channel-detail-loading")).not.toBeInTheDocument();
  });
});
