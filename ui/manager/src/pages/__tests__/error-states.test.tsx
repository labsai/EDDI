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

  it("Quotas shows an error instead of a form full of fabricated limits", async () => {
    server.use(
      http.get("*/administration/quotas/*", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
    );
    renderPage("/manage/quotas", <QuotasPage />, "/manage/quotas");
    await expectErrorNotEmpty();
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
