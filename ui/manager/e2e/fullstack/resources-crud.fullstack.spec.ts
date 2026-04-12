import { test, expect } from "@playwright/test";
import {
  waitForFullStack,
  navigateTo,
  API_BASE,
  waitForBackend,
} from "./fullstack-helpers";
import {
  extractIdFromLocation,
  cleanupResource,
} from "../integration/integration-helpers";

/**
 * Resource types and their API store paths.
 * Used for parameterized CRUD testing through the browser UI.
 */
const RESOURCE_TYPES = [
  {
    name: "Behavior Rules",
    urlType: "behavior",
    store: "behaviorstore",
    plural: "behaviorsets",
    createPayload: { behaviorGroups: [] },
  },
  {
    name: "HTTP Calls",
    urlType: "httpcalls",
    store: "httpcallsstore",
    plural: "httpcalls",
    createPayload: { targetServerUrl: "", httpCalls: [] },
  },
  {
    name: "Output Sets",
    urlType: "output",
    store: "outputstore",
    plural: "outputsets",
    createPayload: { outputSet: [] },
  },
  {
    name: "Dictionaries",
    urlType: "dictionaries",
    store: "regulardictionarystore",
    plural: "regulardictionaries",
    createPayload: { words: [], phrases: [], regExs: [] },
  },
  {
    name: "LangChain",
    urlType: "langchain",
    store: "langchainstore",
    plural: "langchains",
    createPayload: { tasks: [] },
  },
  {
    name: "Property Setter",
    urlType: "propertysetter",
    store: "propertysetterstore",
    plural: "propertysetters",
    createPayload: { setOnActions: [] },
  },
] as const;

/**
 * Resources hub and per-type list pages verified with real backend data.
 */
test.describe("Resources — Full Stack", () => {
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

  test("resources hub shows all resource type cards", async ({
    page,
    request,
  }) => {
    await waitForFullStack(page, request, "/manage/resources", {
      skipHealthCheck: true,
    });

    // All 6 resource type cards should be visible
    for (const rt of RESOURCE_TYPES) {
      await expect(
        page.getByTestId(`resource-type-${rt.urlType}`)
      ).toBeVisible();
    }
  });

  for (const rt of RESOURCE_TYPES) {
    test(`${rt.name}: created resource appears in list`, async ({
      page,
      request,
    }) => {
      // Create resource via API
      const basePath = `${rt.store}/${rt.plural}`;
      const createRes = await request.post(`${API_BASE}/${basePath}`, {
        data: rt.createPayload,
      });
      expect(createRes.status()).toBe(201);
      const { id, version } = extractIdFromLocation(
        createRes.headers()["location"]!
      );
      cleanup.push({ storePath: basePath, id, version });

      // Navigate to the resource type list page
      await navigateTo(page, `/manage/resources/${rt.urlType}`);

      // The list should contain at least one resource link
      const resourceLinks = page.locator(
        `main a[href*="/manage/resources/${rt.urlType}/"]`
      );
      await expect(resourceLinks.first()).toBeVisible({ timeout: 10_000 });
    });
  }

  test("resource detail page renders content", async ({ page, request }) => {
    // Create a behavior resource to view in detail
    const createRes = await request.post(
      `${API_BASE}/behaviorstore/behaviorsets`,
      { data: { behaviorGroups: [] } }
    );
    expect(createRes.status()).toBe(201);
    const { id, version } = extractIdFromLocation(
      createRes.headers()["location"]!
    );
    cleanup.push({
      storePath: "behaviorstore/behaviorsets",
      id,
      version,
    });

    await navigateTo(page, `/manage/resources/behavior/${id}`);

    // Back link should be visible
    await expect(page.getByTestId("back-to-list")).toBeVisible();

    // Main content area should have rendered
    await expect(page.locator("main").first()).toBeVisible();
  });

  test("resource type card navigates to list page", async ({ page }) => {
    await navigateTo(page, "/manage/resources");

    await page.getByTestId("resource-type-behavior").click();
    await expect(page).toHaveURL(/\/manage\/resources\/behavior/);
  });
});
