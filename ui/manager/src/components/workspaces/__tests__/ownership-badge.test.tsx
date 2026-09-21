import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { OwnershipBadge } from "@/components/workspaces/ownership-badge";
import { useSpaces } from "@/hooks/use-spaces";
import * as workspacesApi from "@/lib/api/workspaces";
import type { WorkspaceInfo } from "@/lib/api/workspaces";

const ALICE = "alice@example.com";

/**
 * Renders once the workspace query has actually settled.
 *
 * <h3>Why a probe and not a `waitFor`</h3> Both components under test render
 * *nothing* in the cases below, so there is no positive observable to settle on
 * — and `waitFor(() => expect(queryByTestId(x)).not.toBeInTheDocument())`
 * resolves on its FIRST poll, which happens before the query has resolved. Every
 * such assertion therefore passed against a component that had not rendered yet,
 * and would have passed just as happily if the thing appeared a tick later.
 *
 * Awaiting this probe makes "not rendered" mean "not rendered once the answer
 * was in", which is the actual claim.
 */
function SpacesReady() {
  const { isLoading } = useSpaces();
  return isLoading ? null : <div data-testid="spaces-ready" />;
}

function info(overrides: Partial<WorkspaceInfo> = {}): WorkspaceInfo {
  return {
    enabled: true,
    principal: ALICE,
    defaultSpace: `user:${ALICE}`,
    spaces: [{ id: `user:${ALICE}`, kind: "personal", label: ALICE }],
    seesEverything: false,
    ...overrides,
  };
}

/**
 * The badge answers one question — "why is this in a list that is otherwise
 * mine?" — and it answers it by staying silent most of the time.
 *
 * Both directions matter. A badge on every row is wallpaper; a missing badge on
 * someone else's published agent is a reader concluding it is theirs. So each
 * case below asserts what renders *and* what does not.
 */
describe("OwnershipBadge", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  async function render(props: Parameters<typeof OwnershipBadge>[0], ws = info()) {
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(ws);
    const result = renderWithProviders(
      <>
        <OwnershipBadge {...props} />
        <SpacesReady />
      </>
    );
    // Settling the FETCH CALL is not settling the query: every "renders
    // nothing" assertion below would otherwise read the pre-answer render,
    // where nothing is drawn regardless of whether the gate works.
    await screen.findByTestId("spaces-ready");
    return result;
  }

  it("draws nothing at all while enforcement is off", async () => {
    // The load-bearing case. Ownership IS recorded with the feature disabled —
    // deliberately, so attribution accumulates before an operator flips it — so
    // a badge driven by the fields alone would label a distinction the
    // deployment does not have, on the default configuration.
    await render(
      { ownerId: "bob@example.com", spaceId: "team:engineering", visibility: "published" },
      info({ enabled: false, seesEverything: true })
    );

    expect(screen.queryByTestId("ownership-badge-published")).not.toBeInTheDocument();
    expect(screen.queryByTestId("ownership-badge-shared")).not.toBeInTheDocument();
  });

  it("marks a published resource, whoever owns it", async () => {
    await render({ ownerId: ALICE, spaceId: `user:${ALICE}`, visibility: "published" });

    expect(await screen.findByTestId("ownership-badge-published")).toBeInTheDocument();
  });

  it("stays silent on your own resource in your own workspace", async () => {
    await render({ ownerId: ALICE, spaceId: `user:${ALICE}`, visibility: "space" });

    expect(screen.queryByTestId("ownership-badge-shared")).not.toBeInTheDocument();
    expect(screen.queryByTestId("ownership-badge-published")).not.toBeInTheDocument();
  });

  it("marks a resource that reached you through someone else", async () => {
    await render({ ownerId: "bob@example.com", spaceId: "team:engineering", visibility: "space" });

    const badge = await screen.findByTestId("ownership-badge-shared");
    expect(badge).toHaveAttribute("title", expect.stringContaining("bob@example.com"));
    expect(badge).toHaveAttribute("title", expect.stringContaining("engineering"));
  });

  it("compares against the principal the backend reports, not a display name", async () => {
    // `ownerId` is stamped from SecurityIdentity's principal name. Comparing
    // against the token's display name instead would badge every one of a
    // user's own resources as "shared" on any realm where the two differ.
    await render({ ownerId: ALICE, spaceId: `user:${ALICE}`, visibility: "space" }, info({ principal: "Alice Smith" }));

    expect(await screen.findByTestId("ownership-badge-shared")).toBeInTheDocument();
  });

  it("draws nothing for data that predates ownership", async () => {
    // An unowned resource is not the same as one owned by somebody else, and
    // saying "unowned" would name a status the UI has no concept of.
    await render({});

    expect(screen.queryByTestId("ownership-badge-shared")).not.toBeInTheDocument();
    expect(screen.queryByTestId("ownership-badge-published")).not.toBeInTheDocument();
  });

  it("names the owner even when the space is unrecorded", async () => {
    await render({ ownerId: "bob@example.com", visibility: "space" });

    const badge = await screen.findByTestId("ownership-badge-shared");
    expect(badge).toHaveAttribute("title", expect.stringContaining("bob@example.com"));
  });
});
