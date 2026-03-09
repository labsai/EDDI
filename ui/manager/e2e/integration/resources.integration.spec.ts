import { test, expect } from "@playwright/test";
import {
  API_BASE,
  waitForBackend,
  extractIdFromLocation,
  cleanupResource,
} from "./integration-helpers";

/**
 * All 6 resource types supported by the Manager, with their store paths
 * and minimal valid create payloads.
 *
 * These tests can run in parallel since each resource type is independent.
 */
const RESOURCE_TYPES = [
  {
    name: "Behavior Rules",
    store: "behaviorstore",
    plural: "behaviorsets",
    createPayload: { behaviorGroups: [] },
  },
  {
    name: "HTTP Calls",
    store: "httpcallsstore",
    plural: "httpcalls",
    createPayload: { targetServerUrl: "", httpCalls: [] },
  },
  {
    name: "Output Sets",
    store: "outputstore",
    plural: "outputsets",
    createPayload: { outputSet: [] },
  },
  {
    name: "Regular Dictionaries",
    store: "regulardictionarystore",
    plural: "regulardictionaries",
    createPayload: { words: [], phrases: [], regExs: [] },
  },
  {
    name: "LangChain",
    store: "langchainstore",
    plural: "langchains",
    createPayload: { tasks: [] },
  },
  {
    name: "Property Setter",
    store: "propertysetterstore",
    plural: "propertysetters",
    createPayload: { setOnActions: [] },
  },
];

test.describe("Resources CRUD — Real Backend", () => {
  test.describe.configure({ timeout: 120_000, mode: "serial" });
  const cleanup: { storePath: string; id: string; version: number }[] = [];

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
  });

  test.afterAll(async ({ request }) => {
    for (const item of cleanup) {
      await cleanupResource(request, item.storePath, item.id, item.version);
    }
  });

  for (const rt of RESOURCE_TYPES) {
    const basePath = `${rt.store}/${rt.plural}`;

    test(`${rt.name}: CREATE → READ → UPDATE → DELETE`, async ({
      request,
    }) => {
      // CREATE
      const createRes = await request.post(`${API_BASE}/${basePath}`, {
        data: rt.createPayload,
      });
      expect(createRes.status()).toBe(201);
      const location = createRes.headers()["location"];
      expect(location).toBeTruthy();

      const { id, version } = extractIdFromLocation(location!);
      cleanup.push({ storePath: basePath, id, version });

      // READ
      const getRes = await request.get(
        `${API_BASE}/${basePath}/${id}?version=${version}`
      );
      expect(getRes.ok()).toBeTruthy();
      const resource = await getRes.json();
      expect(resource).toBeTruthy();

      // UPDATE
      const updateRes = await request.put(
        `${API_BASE}/${basePath}/${id}?version=${version}`,
        { data: rt.createPayload }
      );
      expect(updateRes.ok()).toBeTruthy();
      const updateLocation = updateRes.headers()["location"];
      expect(updateLocation).toBeTruthy();

      const updated = extractIdFromLocation(updateLocation!);
      expect(updated.version).toBe(version + 1);
      cleanup.push({
        storePath: basePath,
        id: updated.id,
        version: updated.version,
      });

      // DELETE — must delete newest version first (EDDI returns 409 if newer exists)
      const deleteV2 = await request.delete(
        `${API_BASE}/${basePath}/${updated.id}?version=${updated.version}`
      );
      expect([200, 204]).toContain(deleteV2.status());

      // v1 soft-delete may return 409 (newer version exists),
      // 404 (some stores cascade deletes), or 200/204 (success).
      const deleteRes = await request.delete(
        `${API_BASE}/${basePath}/${id}?version=${version}`
      );
      expect([200, 204, 404, 409]).toContain(deleteRes.status());
    });

    test(`${rt.name}: GET descriptors returns array`, async ({ request }) => {
      const res = await request.get(
        `${API_BASE}/${basePath}/descriptors?limit=10`
      );
      expect(res.ok()).toBeTruthy();
      const body = await res.json();
      expect(Array.isArray(body)).toBeTruthy();
    });

    test(`${rt.name}: JSON Schema endpoint`, async ({ request }) => {
      const res = await request.get(`${API_BASE}/${basePath}/jsonSchema`);
      expect(res.ok()).toBeTruthy();
      const schema = await res.json();
      expect(schema).toHaveProperty("type");
    });
  }
});
