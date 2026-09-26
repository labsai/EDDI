import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SchedulesPage } from "@/pages/schedules";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderSchedules() {
  return renderWithProviders(<SchedulesPage />, {
    initialRoute: "/manage/schedules",
  });
}

function scheduleRow(overrides: Record<string, unknown>) {
  return {
    triggerType: "CRON",
    agentId: "agent1",
    agentVersion: 0,
    environment: "production",
    cronExpression: "0 3 * * *",
    message: "tick",
    conversationStrategy: "new",
    enabled: true,
    fireStatus: "PENDING",
    failCount: 0,
    timeZone: "UTC",
    ...overrides,
  };
}

// ── Editing must not rewire a schedule's fire path ─────────────────────────
//
// The edit form can only express a chat schedule and a PUT is a full replace,
// so saving a RAG-ingestion, dream or team-cadence schedule dropped its
// metadata marker and turned it into one that messages an agent.

describe("SchedulesPage — system-managed schedules", () => {
  it.each([
    ["rag-1", { ragIngestion: true, ragConfigId: "kb1", sourceId: "s1" }],
    ["dream-1", { dreamType: "dream_consolidation" }],
    ["cadence-1", { teamCadenceType: "standup", groupId: "g1" }],
  ])(
    "offers no edit action for %s and marks it as system-managed",
    async (id, metadata) => {
      server.use(
        http.get("*/schedulestore/schedules", () =>
          HttpResponse.json([
            scheduleRow({ id, name: `System ${id}`, metadata }),
            scheduleRow({ id: "chat-1", name: "Chat schedule" }),
          ])
        )
      );

      renderSchedules();
      await waitFor(() =>
        expect(
          screen.getByTestId(`system-schedule-badge-${id}`)
        ).toBeInTheDocument()
      );
      expect(screen.queryByTestId(`edit-${id}`)).not.toBeInTheDocument();
      // An ordinary chat schedule keeps its edit action.
      expect(screen.getByTestId("edit-chat-1")).toBeInTheDocument();
      expect(
        screen.queryByTestId("system-schedule-badge-chat-1")
      ).not.toBeInTheDocument();
    }
  );
});

describe("SchedulesPage — edit keeps what the form does not own", () => {
  it("echoes tenantId, allowSelfScheduling and metadata, and takes enabled from the freshest row", async () => {
    let enabledNow = true;
    let putBody: Record<string, unknown> | null = null;
    server.use(
      http.get("*/schedulestore/schedules", () =>
        HttpResponse.json([
          scheduleRow({
            id: "chat-1",
            name: "Chat schedule",
            enabled: enabledNow,
            tenantId: "tenant-a",
            allowSelfScheduling: true,
            metadata: { origin: "import" },
            conversationStrategy: "persistent",
            persistentConversationId: "conv-kept",
          }),
        ])
      ),
      http.put("*/schedulestore/schedules/:id", async ({ request }) => {
        putBody = (await request.json()) as Record<string, unknown>;
        return new HttpResponse(null, { status: 200 });
      })
    );

    const user = userEvent.setup();
    const { queryClient } = renderSchedules();
    await user.click(await screen.findByTestId("edit-chat-1"));
    const dialog = await screen.findByRole("dialog");

    // The persistent conversation id is fixed at creation — the backend
    // carries it over on every update, so the field must not pretend otherwise.
    const convInput = within(dialog).getByTestId("persistent-conv-input");
    expect(convInput).toHaveValue("conv-kept");
    expect(convInput).toHaveAttribute("readonly");
    expect(
      within(dialog).getByTestId("persistent-conv-readonly-hint")
    ).toBeInTheDocument();

    // Someone disables the schedule while the dialog is open.
    enabledNow = false;
    await queryClient.refetchQueries({ queryKey: ["schedules"] });

    await user.click(within(dialog).getByTestId("schedule-submit-btn"));
    await waitFor(() => expect(putBody).not.toBeNull());

    expect(putBody!.enabled).toBe(false);
    expect(putBody!.tenantId).toBe("tenant-a");
    expect(putBody!.allowSelfScheduling).toBe(true);
    expect(putBody!.metadata).toEqual({ origin: "import" });
    expect(putBody!.persistentConversationId).toBeUndefined();
  });
});

// ── The dead-letter panel lists fire logs but acts on schedules ────────────

describe("SchedulesPage — dead-letter panel acts on schedules, not logs", () => {
  it("offers retry/dismiss only on the newest log of a schedule that is dead-lettered now", async () => {
    server.use(
      http.get("*/schedulestore/schedules", () =>
        HttpResponse.json([
          scheduleRow({
            id: "dl-1",
            name: "Dead one",
            enabled: false,
            fireStatus: "DEAD_LETTERED",
            failCount: 3,
          }),
          scheduleRow({
            id: "backoff-1",
            name: "Backing off",
            fireStatus: "FAILED",
            failCount: 1,
          }),
        ])
      ),
      http.get("*/schedulestore/schedules/admin/failed", () =>
        HttpResponse.json([
          {
            id: "log-new",
            scheduleId: "dl-1",
            fireTime: new Date().toISOString(),
            status: "DEAD_LETTERED",
            attemptNumber: 3,
          },
          {
            id: "log-old",
            scheduleId: "dl-1",
            fireTime: new Date(Date.now() - 60_000).toISOString(),
            status: "FAILED",
            attemptNumber: 2,
          },
          {
            id: "log-backoff",
            scheduleId: "backoff-1",
            fireTime: new Date().toISOString(),
            status: "FAILED",
            attemptNumber: 1,
          },
          {
            id: "log-gone",
            scheduleId: "deleted-1",
            fireTime: new Date().toISOString(),
            status: "DEAD_LETTERED",
            attemptNumber: 3,
          },
        ])
      )
    );

    const user = userEvent.setup();
    renderSchedules();
    await user.click(await screen.findByTestId("tab-failed"));
    const table = await screen.findByTestId("failed-fires-table");

    // Exactly one retry/dismiss pair — for the dead-lettered schedule.
    await waitFor(() =>
      expect(within(table).getAllByTestId("failed-retry-dl-1")).toHaveLength(1)
    );
    expect(within(table).getAllByTestId("failed-dismiss-dl-1")).toHaveLength(1);
    // A FAILED schedule waiting out its backoff: retry would 404 and dismiss
    // could clear a live claim, so neither is offered.
    expect(
      within(table).queryByTestId("failed-retry-backoff-1")
    ).not.toBeInTheDocument();
    expect(
      within(table).queryByTestId("failed-dismiss-backoff-1")
    ).not.toBeInTheDocument();
    expect(
      within(table).getByTestId("failed-no-action-log-backoff")
    ).toHaveTextContent("Failed");
    expect(
      within(table).getByTestId("failed-no-action-log-old")
    ).toBeInTheDocument();
    expect(
      within(table).getByTestId("failed-no-action-log-gone")
    ).toHaveTextContent("Schedule no longer exists");
  });
});
