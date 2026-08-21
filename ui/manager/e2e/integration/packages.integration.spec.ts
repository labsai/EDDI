import { test, expect } from "@playwright/test";
import {
  API_BASE,
  waitForBackend,
  extractIdFromLocation,
  cleanupResource,
} from "./integration-helpers";

test.describe("Workflows CRUD — Real Backend", () => {
  test.describe.configure({ timeout: 120_000, mode: "serial" });
  const createdWorkflows: { id: string; version: number }[] = [];

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
  });

  test.afterAll(async ({ request }) => {
    for (const pkg of createdWorkflows) {
      await cleanupResource(
        request,
        "workflowstore/workflows",
        pkg.id,
        pkg.version
      );
    }
  });

  test("GET /workflowstore/workflows/descriptors returns array", async ({
    request,
  }) => {
    const res = await request.get(
      `${API_BASE}/workflowstore/workflows/descriptors?limit=100`
    );
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(Array.isArray(body)).toBeTruthy();
  });

  test("POST → GET → PUT → GET round-trip", async ({ request }) => {
    // CREATE empty package
    const createRes = await request.post(`${API_BASE}/workflowstore/workflows`, {
      data: { workflowSteps: [] },
    });
    expect(createRes.status()).toBe(201);
    const location = createRes.headers()["location"];
    expect(location).toBeTruthy();

    const { id, version } = extractIdFromLocation(location!);
    createdWorkflows.push({ id, version });

    // READ
    const getRes = await request.get(
      `${API_BASE}/workflowstore/workflows/${id}?version=${version}`
    );
    expect(getRes.ok()).toBeTruthy();
    const pkg = await getRes.json();
    expect(pkg).toHaveProperty("workflowSteps");
    expect(Array.isArray(pkg.workflowSteps)).toBeTruthy();

    // UPDATE
    const updateRes = await request.put(
      `${API_BASE}/workflowstore/workflows/${id}?version=${version}`,
      { data: { workflowSteps: [] } }
    );
    expect(updateRes.ok()).toBeTruthy();
    const updateLocation = updateRes.headers()["location"];
    expect(updateLocation).toBeTruthy();

    const updated = extractIdFromLocation(updateLocation!);
    expect(updated.version).toBe(version + 1);
    createdWorkflows.push({ id: updated.id, version: updated.version });

    // READ updated
    const getUpdatedRes = await request.get(
      `${API_BASE}/workflowstore/workflows/${updated.id}?version=${updated.version}`
    );
    expect(getUpdatedRes.ok()).toBeTruthy();
  });

  test("DELETE package returns 200 or 204", async ({ request }) => {
    const createRes = await request.post(`${API_BASE}/workflowstore/workflows`, {
      data: { workflowSteps: [] },
    });
    const { id, version } = extractIdFromLocation(
      createRes.headers()["location"]!
    );

    const deleteRes = await request.delete(
      `${API_BASE}/workflowstore/workflows/${id}?version=${version}`
    );
    expect([200, 204]).toContain(deleteRes.status());
  });

  test("GET /workflowstore/workflows/jsonSchema returns valid schema", async ({
    request,
  }) => {
    const res = await request.get(
      `${API_BASE}/workflowstore/workflows/jsonSchema`
    );
    expect(res.ok()).toBeTruthy();
    const schema = await res.json();
    expect(schema).toHaveProperty("type");
  });
});
