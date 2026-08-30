import { describe, it, expect, vi, beforeEach, afterEach, type MockInstance } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ShareDialog } from "@/components/workspaces/share-dialog";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";

const RESOURCE_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
const SHARES = `*/descriptorstore/descriptors/${RESOURCE_ID}/shares`;

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

describe("ShareDialog", () => {
  const props = { open: true, onClose: vi.fn(), resourceId: RESOURCE_ID, resourceName: "Test Agent" };

  /**
   * Refusals reach the user as a toast, and no Toaster is mounted in this
   * harness — so the call is the observable. Asserting on it also means a
   * button that silently did nothing fails these tests, which was Fable's point
   * about the original version.
   */
  let errorToast: MockInstance<typeof toast.error>;

  beforeEach(() => {
    vi.clearAllMocks();
    errorToast = vi.spyOn(toast, "error").mockImplementation(() => "id");
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("tells a non-owner they cannot change access, and offers no controls that would fail", async () => {
    // Showing the controls and letting the server refuse would teach the user
    // that sharing is broken rather than that it is not theirs to do.
    server.use(http.get(SHARES, () => HttpResponse.json(shareInfo({ callerLevel: "VIEW" }))));

    renderWithProviders(<ShareDialog {...props} />);

    await waitFor(() => expect(screen.getByTestId("share-owner-line")).toBeInTheDocument());
    expect(screen.getByText(/only the owner can change who has access/i)).toBeInTheDocument();
    expect(screen.queryByTestId("share-submit")).not.toBeInTheDocument();
    expect(screen.queryByTestId("visibility-published")).not.toBeInTheDocument();
  });

  it("asks twice before handing someone ownership", async () => {
    // Every other level is reversible by the owner alone. OWN is not: the
    // recipient can delete the resource and re-share it, and taking it back
    // needs their cooperation. So the first click warns instead of acting.
    const shared = vi.fn();
    server.use(
      http.get(SHARES, () => HttpResponse.json(shareInfo())),
      http.post(SHARES, () => {
        shared();
        return HttpResponse.json({ updated: [], skipped: [] });
      })
    );

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByTestId("share-subject-input")).toBeInTheDocument());

    await userEvent.type(screen.getByTestId("share-subject-input"), "bob");
    await userEvent.selectOptions(screen.getByTestId("share-level-select"), "OWN");
    await userEvent.click(screen.getByTestId("share-submit"));

    expect(screen.getByTestId("share-owner-warning")).toBeInTheDocument();
    expect(shared).not.toHaveBeenCalled();

    await userEvent.click(screen.getByTestId("share-submit"));
    await waitFor(() => expect(shared).toHaveBeenCalledTimes(1));
  });

  it("cancels a pending ownership transfer when the level is changed", async () => {
    server.use(http.get(SHARES, () => HttpResponse.json(shareInfo())));

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByTestId("share-subject-input")).toBeInTheDocument());

    await userEvent.type(screen.getByTestId("share-subject-input"), "bob");
    await userEvent.selectOptions(screen.getByTestId("share-level-select"), "OWN");
    await userEvent.click(screen.getByTestId("share-submit"));
    expect(screen.getByTestId("share-owner-warning")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByTestId("share-level-select"), "VIEW");
    expect(screen.queryByTestId("share-owner-warning")).not.toBeInTheDocument();
  });

  it("shares at the chosen level without a second click for every other level", async () => {
    let sentLevel: string | null = null;
    server.use(
      http.get(SHARES, () => HttpResponse.json(shareInfo())),
      http.post(SHARES, ({ request }) => {
        sentLevel = new URL(request.url).searchParams.get("level");
        return HttpResponse.json({ updated: [{ id: RESOURCE_ID, name: "Test Agent" }], skipped: [] });
      })
    );

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByTestId("share-subject-input")).toBeInTheDocument());

    await userEvent.type(screen.getByTestId("share-subject-input"), "bob@example.com");
    await userEvent.click(screen.getByTestId("share-submit"));

    await waitFor(() => expect(sentLevel).toBe("USE"));
  });

  it("names what it declined to touch, rather than reporting a clean success", async () => {
    // A half-shared agent that nobody knows is half-shared is the failure mode
    // this summary exists to prevent.
    server.use(
      http.get(SHARES, () => HttpResponse.json(shareInfo())),
      http.post(SHARES, () =>
        HttpResponse.json({
          updated: [{ id: RESOURCE_ID, name: "Test Agent" }],
          skipped: [{ id: "bbbbbbbbbbbbbbbbbbbbbbbb", name: "Bob's rule set" }],
        })
      )
    );

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByTestId("share-subject-input")).toBeInTheDocument());

    await userEvent.type(screen.getByTestId("share-subject-input"), "bob");
    await userEvent.click(screen.getByTestId("share-submit"));

    await waitFor(() => expect(screen.getByTestId("share-cascade-summary")).toBeInTheDocument());
    expect(screen.getByText("Bob's rule set")).toBeInTheDocument();
    expect(screen.getByText(/left unchanged/i)).toBeInTheDocument();
  });

  it("counts one resource in the singular and several in the plural", async () => {
    server.use(
      http.get(SHARES, () => HttpResponse.json(shareInfo())),
      http.post(SHARES, () =>
        HttpResponse.json({
          updated: [
            { id: "1111111111111111111111aa", name: "a" },
            { id: "2222222222222222222222bb", name: "b" },
          ],
          skipped: [],
        })
      )
    );

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByTestId("share-subject-input")).toBeInTheDocument());

    await userEvent.type(screen.getByTestId("share-subject-input"), "bob");
    await userEvent.click(screen.getByTestId("share-submit"));

    // "Applied to 2 resource" is what a missing plural form produces, and it is
    // the sort of thing that ships because nobody shares exactly two things
    // while testing.
    await waitFor(() => expect(screen.getByText("Applied to 2 resources")).toBeInTheDocument());
  });

  it("rejects a subject with an unrecognised prefix instead of sharing with nobody", async () => {
    // "group:engineering" is a plausible typo for "team:engineering", and a
    // share with a subject nobody holds looks exactly like a successful one.
    const shared = vi.fn();
    server.use(
      http.get(SHARES, () => HttpResponse.json(shareInfo())),
      http.post(SHARES, () => {
        shared();
        return HttpResponse.json({ updated: [], skipped: [] });
      })
    );

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByTestId("share-subject-input")).toBeInTheDocument());

    await userEvent.type(screen.getByTestId("share-subject-input"), "group:engineering");
    await userEvent.click(screen.getByTestId("share-submit"));

    // Wait for the refusal to actually be issued before asserting nothing was
    // sent. `waitFor(() => expect(fn).not.toHaveBeenCalled())` resolves on its
    // first poll, so on its own it would pass against a POST made a tick later
    // — it asserts "not yet", which is not the claim. It would also pass if the
    // button did nothing whatsoever.
    await waitFor(() => expect(errorToast).toHaveBeenCalledWith(expect.stringMatching(/user:|team:/)));
    expect(shared).not.toHaveBeenCalled();
    expect(screen.queryByTestId("share-cascade-summary")).not.toBeInTheDocument();
  });

  it("does not arm an ownership transfer for an empty subject", async () => {
    // Arming before validating showed a destructive "Confirm transfer" button,
    // and a warning about handing ownership away, for a transfer to nobody.
    server.use(http.get(SHARES, () => HttpResponse.json(shareInfo())));

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByTestId("share-level-select")).toBeInTheDocument());

    await userEvent.selectOptions(screen.getByTestId("share-level-select"), "OWN");
    await userEvent.click(screen.getByTestId("share-submit"));

    await waitFor(() => expect(errorToast).toHaveBeenCalledWith(expect.stringMatching(/person or team/i)));
    expect(screen.queryByTestId("share-owner-warning")).not.toBeInTheDocument();
  });

  it("withdraws a confirmed transfer when the subject is retyped", async () => {
    // The confirmation has to be bound to the subject it warned about. With a
    // plain flag, arming for "bob" and then retyping "carol" handed CAROL
    // ownership under a warning the user had read about bob.
    let sentSubject: string | null = null;
    server.use(
      http.get(SHARES, () => HttpResponse.json(shareInfo())),
      http.post(SHARES, ({ request }) => {
        sentSubject = new URL(request.url).searchParams.get("subject");
        return HttpResponse.json({ updated: [], skipped: [] });
      })
    );

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByTestId("share-subject-input")).toBeInTheDocument());

    const input = screen.getByTestId("share-subject-input");
    await userEvent.type(input, "bob");
    await userEvent.selectOptions(screen.getByTestId("share-level-select"), "OWN");
    await userEvent.click(screen.getByTestId("share-submit"));
    expect(screen.getByTestId("share-owner-warning")).toBeInTheDocument();

    await userEvent.clear(input);
    await userEvent.type(input, "carol");

    // The warning is gone, and the next click re-arms rather than transferring.
    expect(screen.queryByTestId("share-owner-warning")).not.toBeInTheDocument();
    await userEvent.click(screen.getByTestId("share-submit"));
    expect(screen.getByTestId("share-owner-warning")).toBeInTheDocument();
    expect(sentSubject).toBeNull();
  });

  it("does not let two quick Enters skip the ownership confirmation", async () => {
    // Enter arms a transfer but must never complete one: two presses in the
    // input would otherwise sail straight through the confirmation the second
    // press exists to read.
    const shared = vi.fn();
    server.use(
      http.get(SHARES, () => HttpResponse.json(shareInfo())),
      http.post(SHARES, () => {
        shared();
        return HttpResponse.json({ updated: [], skipped: [] });
      })
    );

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByTestId("share-subject-input")).toBeInTheDocument());

    await userEvent.type(screen.getByTestId("share-subject-input"), "bob");
    await userEvent.selectOptions(screen.getByTestId("share-level-select"), "OWN");
    await userEvent.type(screen.getByTestId("share-subject-input"), "{Enter}{Enter}");

    expect(screen.getByTestId("share-owner-warning")).toBeInTheDocument();
    expect(shared).not.toHaveBeenCalled();
  });

  it("removes a grant and says so in the past tense", async () => {
    // "Applied to 1 resource" after a revoke reads as though access had been
    // granted, which is the opposite of what happened.
    let revokedSubject: string | null = null;
    server.use(
      http.get(SHARES, () =>
        HttpResponse.json(
          shareInfo({
            grants: [{ subject: "user:bob@example.com", level: "VIEW", grantedBy: "alice", grantedOn: 0 }],
          })
        )
      ),
      http.delete(SHARES, ({ request }) => {
        revokedSubject = new URL(request.url).searchParams.get("subject");
        return HttpResponse.json({ updated: [{ id: RESOURCE_ID, name: "Test Agent" }], skipped: [] });
      })
    );

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByText("bob@example.com")).toBeInTheDocument());

    await userEvent.click(screen.getByRole("button", { name: /stop sharing with/i }));

    await waitFor(() => expect(revokedSubject).toBe("user:bob@example.com"));
    expect(await screen.findByText(/removed from/i)).toBeInTheDocument();
  });

  it("changes visibility and reports what the cascade reached", async () => {
    let sentVisibility: string | null = null;
    server.use(
      http.get(SHARES, () => HttpResponse.json(shareInfo())),
      http.put(`${SHARES}/visibility`, ({ request }) => {
        sentVisibility = new URL(request.url).searchParams.get("visibility");
        return HttpResponse.json({
          updated: [
            { id: RESOURCE_ID, name: "Test Agent" },
            { id: "2222222222222222222222bb", name: "Support Rules" },
          ],
          skipped: [],
        });
      })
    );

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByTestId("visibility-published")).toBeInTheDocument());

    await userEvent.click(screen.getByTestId("visibility-published"));

    await waitFor(() => expect(sentVisibility).toBe("published"));
    expect(await screen.findByText("Applied to 2 resources")).toBeInTheDocument();
  });

  it("names the resources it could not touch, and counts the ones it did not name", async () => {
    // A list of five under a count of twelve reads as the whole list. Silent
    // truncation is the one thing this summary exists not to do.
    server.use(
      http.get(SHARES, () => HttpResponse.json(shareInfo())),
      http.post(SHARES, () =>
        HttpResponse.json({
          updated: [{ id: RESOURCE_ID, name: "Test Agent" }],
          skipped: Array.from({ length: 8 }, (_, i) => ({
            id: `${i}`.repeat(24).slice(0, 24),
            name: `Colleague resource ${i}`,
          })),
        })
      )
    );

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByTestId("share-subject-input")).toBeInTheDocument());

    await userEvent.type(screen.getByTestId("share-subject-input"), "bob");
    await userEvent.click(screen.getByTestId("share-submit"));

    await waitFor(() => expect(screen.getByTestId("share-cascade-summary")).toBeInTheDocument());
    expect(screen.getByText("Colleague resource 0")).toBeInTheDocument();
    expect(screen.queryByText("Colleague resource 7")).not.toBeInTheDocument();
    expect(screen.getByText("and 3 more")).toBeInTheDocument();
  });

  it("explains a 403 as the USE/VIEW split rather than as a failure", async () => {
    // Someone shared the agent so this person could talk to it, which
    // deliberately does not let them read how it was built. The server's own
    // wording is about access levels and reads as something being broken.
    server.use(
      http.get(SHARES, () => HttpResponse.json({ error: "forbidden" }, { status: 403 }))
    );

    renderWithProviders(<ShareDialog {...props} />);

    expect(await screen.findByText(/you can chat with this/i)).toBeInTheDocument();
    // And the stale controls must not sit under the message.
    expect(screen.queryByTestId("share-submit")).not.toBeInTheDocument();
    expect(screen.queryByTestId("visibility-published")).not.toBeInTheDocument();
  });

  it("surfaces a server error on share without claiming success", async () => {
    server.use(
      http.get(SHARES, () => HttpResponse.json(shareInfo())),
      http.post(SHARES, () => HttpResponse.json({ error: "boom" }, { status: 500 }))
    );

    renderWithProviders(<ShareDialog {...props} />);
    await waitFor(() => expect(screen.getByTestId("share-subject-input")).toBeInTheDocument());

    await userEvent.type(screen.getByTestId("share-subject-input"), "bob");
    await userEvent.click(screen.getByTestId("share-submit"));

    // Wait for the failure to be REPORTED, then assert the absence. Wrapping a
    // negative in `waitFor` asserts "not yet" — it resolves on the first poll,
    // and would pass just as happily against a summary rendered one tick later.
    // (Exactly the trap fixed twice already in this file; it came back with the
    // test that was added last.)
    await waitFor(() => expect(errorToast).toHaveBeenCalled());
    expect(screen.queryByTestId("share-cascade-summary")).not.toBeInTheDocument();
    expect(screen.getByTestId("share-subject-input")).toHaveValue("bob");
  });
});
