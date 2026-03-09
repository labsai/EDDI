import { test, expect } from "@playwright/test";
import {
  API_BASE,
  waitForBackend,
  extractIdFromLocation,
  cleanupResource,
} from "./integration-helpers";

test.describe("Packages CRUD — Real Backend", () => {
  const createdPackages: { id: string; version: number }[] = [];

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
  });

  test.afterAll(async ({ request }) => {
    for (const pkg of createdPackages) {
      await cleanupResource(
        request,
        "packagestore/packages",
        pkg.id,
        pkg.version
      );
    }
  });

  test("GET /packagestore/packages/descriptors returns array", async ({
    request,
  }) => {
    const res = await request.get(
      `${API_BASE}/packagestore/packages/descriptors?limit=100`
    );
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(Array.isArray(body)).toBeTruthy();
  });

  test("POST → GET → PUT → GET round-trip", async ({ request }) => {
    // CREATE empty package
    const createRes = await request.post(`${API_BASE}/packagestore/packages`, {
      data: { packageExtensions: [] },
    });
    expect(createRes.status()).toBe(201);
    const location = createRes.headers()["location"];
    expect(location).toBeTruthy();

    const { id, version } = extractIdFromLocation(location!);
    createdPackages.push({ id, version });

    // READ
    const getRes = await request.get(
      `${API_BASE}/packagestore/packages/${id}?version=${version}`
    );
    expect(getRes.ok()).toBeTruthy();
    const pkg = await getRes.json();
    expect(pkg).toHaveProperty("packageExtensions");
    expect(Array.isArray(pkg.packageExtensions)).toBeTruthy();

    // UPDATE — add a dummy extension reference
    const updateRes = await request.put(
      `${API_BASE}/packagestore/packages/${id}?version=${version}`,
      { data: { packageExtensions: [] } }
    );
    expect(updateRes.ok()).toBeTruthy();
    const updateLocation = updateRes.headers()["location"];
    expect(updateLocation).toBeTruthy();

    const updated = extractIdFromLocation(updateLocation!);
    expect(updated.version).toBe(version + 1);
    createdPackages.push({ id: updated.id, version: updated.version });

    // READ updated
    const getUpdatedRes = await request.get(
      `${API_BASE}/packagestore/packages/${updated.id}?version=${updated.version}`
    );
    expect(getUpdatedRes.ok()).toBeTruthy();
  });

  test("GET descriptors with includePreviousVersions", async ({ request }) => {
    const createRes = await request.post(`${API_BASE}/packagestore/packages`, {
      data: { packageExtensions: [] },
    });
    const { id, version } = extractIdFromLocation(
      createRes.headers()["location"]!
    );
    createdPackages.push({ id, version });

    // Create v2
    const updateRes = await request.put(
      `${API_BASE}/packagestore/packages/${id}?version=${version}`,
      { data: { packageExtensions: [] } }
    );
    const v2 = extractIdFromLocation(updateRes.headers()["location"]!);
    createdPackages.push({ id: v2.id, version: v2.version });

    const res = await request.get(
      `${API_BASE}/packagestore/packages/descriptors?filter=${id}&includePreviousVersions=true`
    );
    expect(res.ok()).toBeTruthy();
    const versions = await res.json();
    expect(versions.length).toBeGreaterThanOrEqual(2);
  });

  test("DELETE package returns 200 or 204", async ({ request }) => {
    const createRes = await request.post(`${API_BASE}/packagestore/packages`, {
      data: { packageExtensions: [] },
    });
    const { id, version } = extractIdFromLocation(
      createRes.headers()["location"]!
    );

    const deleteRes = await request.delete(
      `${API_BASE}/packagestore/packages/${id}?version=${version}`
    );
    expect([200, 204]).toContain(deleteRes.status());
  });

  test("GET /packagestore/packages/jsonSchema returns valid schema", async ({
    request,
  }) => {
    const res = await request.get(
      `${API_BASE}/packagestore/packages/jsonSchema`
    );
    expect(res.ok()).toBeTruthy();
    const schema = await res.json();
    expect(schema).toHaveProperty("type");
  });
});
