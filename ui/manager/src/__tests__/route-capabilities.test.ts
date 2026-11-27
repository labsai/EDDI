import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { capabilityForScreen } from "@eddi/app-kit/lib/roles";

/**
 * Pins the fail-open rule. A path with no entry in the permission map is open
 * to every role, so a route added to `app.tsx` without a capability would be
 * silently ungated. Every route is therefore either gated or on this list, which
 * is a decision somebody wrote down: these screens are for any signed-in role.
 */
const OPEN_TO_EVERY_ROLE = new Set([
  "/welcome",
  "/",
  "*",
  // The conversation list admits every role (owner check on the backend); the
  // agent-name lookups inside it are gated by `agents.read`.
  "/manage/conversations",
  "/manage/conversationview/:id",
  // Per user, not per role.
  "/manage/linked-accounts",
  // Hidden by the brand, not by role; the page checks `eddi-admin` itself.
  "/manage/updates",
  // Redirects to /manage/userdata, which is gated.
  "/manage/memories",
  "/manage/properties",
  "/manage/user-conversations",
]);

function routePaths(): string[] {
  const source = readFileSync(resolve(__dirname, "../app.tsx"), "utf8");
  return [...source.matchAll(/<Route\s+(?:\n\s*)?path="([^"]+)"/g)].map((m) => m[1]!);
}

describe("every Manager route is gated or deliberately open", () => {
  const paths = routePaths();

  it("finds the route table", () => {
    expect(paths.length).toBeGreaterThan(40);
    expect(paths).toContain("/manage/agents");
  });

  it.each(paths)("%s", (path) => {
    const gated = capabilityForScreen(path) !== null;
    expect(gated || OPEN_TO_EVERY_ROLE.has(path)).toBe(true);
  });

  it("does not let the allow-list name a gated route (a stale entry hides a decision)", () => {
    for (const path of OPEN_TO_EVERY_ROLE) {
      if (path.startsWith("/manage")) expect(capabilityForScreen(path)).toBeNull();
    }
  });

  it("gates a path however it is spelled", () => {
    expect(capabilityForScreen("/manage/Logs")).toBe("logs.read");
    expect(capabilityForScreen("/manage//logs")).toBe("logs.read");
    expect(capabilityForScreen("/MANAGE/AGENTS/WIZARD")).toBe("setup.guided");
  });
});
