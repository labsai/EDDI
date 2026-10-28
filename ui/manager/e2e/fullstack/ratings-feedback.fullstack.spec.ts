import { test, expect, type APIRequestContext } from "@playwright/test";
import { waitForFullStack, waitForBackend } from "./fullstack-helpers";
import { API_BASE, cleanupResource, extractIdFromLocation } from "../integration/integration-helpers";

/**
 * The rating loop across both UIs against the real backend: rate a conversation in
 * the Chat UI (served by EDDI at /chat), find it in the Manager's Feedback page under
 * "Needs attention" with the sidebar badge, mark it reviewed, and see the attention
 * count drop.
 *
 * The Chat UI is served by the backend itself, not the Manager's dev server, so it
 * is opened on the backend's port.
 */
const CHAT_BASE = `http://localhost:${Number(process.env.EDDI_PORT) || 7070}`;

type ResourceRef = ReturnType<typeof extractIdFromLocation>;

/** What setup created so far — filled in as each resource exists, so cleanup works after a partial failure. */
interface Created {
  workflow?: ResourceRef;
  agent?: ResourceRef;
}

async function createRatedAgent(request: APIRequestContext, created: Created) {
  const workflow = await request.post(`${API_BASE}/workflowstore/workflows`, { data: { workflowSteps: [] } });
  expect(workflow.status()).toBe(201);
  const workflowLocation = workflow.headers()["location"]!;
  created.workflow = extractIdFromLocation(workflowLocation);
  const agentRes = await request.post(`${API_BASE}/agentstore/agents`, {
    data: {
      workflows: [workflowLocation],
      ratingConfig: {
        enabled: true,
        scale: "FIVE_STAR",
        conversationRating: { enabled: true, askOnEnd: false, askAfterTurns: 0 },
        comment: { enabled: true, maxLength: 1000 },
      },
    },
  });
  expect(agentRes.status()).toBe(201);
  const agent = extractIdFromLocation(agentRes.headers()["location"]!);
  created.agent = agent;
  const deploy = await request.post(`${API_BASE}/administration/production/deploy/${agent.id}?version=${agent.version}`);
  expect([200, 202]).toContain(deploy.status());
  await expect
    .poll(
      async () => {
        const status = await request.get(`${API_BASE}/administration/production/deploymentstatus/${agent.id}?version=${agent.version}`);
        return status.ok() ? (await status.json()).status : "UNKNOWN";
      },
      { timeout: 30_000 },
    )
    .toBe("READY");
  return agent;
}

async function attentionCount(request: APIRequestContext): Promise<number> {
  const res = await request.get(`${API_BASE}/ratings/attention-count`);
  expect(res.ok()).toBe(true);
  return (await res.json()).count as number;
}

test.describe("Ratings — Chat UI to Feedback triage (full stack)", () => {
  test.describe.configure({ timeout: 180_000, mode: "serial" });

  const created: Created = {};

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
    await createRatedAgent(request, created);
  });

  test.afterAll(async ({ request }) => {
    // Every step on its own: one failing cleanup must not leave the others behind.
    const { agent, workflow } = created;
    // An agent that never got deployed answers the undeploy with an error, which is ignored.
    if (agent) {
      await request
        .post(`${API_BASE}/administration/production/undeploy/${agent.id}?version=${agent.version}`)
        .catch(() => undefined);
    }
    if (agent) {
      await cleanupResource(request, "agentstore/agents", agent.id, agent.version).catch(() => undefined);
    }
    if (workflow) {
      await cleanupResource(request, "workflowstore/workflows", workflow.id, workflow.version).catch(() => undefined);
    }
  });

  test("a low rating with a comment lands in Needs attention, and reviewing it clears it", async ({ page, request }) => {
    const agentId = created.agent!.id;
    const before = await attentionCount(request);

    // 1. Rate in the Chat UI.
    await page.goto(`${CHAT_BASE}/chat/production/${agentId}`);
    await expect(page.getByTestId("chat-input")).toBeVisible({ timeout: 30_000 });
    await page.getByTestId("rate-conversation").click();
    await page.getByTestId("rating-star-1").click();
    await page.getByTestId("rating-card-comment").fill("E2E: it kept looping");
    await page.getByTestId("rating-card-send").click();
    await expect(page.getByTestId("rating-card-thanks")).toBeVisible();

    await expect.poll(() => attentionCount(request), { timeout: 15_000 }).toBe(before + 1);
    const list = await request.get(`${API_BASE}/ratings?agentId=${agentId}&needsAttention=true`);
    const rating = (await list.json()).ratings[0] as { id: string; comment: string };
    expect(rating.comment).toBe("E2E: it kept looping");

    // 2. The Manager shows it under Needs attention, with the sidebar badge.
    await waitForFullStack(page, request, `/manage/feedback?agentId=${agentId}`, { skipHealthCheck: true });
    await expect(page.getByTestId("feedback-tab-attention")).toHaveAttribute("aria-selected", "true");
    await expect(page.getByTestId(`rating-row-${rating.id}`)).toBeVisible();
    await expect(page.getByTestId("nav-feedback-badge")).toBeVisible();

    // 3. Mark it reviewed: it leaves the list and the attention count drops back.
    await page.getByTestId(`rating-review-${rating.id}`).click();
    await expect(page.getByTestId(`rating-row-${rating.id}`)).toHaveCount(0);
    await expect.poll(() => attentionCount(request), { timeout: 15_000 }).toBe(before);
    if (before === 0) {
      await expect(page.getByTestId("nav-feedback-badge")).toHaveCount(0, { timeout: 45_000 });
    }
  });
});
