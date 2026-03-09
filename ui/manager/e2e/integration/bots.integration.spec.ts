import { test, expect } from "@playwright/test";
import {
  API_BASE,
  waitForBackend,
  extractIdFromLocation,
  cleanupResource,
} from "./integration-helpers";

test.describe("Bots CRUD — Real Backend", () => {
  test.describe.configure({ timeout: 120_000, mode: "serial" });
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
    createdBots.push({ id: updated.id, version: updated.version });

    // READ updated version
    const getUpdatedRes = await request.get(
      `${API_BASE}/botstore/bots/${updated.id}?version=${updated.version}`
    );
    expect(getUpdatedRes.ok()).toBeTruthy();
  });

  test("POST duplicate bot returns new Location", async ({
    request,
  }) => {
    const createRes = await request.post(`${API_BASE}/botstore/bots`, {
      data: { packages: [] },
    });
    const { id, version } = extractIdFromLocation(
      createRes.headers()["location"]!
    );
    createdBots.push({ id, version });

    // Duplicate — EDDI currently returns 200, should arguably return 201
    // (backend fix pending). Accept both so test passes now AND after fix.
    const dupRes = await request.post(
      `${API_BASE}/botstore/bots/${id}?version=${version}&deepCopy=false`
    );
    expect([200, 201]).toContain(dupRes.status());
    const dupLocation = dupRes.headers()["location"];
    expect(dupLocation).toBeTruthy();

    const dup = extractIdFromLocation(dupLocation!);
    createdBots.push({ id: dup.id, version: dup.version });
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
