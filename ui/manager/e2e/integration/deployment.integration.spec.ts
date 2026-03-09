import { test, expect } from "@playwright/test";
import {
  API_BASE,
  waitForBackend,
  extractIdFromLocation,
  cleanupResource,
} from "./integration-helpers";

test.describe("Deployment — Real Backend", () => {
  let testBotId: string | null = null;
  let testBotVersion = 1;
  let testPackageId: string | null = null;
  let testPackageVersion = 1;
  let didDeploy = false;

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);

    // Create a package for our test bot
    const pkgRes = await request.post(`${API_BASE}/packagestore/packages`, {
      data: { packageExtensions: [] },
    });
    if (pkgRes.status() === 201) {
      const pkgLoc = pkgRes.headers()["location"]!;
      const pkg = extractIdFromLocation(pkgLoc);
      testPackageId = pkg.id;
      testPackageVersion = pkg.version;

      // Create a bot referencing the package
      const botRes = await request.post(`${API_BASE}/botstore/bots`, {
        data: { packages: [pkgLoc] },
      });
      if (botRes.status() === 201) {
        const botLoc = botRes.headers()["location"]!;
        const bot = extractIdFromLocation(botLoc);
        testBotId = bot.id;
        testBotVersion = bot.version;
      }
    }
  });

  test.afterAll(async ({ request }) => {
    // Undeploy if we deployed
    if (didDeploy && testBotId) {
      try {
        await request.post(
          `${API_BASE}/administration/unrestricted/undeploy/${testBotId}`
        );
      } catch {
        // Ignore
      }
    }
    // Cleanup bot and package
    if (testBotId) {
      await cleanupResource(
        request,
        "botstore/bots",
        testBotId,
        testBotVersion
      );
    }
    if (testPackageId) {
      await cleanupResource(
        request,
        "packagestore/packages",
        testPackageId,
        testPackageVersion
      );
    }
  });

  test("Deploy bot to unrestricted environment", async ({ request }) => {
    test.skip(!testBotId, "Test bot creation failed");

    const res = await request.post(
      `${API_BASE}/administration/unrestricted/deploy/${testBotId}?version=${testBotVersion}`
    );
    expect(res.ok()).toBeTruthy();
    didDeploy = true;

    // Wait for deployment to complete
    await new Promise((r) => setTimeout(r, 5000));
  });

  test("Get deployment status", async ({ request }) => {
    test.skip(!testBotId || !didDeploy, "Bot not deployed");

    const res = await request.get(
      `${API_BASE}/administration/unrestricted/deploymentstatus/${testBotId}`
    );
    expect(res.ok()).toBeTruthy();
    const status = await res.json();
    expect(["READY", "IN_PROGRESS"]).toContain(status.status);
  });

  test("Deployment status for non-deployed environment returns NOT_FOUND", async ({
    request,
  }) => {
    test.skip(!testBotId, "Test bot creation failed");

    const res = await request.get(
      `${API_BASE}/administration/test/deploymentstatus/${testBotId}`
    );
    // Could be 200 with NOT_FOUND status, or 404
    if (res.ok()) {
      const status = await res.json();
      expect(status.status).toBe("NOT_FOUND");
    } else {
      expect([404, 400]).toContain(res.status());
    }
  });

  test("Undeploy bot", async ({ request }) => {
    test.skip(!testBotId || !didDeploy, "Bot not deployed");

    const res = await request.post(
      `${API_BASE}/administration/unrestricted/undeploy/${testBotId}`
    );
    expect(res.ok()).toBeTruthy();
    didDeploy = false;

    // Wait for undeployment
    await new Promise((r) => setTimeout(r, 2000));

    // Verify NOT_FOUND after undeploy
    const statusRes = await request.get(
      `${API_BASE}/administration/unrestricted/deploymentstatus/${testBotId}`
    );
    if (statusRes.ok()) {
      const status = await statusRes.json();
      expect(status.status).toBe("NOT_FOUND");
    }
  });
});
