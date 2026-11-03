import { describe, it, expect } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { GroupWorkspacePage } from "@/pages/group-workspace";

/** Team lessons on the workspace page: own, shared, consolidation (schedule, preview, run) and history. */

const ROUTE = "/manage/groups/g1/workspace";

function renderWorkspacePage() {
  return renderPage(ROUTE, <GroupWorkspacePage />, "/manage/groups/:id/workspace");
}

const LESSON_CONFIG = {
  shareWith: ["board"],
  readFrom: ["marketing", "sales"],
  maxSharedLessons: 10,
  consolidation: { llmProvider: "anthropic", llmModel: "claude-x", maxCostPerRun: 0.1, minLessons: 6, maxLessonsPerRun: 40, outcomeLookbackDays: 30, maxOutcomesPerRun: 20 },
};

function lesson(n: number) {
  return { key: `retro:k${n}`, text: `Lesson number ${n}`, createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-02T00:00:00Z" };
}

function useGroup(lessonConfig: unknown) {
  server.use(
    http.get("*/groupstore/groups/g1", () => HttpResponse.json({ name: "Growth Board", members: [], phases: [], lessonConfig })),
    http.get("*/groupstore/groups/:groupId/workspace", () =>
      HttpResponse.json({
        id: "ws-g1", schemaVersion: 1, groupId: "g1", backlog: { tasks: [], awardedBids: {} },
        metrics: { discussions: 0, tasksVerified: 0, totalCost: 0, lastRunAt: null, perMemberStats: {} },
        cadences: [], runningDiscussionId: "", pulledTaskIds: [],
        created: "2026-06-01T00:00:00Z", lastModified: "2026-06-01T00:00:00Z", revision: "0",
      }),
    ),
  );
}

function consolidationState(overrides: Record<string, unknown> = {}) {
  server.use(
    http.get("*/groupstore/groups/:groupId/lessons/consolidation", () =>
      HttpResponse.json({ configured: true, hasModel: true, scheduled: false, ...overrides }),
    ),
  );
}

describe("team lessons on the workspace page", () => {
  it("shows nothing for a group without a lessonConfig", async () => {
    useGroup(null);
    renderWorkspacePage();

    await screen.findByTestId("workspace-backlog");
    expect(screen.queryByTestId("workspace-lessons")).not.toBeInTheDocument();
  });

  it("lists the team's own lessons, and says when there are none", async () => {
    useGroup(LESSON_CONFIG);
    consolidationState();
    server.use(http.get("*/groupstore/groups/:groupId/lessons", () => HttpResponse.json([lesson(1), lesson(2)])));
    const { unmount } = renderWorkspacePage();

    expect(await screen.findByTestId("lesson-retro:k1")).toHaveTextContent("Lesson number 1");
    expect(screen.getByTestId("workspace-lessons")).toHaveTextContent("(2)");
    unmount();

    server.use(http.get("*/groupstore/groups/:groupId/lessons", () => HttpResponse.json([])));
    renderWorkspacePage();
    expect(await screen.findByTestId("lessons-own-empty")).toBeInTheDocument();
  });

  it("shows what other teams share, and names the owners that did not grant", async () => {
    useGroup(LESSON_CONFIG);
    consolidationState();
    server.use(
      http.get("*/groupstore/groups/:groupId/lessons/shared", () =>
        HttpResponse.json({ readerGroupId: "g1", groups: [{ groupId: "marketing", name: "Marketing", lessons: [lesson(7)] }], notShared: ["sales"] }),
      ),
    );
    renderWorkspacePage();

    const group = await screen.findByTestId("shared-group-marketing");
    expect(group).toHaveTextContent("Marketing");
    expect(group).toHaveTextContent("Lesson number 7");
    expect(screen.getByTestId("lessons-not-shared")).toHaveTextContent("sales");
  });

  it("explains an unconfigured consolidation instead of offering buttons that cannot work", async () => {
    useGroup({ ...LESSON_CONFIG, consolidation: null });
    renderWorkspacePage();

    expect(await screen.findByTestId("consolidation-off")).toBeInTheDocument();
    expect(screen.queryByTestId("consolidation-run")).not.toBeInTheDocument();
  });

  it("warns when the job has no model", async () => {
    useGroup(LESSON_CONFIG);
    consolidationState({ hasModel: false });
    renderWorkspacePage();

    expect(await screen.findByTestId("consolidation-no-model")).toBeInTheDocument();
  });

  it("schedules the job with the typed cron, and removes the schedule", async () => {
    const calls: unknown[] = [];
    useGroup(LESSON_CONFIG);
    consolidationState();
    server.use(
      http.put("*/groupstore/groups/:groupId/lessons/consolidation/schedule", async ({ request }) => {
        calls.push(await request.json());
        return HttpResponse.json({ scheduleId: "s1", nextFire: "2026-11-10T03:00:00Z" }, { status: 201 });
      }),
    );
    renderWorkspacePage();

    fireEvent.change(await screen.findByTestId("consolidation-cron"), { target: { value: "0 5 * * 2" } });
    await userEvent.click(screen.getByTestId("consolidation-schedule"));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]).toMatchObject({ cronExpression: "0 5 * * 2" });
  });

  it("offers Remove schedule only while scheduled", async () => {
    useGroup(LESSON_CONFIG);
    consolidationState({ scheduled: true, cronExpression: "0 3 * * 1" });
    renderWorkspacePage();

    expect(await screen.findByTestId("consolidation-schedule-state")).toHaveTextContent("0 3 * * 1");
    expect(screen.getByTestId("consolidation-unschedule")).toBeInTheDocument();
  });

  it("previews a run with dryRun=true and shows what it would merge and retire, with why one was refused", async () => {
    const urls: string[] = [];
    useGroup(LESSON_CONFIG);
    consolidationState();
    server.use(
      http.post("*/groupstore/groups/:groupId/lessons/consolidation/run", ({ request }) => {
        urls.push(request.url);
        return HttpResponse.json({
          groupId: "g1", runId: "r1", status: "COMPLETED", message: "Proposed 2 change(s); nothing was changed", dryRun: true,
          lessonsConsidered: 8, outcomesConsidered: 3, estimatedCost: 0.0123,
          actions: [
            { type: "MERGE", keys: ["retro:k1", "retro:k2"], text: "Merged text", reason: null, evidence: [], applied: false, note: null },
            { type: "RETIRE", keys: ["retro:k3"], text: null, reason: "contradicted", evidence: [], applied: false, note: "a retirement must cite the outcome that contradicted the lesson" },
          ],
        });
      }),
    );
    renderWorkspacePage();

    await userEvent.click(await screen.findByTestId("consolidation-preview"));

    const result = await screen.findByTestId("consolidation-result");
    expect(urls[0]).toContain("dryRun=true");
    expect(result).toHaveTextContent("Proposed 2 change(s)");
    expect(screen.getByTestId("consolidation-action-0")).toHaveTextContent("Merged text");
    expect(screen.getByTestId("consolidation-action-1")).toHaveTextContent("must cite the outcome");
  });

  it("runs for real with dryRun=false, and shows a failure from the server", async () => {
    const urls: string[] = [];
    useGroup(LESSON_CONFIG);
    consolidationState();
    server.use(
      http.post("*/groupstore/groups/:groupId/lessons/consolidation/run", ({ request }) => {
        urls.push(request.url);
        return HttpResponse.json({ error: "The model call failed" }, { status: 502 });
      }),
    );
    renderWorkspacePage();

    await userEvent.click(await screen.findByTestId("consolidation-run"));

    expect(await screen.findByTestId("consolidation-error")).toBeInTheDocument();
    expect(urls[0]).toContain("dryRun=false");
  });

  it("hides the schedule and run controls from a caller who may only view", async () => {
    useGroup(LESSON_CONFIG);
    consolidationState();
    server.use(
      http.get("*/groupstore/groups/descriptors", () =>
        HttpResponse.json([{ resource: "eddi://ai.labs.group/groupstore/groups/g1?version=1", callerLevel: "VIEW" }]),
      ),
    );
    renderWorkspacePage();

    await screen.findByTestId("consolidation-schedule-state");
    await waitFor(() => expect(screen.queryByTestId("consolidation-run")).not.toBeInTheDocument());
  });

  it("loads the history only when asked, and shows what was removed", async () => {
    let requested = false;
    useGroup(LESSON_CONFIG);
    consolidationState();
    server.use(
      http.get("*/groupstore/groups/:groupId/lessons/history", () => {
        requested = true;
        return HttpResponse.json([
          { id: "h1", groupId: "g1", lessonKey: "retro:k3", lessonValue: "Post on Fridays", action: "RETIRED", reason: "replies were zero", replacedByKey: null, evidence: ["o1"], runId: "r", lessonCreatedAt: null, at: "2026-11-02T10:00:00Z" },
        ]);
      }),
    );
    renderWorkspacePage();

    await screen.findByTestId("lessons-history-toggle");
    expect(requested).toBe(false);
    await userEvent.click(screen.getByTestId("lessons-history-toggle"));

    const row = await screen.findByTestId("history-h1");
    expect(row).toHaveTextContent("Post on Fridays");
    expect(row).toHaveTextContent("replies were zero");
  });
});
