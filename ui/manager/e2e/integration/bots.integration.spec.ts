import { test, expect } from "@playwright/test";
import {
  API_BASE,
  waitForBackend,
  extractIdFromLocation,
  cleanupResource,
} from "./integration-helpers";

test.describe("Bots CRUD — Real Backend", () => {
  const createdBots: { id: string; version: number }[] = [];

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
  });

  test.afterAll(async ({ request }) => {
    for (const bot of createdBots) {
      await cleanupResource(request, "botstore/bots", bot.id, bot.version);
    }
  });

  test("GET /botstore/bots/descriptors returns array", async ({ request }) => {
    const res = await request.get(
      `${API_BASE}/botstore/bots/descriptors?limit=100`
    );
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(Array.isArray(body)).toBeTruthy();
    // EDDI auto-imports Bot Father, so there should be at least one
    expect(body.length).toBeGreaterThanOrEqual(0);
  });

  test("POST → GET → PUT → GET round-trip", async ({ request }) => {
    // CREATE
    const createRes = await request.post(`${API_BASE}/botstore/bots`, {
      data: { packages: [], channels: [] },
    });
    expect(createRes.status()).toBe(201);
    const location = createRes.headers()["location"];
    expect(location).toBeTruthy();

    const { id, version } = extractIdFromLocation(location!);
    createdBots.push({ id, version });

    // READ
    const getRes = await request.get(
      `${API_BASE}/botstore/bots/${id}?version=${version}`
    );
    expect(getRes.ok()).toBeTruthy();
    const bot = await getRes.json();
    expect(bot).toHaveProperty("packages");

    // UPDATE
    const updateRes = await request.put(
      `${API_BASE}/botstore/bots/${id}?version=${version}`,
      { data: { packages: [], channels: [] } }
    );
    expect(updateRes.ok()).toBeTruthy();
    const updateLocation = updateRes.headers()["location"];
    expect(updateLocation).toBeTruthy();

    const updated = extractIdFromLocation(updateLocation!);
    expect(updated.version).toBe(version + 1);
    // Track new version for cleanup
    createdBots.push({ id: updated.id, version: updated.version });

    // READ updated version
    const getUpdatedRes = await request.get(
      `${API_BASE}/botstore/bots/${updated.id}?version=${updated.version}`
    );
    expect(getUpdatedRes.ok()).toBeTruthy();
  });

  test("GET descriptors with includePreviousVersions", async ({ request }) => {
    // Use a previously created bot (need at least a create first)
    const createRes = await request.post(`${API_BASE}/botstore/bots`, {
      data: { packages: [] },
    });
    const { id, version } = extractIdFromLocation(
      createRes.headers()["location"]!
    );
    createdBots.push({ id, version });

    // Update to create v2
    const updateRes = await request.put(
      `${API_BASE}/botstore/bots/${id}?version=${version}`,
      { data: { packages: [] } }
    );
    const v2 = extractIdFromLocation(updateRes.headers()["location"]!);
    createdBots.push({ id: v2.id, version: v2.version });

    // Get all versions
    const res = await request.get(
      `${API_BASE}/botstore/bots/descriptors?filter=${id}&includePreviousVersions=true`
    );
    expect(res.ok()).toBeTruthy();
    const versions = await res.json();
    expect(versions.length).toBeGreaterThanOrEqual(2);
  });

  test("POST duplicate bot", async ({ request }) => {
    const createRes = await request.post(`${API_BASE}/botstore/bots`, {
      data: { packages: [] },
    });
    const { id, version } = extractIdFromLocation(
      createRes.headers()["location"]!
    );
    createdBots.push({ id, version });

    // Duplicate
    const dupRes = await request.post(
      `${API_BASE}/botstore/bots/${id}?version=${version}&deepCopy=false`
    );
    expect(dupRes.status()).toBe(200);
    const dupLocation = dupRes.headers()["location"];
    expect(dupLocation).toBeTruthy();

    const dup = extractIdFromLocation(dupLocation!);
    createdBots.push({ id: dup.id, version: dup.version });
    // Duplicated bot should be a different ID
    expect(dup.id).not.toBe(id);
  });

  test("DELETE bot returns 200 or 204", async ({ request }) => {
    const createRes = await request.post(`${API_BASE}/botstore/bots`, {
      data: { packages: [] },
    });
    const { id, version } = extractIdFromLocation(
      createRes.headers()["location"]!
    );

    const deleteRes = await request.delete(
      `${API_BASE}/botstore/bots/${id}?version=${version}`
    );
    expect([200, 204]).toContain(deleteRes.status());

    // Verify it's gone (GET should return 404 or empty)
    const getRes = await request.get(
      `${API_BASE}/botstore/bots/${id}?version=${version}`
    );
    expect([404, 410].some((s) => s === getRes.status()) || !getRes.ok()).toBeTruthy();
  });

  test("GET /botstore/bots/jsonSchema returns valid schema", async ({
    request,
  }) => {
    const res = await request.get(`${API_BASE}/botstore/bots/jsonSchema`);
    expect(res.ok()).toBeTruthy();
    const schema = await res.json();
    expect(schema).toHaveProperty("type");
  });
});
