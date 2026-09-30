import { describe, expect, it } from "vitest";
import {
  deleteSecret,
  findSecret,
  listSecrets,
  storeSecret,
  updateSecretGrant,
} from "@/lib/api/secrets";

/**
 * The secret mocks share one mutable store, reset after every test by
 * `src/test/setup.ts`. When the metadata GET read a fixed array the writes never
 * touched, a key created a moment earlier still answered 404 — so the mocked Add
 * flow would accept a second create that the real backend reports as a duplicate.
 */
describe("secret mock handlers share state", () => {
  it("finds and lists a key right after it is created", async () => {
    expect(await findSecret("default", "fresh-key")).toBeNull();

    await storeSecret("default", "fresh-key", "v1", "created in a test", ["agent1"]);

    const found = await findSecret("default", "fresh-key");
    expect(found).toMatchObject({
      keyName: "fresh-key",
      description: "created in a test",
      allowedAgents: ["agent1"],
    });
    expect((await listSecrets("default")).map((s) => s.keyName)).toContain("fresh-key");
  });

  it("resets between tests: the key created above is gone again", async () => {
    expect(await findSecret("default", "fresh-key")).toBeNull();
  });

  it("forgets a deleted key, and a second delete is a 404", async () => {
    expect(await findSecret("default", "google-gemini-key")).not.toBeNull();

    await deleteSecret("default", "google-gemini-key");

    expect(await findSecret("default", "google-gemini-key")).toBeNull();
    await expect(deleteSecret("default", "google-gemini-key")).rejects.toThrow();
  });

  it("keeps a committed grant change, but not a dry run", async () => {
    await updateSecretGrant({
      tenantId: "default",
      keyName: "sendgrid-api-key",
      allowedAgents: ["agent9"],
      dryRun: true,
    });
    expect((await findSecret("default", "sendgrid-api-key"))?.allowedAgents).toEqual([
      "agent1",
      "agent4",
    ]);

    await updateSecretGrant({
      tenantId: "default",
      keyName: "sendgrid-api-key",
      allowedAgents: ["agent9"],
    });
    expect((await findSecret("default", "sendgrid-api-key"))?.allowedAgents).toEqual([
      "agent9",
    ]);
  });
});
