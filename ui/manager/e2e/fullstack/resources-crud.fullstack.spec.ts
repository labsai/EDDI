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
interface ResourceTypeCase {
  name: string;
  urlType: string;
  store: string;
  plural: string;
  createPayload: Record<string, unknown>;
}

/**
 * KNOWN FAILURE, unresolved: whichever type runs FIRST does not appear in its
 * store's descriptor list, and the case for it fails.
 *
 * What five dispatches against a live backend established:
 *
 *  - It follows POSITION, not type. Rules failed while first; removing Rules
 *    moved the failure to API Calls; restoring it moved it back.
 *  - The resource IS created — 201, and retrievable by id. Only the descriptor
 *    is missing, on both MongoDB and Postgres.
 *  - It is not a UI or caching problem. Polling the descriptors API directly,
 *    before any page is opened, fails the same way.
 *  - No warm-up fixes it: not one create/delete before the suite, not one per
 *    store inside each case.
 *  - And a readiness probe in `beforeAll` that waited for a created resource to
 *    be listed timed out after NINETY seconds — while a case running about a
 *    minute into the same run passes. Those two facts do not fit together, so
 *    the mechanism is still unknown and anything written here would be a guess.
 *    Three guesses have already been wrong.
 *
 * Tracked upstream: https://github.com/labsai/EDDI/issues/712
 *
 * It is left failing on purpose. The tier reports one true thing rather than
 * hiding it behind a skip, and the message now names the store and the id
 * instead of timing out against a UI locator. Worth raising upstream with the
 * run ids above rather than absorbing here.
 */
const RESOURCE_TYPES: ResourceTypeCase[] = [
  {
    name: "Rules",
    urlType: "rules",
    store: "rulestore",
    plural: "rulesets",
    createPayload: { behaviorGroups: [] },
  },
  {
    name: "API Calls",
    urlType: "apicalls",
    store: "apicallstore",
    plural: "apicalls",
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
    urlType: "dictionary",
    store: "dictionarystore",
    plural: "dictionaries",
    createPayload: { words: [], phrases: [], regExs: [] },
  },
  {
    name: "LLM",
    urlType: "llm",
    store: "llmstore",
    plural: "llms",
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

      // Wait for the BACKEND to list it before opening the page.
      //
      // Not because of any theory about when the page fetches — I had one and
      // it was wrong. Because asking the API directly is what turns a UI locator
      // timing out into a sentence naming the store, the id and what the
      // backend actually returned. See the note above RESOURCE_TYPES for what
      // that message went on to establish.
      const descriptorsUrl = `${API_BASE}/${basePath}/descriptors?limit=100&index=0`;

      // A hard error is not 'not listed yet'. Polling a 401/404/500 for thirty
      // seconds and then reporting that the resource never appeared would be
      // the same species of false message this file exists to remove, so the
      // endpoint is checked once up front and the status is in the failure.
      const firstLook = await request.get(descriptorsUrl);
      expect(
        firstLook.ok(),
        `${basePath}/descriptors responded HTTP ${firstLook.status()}`
      ).toBe(true);

      await expect
        .poll(
          async () => {
            const res = await request.get(descriptorsUrl);
            if (!res.ok()) {
              // `expect.poll` swallows this and keeps polling, but it reports
              // the last error's message on timeout — so the status still
              // reaches the log rather than being flattened into an empty list.
              throw new Error(
                `${basePath}/descriptors responded HTTP ${res.status()}`
              );
            }
            const descriptors = (await res.json()) as Array<{ resource?: string }>;
            return descriptors.map((d) => d.resource ?? "");
          },
          {
            timeout: 30_000,
            message: `${rt.name} was created but never appeared in ${basePath}/descriptors`,
          }
        )
        .toEqual(expect.arrayContaining([expect.stringContaining(id)]));

      // Navigate to the resource type list page
      await navigateTo(page, `/manage/resources/${rt.urlType}`);

      // The resource THIS test created, by id — not merely 'a link exists'.
      await expect(
        page.locator(`main a[href*="/manage/resources/${rt.urlType}/${id}"]`)
      ).toBeVisible({ timeout: 10_000 });
    });
  }

  test("resource detail page renders content", async ({ page, request }) => {
    // Create a behavior resource to view in detail
    const createRes = await request.post(
      `${API_BASE}/rulestore/rulesets`,
      { data: { behaviorGroups: [] } }
    );
    expect(createRes.status()).toBe(201);
    const { id, version } = extractIdFromLocation(
      createRes.headers()["location"]!
    );
    cleanup.push({
      storePath: "rulestore/rulesets",
      id,
      version,
    });

    // `rules`, not `behavior`: the resource is created through
    // `rulestore/rulesets`, so opening the legacy slug would route to a
    // different (non-existent) type and render the "unknown type" error state
    // while the two assertions below still passed.
    await navigateTo(page, `/manage/resources/rules/${id}`);

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

