import { test, expect } from "@playwright/test";
import {
  API_BASE,
  waitForBackend,
  extractIdFromLocation,
  cleanupResource,
} from "./integration-helpers";

/**
 * Deployment lifecycle tests.
 * Serial mode — deploy → check status → undeploy must run in order.
 * Fully self-contained: creates its own bot + package.
 *
 * Key API behaviors:
 * - POST /administration/unrestricted/deploy returns 202 (accepted)
 * - GET /administration/unrestricted/deploymentstatus returns JSON {"status":"READY"}
 *   (use ?format=text for plain text, deprecated)
 */
test.describe("Deployment — Real Backend", () => {
  test.describe.configure({ timeout: 120_000, mode: "serial" });

  let testBotId: string;
  let testBotVersion = 1;
  let testPackageId: string;
  let testPackageVersion = 1;

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);

    // Create a package for our test bot
    const pkgRes = await request.post(`${API_BASE}/packagestore/packages`, {
      data: { packageExtensions: [] },
    });
    expect(pkgRes.status()).toBe(201);
    const pkgLoc = pkgRes.headers()["location"]!;
    const pkg = extractIdFromLocation(pkgLoc);
    testPackageId = pkg.id;
    testPackageVersion = pkg.version;

    // Create a bot referencing the package
    const botRes = await request.post(`${API_BASE}/botstore/bots`, {
      data: { packages: [pkgLoc] },
    });
    expect(botRes.status()).toBe(201);
    const botLoc = botRes.headers()["location"]!;
    const bot = extractIdFromLocation(botLoc);
    testBotId = bot.id;
    testBotVersion = bot.version;
  });

  test.afterAll(async ({ request }) => {
    // Undeploy (best-effort)
    try {
      await request.post(
        `${API_BASE}/administration/unrestricted/undeploy/${testBotId}?version=${testBotVersion}`
      );
    } catch {
      /* ignore */
    }
    await cleanupResource(
      request,
      "botstore/bots",
      testBotId,
      testBotVersion
    );
    await cleanupResource(
      request,
      "packagestore/packages",
      testPackageId,
      testPackageVersion
    );
  });

  test("Deploy bot returns 202 (accepted)", async ({ request }) => {
    const res = await request.post(
      `${API_BASE}/administration/unrestricted/deploy/${testBotId}?version=${testBotVersion}`
    );
    expect([200, 202]).toContain(res.status());

    // Wait for deployment to complete
    const start = Date.now();
    while (Date.now() - start < 15_000) {
      const statusRes = await request.get(
        `${API_BASE}/administration/unrestricted/deploymentstatus/${testBotId}?version=${testBotVersion}`
      );
      if (statusRes.ok()) {
        const body = await statusRes.json();
        if (body.status === "READY") break;
      }
      await new Promise((r) => setTimeout(r, 1000));
    }
  });

  test("Get deployment status returns READY (JSON)", async ({
    request,
  }) => {
    const res = await request.get(
      `${API_BASE}/administration/unrestricted/deploymentstatus/${testBotId}?version=${testBotVersion}`
    );
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(body).toHaveProperty("status", "READY");
  });

  test("Deployment status for non-deployed environment returns NOT_FOUND", async ({
    request,
  }) => {
    const res = await request.get(
      `${API_BASE}/administration/test/deploymentstatus/${testBotId}?version=${testBotVersion}`
    );
    if (res.ok()) {
      const body = await res.json();
      expect(body.status).toBe("NOT_FOUND");
    } else {
      expect([404, 400]).toContain(res.status());
    }
  });

  test("Undeploy bot succeeds", async ({ request }) => {
    const res = await request.post(
      `${API_BASE}/administration/unrestricted/undeploy/${testBotId}?version=${testBotVersion}`
    );
    expect(res.ok()).toBeTruthy();

    // Wait for undeployment
    await new Promise((r) => setTimeout(r, 2000));

    // Verify NOT_FOUND after undeploy
    const statusRes = await request.get(
      `${API_BASE}/administration/unrestricted/deploymentstatus/${testBotId}?version=${testBotVersion}`
    );
    if (statusRes.ok()) {
      const body = await statusRes.json();
      expect(body.status).toBe("NOT_FOUND");
    }
  });
});
