import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ShareDialog } from "@/components/workspaces/share-dialog";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";

const RESOURCE_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
const SHARES = `*/descriptorstore/descriptors/${RESOURCE_ID}/shares`;

/** Flipped per test; read lazily by the mock below. */
const authState = { roles: ["eddi-editor"] as string[] };

vi.mock("@/hooks/use-auth", () => ({
  useAuth: () => ({
    authenticated: true,
    loading: false,
    user: null,
    roles: authState.roles,
    // "keycloak", not "none": `useHasRole` short-circuits to true when auth is
    // off, so a harness left on "none" would report every caller as an admin
    // and this whole file would pass without testing the gate.
    method: "keycloak",
    login: () => {},
    logout: () => {},
  }),
  useHasRole: (role: string) => authState.roles.includes(role),
}));

function shareInfo(overrides: Record<string, unknown> = {}) {
  return {
    resourceId: RESOURCE_ID,
    ownerId: "alice",
    spaceId: "user:alice",
    visibility: "space",
    grants: [],
    callerLevel: "OWN",
    ...overrides,
  };
}

/**
 * Reassigning a resource's owner.
 *
 * Not the same operation as granting someone `OWN`, and the difference is why
 * this control has to exist separately:
 *
 * - Granting OWN *adds* an owner, and requires being the owner yourself.
 * - Transferring *replaces* the owner, and is `@RolesAllowed("eddi-admin")`.
 *
 * So when a resource's owner leaves the organisation, nobody remaining can
 * grant themselves access through the sharing section — there is no owner left
 * to do it. EDDI documents that as the case the endpoint is for. The Manager
 * implemented the call in `sharing.ts` and never rendered a way to reach it, so
 * the function had zero call sites outside its own module.
 */
describe("ShareDialog — transfer ownership", () => {
  const props = { open: true, onClose: vi.fn(), resourceId: RESOURCE_ID, resourceName: "Test Agent" };

  beforeEach(() => {
    vi.clearAllMocks();
    authState.roles = ["eddi-admin"];
    vi.spyOn(toast, "error").mockImplementation(() => "id");
    vi.spyOn(toast, "success").mockImplementation(() => "id");
    server.use(http.get(SHARES, () => HttpResponse.json(shareInfo())));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("is offered to an administrator", async () => {
    renderWithProviders(<ShareDialog {...props} />);

    await waitFor(() => {
      expect(screen.getByTestId("transfer-ownership")).toBeInTheDocument();
    });
  });

  it("is withheld from an editor who owns the resource", async () => {
    // Owning it is not enough — the endpoint is admin-only, so offering the
    // control to an owner would teach them the product is broken.
    authState.roles = ["eddi-editor"];

    renderWithProviders(<ShareDialog {...props} />);

    await waitFor(() => {
      expect(screen.getByTestId("share-owner-line")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("transfer-ownership")).not.toBeInTheDocument();
  });

  it("is offered to an administrator who does NOT own the resource", async () => {
    // The case the endpoint exists for: the owner has left, so nobody holds
    // OWN and the sharing section above is hidden. Without this, the resource
    // is unreachable.
    server.use(http.get(SHARES, () => HttpResponse.json(shareInfo({ callerLevel: "VIEW" }))));

    renderWithProviders(<ShareDialog {...props} />);

    await waitFor(() => {
      expect(screen.getByTestId("transfer-ownership")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("share-submit")).not.toBeInTheDocument();
  });

  it("asks before it transfers, and names who loses control", async () => {
    renderWithProviders(<ShareDialog {...props} />);
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByTestId("transfer-subject-input")).toBeInTheDocument());
    await user.type(screen.getByTestId("transfer-subject-input"), "user:bob");

    let called = false;
    server.use(
      http.put(`${SHARES}/owner`, () => {
        called = true;
        return HttpResponse.json({ updated: [], skipped: [] });
      }),
    );

    await user.click(screen.getByTestId("transfer-submit"));

    // First click arms rather than acts.
    expect(called).toBe(false);
    expect(screen.getByTestId("transfer-warning")).toHaveTextContent("alice");
  });

  it("transfers on the second click", async () => {
    renderWithProviders(<ShareDialog {...props} />);
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByTestId("transfer-subject-input")).toBeInTheDocument());
    await user.type(screen.getByTestId("transfer-subject-input"), "user:bob");

    let ownerId: string | null = null;
    server.use(
      http.put(`${SHARES}/owner`, ({ request }) => {
        ownerId = new URL(request.url).searchParams.get("ownerId");
        return HttpResponse.json({ updated: [{ id: RESOURCE_ID, name: "Test Agent" }], skipped: [] });
      }),
    );

    await user.click(screen.getByTestId("transfer-submit"));
    await user.click(screen.getByTestId("transfer-submit"));

    await waitFor(() => expect(ownerId).toBe("user:bob"));
  });

  it("withdraws the confirmation when the name is changed", async () => {
    // Bound to the subject it was shown for. With a plain flag, arming for
    // "bob" and then typing "carol" would hand carol the resource under a
    // warning that named bob.
    renderWithProviders(<ShareDialog {...props} />);
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByTestId("transfer-subject-input")).toBeInTheDocument());
    const input = screen.getByTestId("transfer-subject-input");
    await user.type(input, "user:bob");
    await user.click(screen.getByTestId("transfer-submit"));
    expect(screen.getByTestId("transfer-warning")).toBeInTheDocument();

    await user.type(input, "x");

    expect(screen.queryByTestId("transfer-warning")).not.toBeInTheDocument();
  });

  it("refuses an empty subject without contacting the server", async () => {
    renderWithProviders(<ShareDialog {...props} />);
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByTestId("transfer-submit")).toBeInTheDocument());

    expect(screen.getByTestId("transfer-submit")).toBeDisabled();
    await user.click(screen.getByTestId("transfer-submit"));
    expect(screen.queryByTestId("transfer-warning")).not.toBeInTheDocument();
  });
});

/**
 * The dialog is reachable from the workflow and extension listings now, not
 * just the agents page. Sharing is by descriptor id and works on any resource,
 * so refreshing only the agent queries left the ownership badge and
 * `callerLevel` on those pages showing the state from before the share.
 */
describe("ShareDialog — cache invalidation follows where the dialog is used", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authState.roles = ["eddi-admin"];
    vi.spyOn(toast, "success").mockImplementation(() => "id");
    server.use(http.get(SHARES, () => HttpResponse.json(shareInfo())));
  });

  afterEach(() => vi.restoreAllMocks());

  it("refreshes the workflow and resource listings, not only the agent ones", async () => {
    const { queryClient } = renderWithProviders(
      <ShareDialog open onClose={vi.fn()} resourceId={RESOURCE_ID} />,
    );
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByTestId("share-subject-input")).toBeInTheDocument());
    await user.type(screen.getByTestId("share-subject-input"), "user:bob");
    server.use(http.post(SHARES, () => HttpResponse.json({ updated: [], skipped: [] })));
    await user.click(screen.getByTestId("share-submit"));

    await waitFor(() => {
      const keys = invalidate.mock.calls
        .map((c) => JSON.stringify((c[0] as { queryKey?: unknown })?.queryKey))
        .join(" ");
      expect(keys).toContain('["agents"');
      expect(keys).toContain('["workflows"]');
      expect(keys).toContain('["resources"]');
    });
  });
});
