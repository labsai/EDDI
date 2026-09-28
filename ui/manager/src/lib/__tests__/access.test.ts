import { describe, expect, it } from "vitest";
import { accessFor, accessForDetail } from "../access";

/**
 * Which controls are worth offering for one listed resource.
 *
 * <h3>Both directions are failures</h3> Too permissive and the user clicks
 * Delete on a colleague's agent and gets a 403, which reads as the product
 * being broken. Too restrictive and an existing deployment — where the backend
 * sends no level at all — loses every button it had yesterday. So absence is
 * pinned as carefully as each level.
 */
describe("accessFor", () => {
  it("treats an absent level as unrestricted, not as no access", () => {
    // The field is omitted when enforcement is off and on every backend that
    // predates it. Reading that as "no access" would empty the UI on upgrade.
    for (const absent of [undefined, null, ""]) {
      const access = accessFor(absent);
      expect(access).toMatchObject({ canUse: true, canView: true, canEdit: true, canOwn: true });
      expect(access.known).toBe(false);
    }
  });

  it("USE permits conversing and nothing else", () => {
    // The split that makes the whole model worth its complexity: talking to an
    // agent is not reading its prompts, tools and vault references.
    expect(accessFor("USE")).toEqual({
      canUse: true,
      canView: false,
      canEdit: false,
      canOwn: false,
      known: true,
    });
  });

  it("VIEW adds reading the configuration, not changing it", () => {
    expect(accessFor("VIEW")).toEqual({
      canUse: true,
      canView: true,
      canEdit: false,
      canOwn: false,
      known: true,
    });
  });

  it("EDIT stops short of deleting or re-sharing", () => {
    // Re-sharing changes who can reach the resource, which is an owner's
    // decision — the backend's AccessLevel says so explicitly.
    expect(accessFor("EDIT")).toEqual({
      canUse: true,
      canView: true,
      canEdit: true,
      canOwn: false,
      known: true,
    });
  });

  it("OWN permits everything", () => {
    expect(accessFor("OWN")).toEqual({
      canUse: true,
      canView: true,
      canEdit: true,
      canOwn: true,
      known: true,
    });
  });

  it("falls back to unrestricted for a level it does not recognise", () => {
    // A backend that grows a fifth level must not blank out an older Manager,
    // and the server refuses anything the caller may not do regardless.
    const access = accessFor("SUPERUSER");
    expect(access.canOwn).toBe(true);
    expect(access.known).toBe(false);
  });

  it("reports whether the answer came from the backend at all", () => {
    // `known` is what separates "not restricted" from "restricted to OWN" —
    // both permit everything, and only one of them is a real grant.
    expect(accessFor("OWN").known).toBe(true);
    expect(accessFor(undefined).known).toBe(false);
  });
});

describe("accessForDetail", () => {
  const uri = (id: string, level?: string) => ({
    resource: `eddi://ai.labs.agent/agentstore/agents/${id}?version=1`,
    ...(level ? { callerLevel: level } : {}),
  });

  it("offers nothing while the descriptors are still loading", () => {
    // Unrestricted here would flash Delete at an EDIT grantee until the level
    // arrived — long enough to click it.
    expect(accessForDetail(undefined, "a1")).toMatchObject({
      canUse: false,
      canView: false,
      canEdit: false,
      canOwn: false,
    });
  });

  it("reads the level of this resource, not of one whose id contains it", () => {
    // `filter=` is a text match: `a1` also returns `a1b2`, which the caller may own.
    const access = accessForDetail([uri("a1b2", "OWN"), uri("a1", "EDIT")], "a1");
    expect(access.canEdit).toBe(true);
    expect(access.canOwn).toBe(false);
  });

  it("treats a loaded descriptor without a level as unrestricted", () => {
    expect(accessForDetail([uri("a1")], "a1").canOwn).toBe(true);
  });
});
