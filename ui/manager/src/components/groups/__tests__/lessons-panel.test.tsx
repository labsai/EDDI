import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";

const toastSuccess = vi.hoisted(() => vi.fn());
const toastWarning = vi.hoisted(() => vi.fn());
vi.mock("sonner", () => ({ toast: { success: toastSuccess, warning: toastWarning, error: vi.fn() } }));

import { LessonsPanel } from "../lessons-panel";

const configured = () =>
  server.use(
    http.get("*/groupstore/groups/:groupId/lessons/consolidation", () =>
      HttpResponse.json({ configured: true, hasModel: true, scheduled: true, cronExpression: "0 3 * * 1", timeZone: "Europe/Vienna" }),
    ),
  );

describe("LessonsPanel", () => {
  beforeEach(() => {
    toastSuccess.mockReset();
    toastWarning.mockReset();
  });

  it("shows an error state, not the empty or unconfigured text, when reads fail", async () => {
    server.use(
      http.get("*/groupstore/groups/:groupId/lessons", () => HttpResponse.json({ message: "boom" }, { status: 500 })),
      http.get("*/groupstore/groups/:groupId/lessons/shared", () => HttpResponse.json({ message: "boom" }, { status: 500 })),
      http.get("*/groupstore/groups/:groupId/lessons/consolidation", () => HttpResponse.json({ message: "boom" }, { status: 500 })),
      http.get("*/groupstore/groups/:groupId/lessons/history", () => HttpResponse.json({ message: "boom" }, { status: 500 })),
    );
    renderWithProviders(<LessonsPanel groupId="g1" readOnly={false} />);

    expect(await screen.findByTestId("lessons-own-error")).toBeInTheDocument();
    expect(await screen.findByTestId("lessons-shared-error")).toBeInTheDocument();
    expect(await screen.findByTestId("consolidation-error-state")).toBeInTheDocument();
    expect(screen.queryByTestId("lessons-own-empty")).not.toBeInTheDocument();
    expect(screen.queryByTestId("lessons-shared-empty")).not.toBeInTheDocument();
    expect(screen.queryByTestId("consolidation-off")).not.toBeInTheDocument();
    expect(screen.queryByTestId("consolidation-run")).not.toBeInTheDocument();

    await userEvent.click(screen.getByTestId("lessons-history-toggle"));
    expect(await screen.findByTestId("lessons-history-error")).toBeInTheDocument();
    expect(screen.queryByTestId("lessons-history-empty")).not.toBeInTheDocument();
  });

  it("does not report a skipped run as finished", async () => {
    configured();
    server.use(
      http.post("*/groupstore/groups/:groupId/lessons/consolidation/run", () =>
        HttpResponse.json({
          groupId: "g1", runId: "r1", status: "SKIPPED", message: "The team holds 2 lesson(s); consolidation starts at 6",
          dryRun: false, lessonsConsidered: 0, outcomesConsidered: 0, estimatedCost: 0, actions: [],
        }),
      ),
    );
    renderWithProviders(<LessonsPanel groupId="g1" readOnly={false} />);

    await userEvent.click(await screen.findByTestId("consolidation-run"));

    await waitFor(() => expect(toastWarning).toHaveBeenCalled());
    expect(String(toastWarning.mock.calls[0]?.[0])).toContain("consolidation starts at 6");
    expect(toastSuccess).not.toHaveBeenCalled();
  });

  it("sends the chosen time zone with the schedule and shows the stored one", async () => {
    configured();
    let body: { cronExpression?: string; timeZone?: string } | undefined;
    server.use(
      http.put("*/groupstore/groups/:groupId/lessons/consolidation/schedule", async ({ request }) => {
        body = (await request.json()) as typeof body;
        return HttpResponse.json({ scheduleId: "s1", nextFire: "2026-11-09T02:00:00Z" }, { status: 201 });
      }),
    );
    renderWithProviders(<LessonsPanel groupId="g1" readOnly={false} />);

    expect(await screen.findByTestId("consolidation-schedule-state")).toHaveTextContent("Europe/Vienna");
    await userEvent.selectOptions(screen.getByTestId("consolidation-timezone"), "UTC");
    await userEvent.click(screen.getByTestId("consolidation-schedule"));

    await waitFor(() => expect(body).toBeDefined());
    expect(body).toEqual({ cronExpression: "0 3 * * 1", timeZone: "UTC" });
  });
});
