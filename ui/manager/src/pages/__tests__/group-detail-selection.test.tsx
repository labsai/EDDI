import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { renderPage, createTestQueryClient } from "@/test/test-utils";
import { GroupDetailPage } from "@/pages/group-detail";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

/**
 * Three defects on the Manager's group page, all of them things the Workforce
 * board — the other surface for the same data — already got right.
 */

// The transcript auto-scrolls as a stream arrives; jsdom implements neither.
window.HTMLElement.prototype.scrollIntoView = vi.fn();
window.HTMLElement.prototype.scrollTo = vi.fn();

function renderGroupDetail(search = "?version=1", id = "grp1") {
  return renderPage(
    `/manage/groups/${id}${search}`,
    <GroupDetailPage />,
    "/manage/groups/:id",
  );
}

/** The conversation ids the MSW group fixture serves for `grp1`. */
async function conversationIds(): Promise<string[]> {
  const items = await waitFor(() => {
    const found = document.querySelectorAll('[data-testid^="discussion-item-"]');
    expect(found.length).toBeGreaterThan(0);
    return found;
  });
  return [...items].map((el) => el.getAttribute("data-testid")!.replace("discussion-item-", ""));
}

describe("GroupDetailPage — selecting a discussion", () => {
  it("opens the discussion named in the URL rather than the first one", async () => {
    const ids = await (async () => {
      renderGroupDetail();
      return conversationIds();
    })();
    // Needs at least two to tell "the one asked for" from "the first one".
    expect(ids.length).toBeGreaterThan(1);
    const target = ids[1]!;

    document.body.innerHTML = "";
    renderGroupDetail(`?version=1&conversation=${target}`);

    await waitFor(() => {
      expect(screen.getByTestId(`discussion-item-${target}`)).toHaveAttribute(
        "aria-current",
        "true",
      );
    });
  });

  it("writes the selection to the URL, so a reload and a shared link land back on it", async () => {
    // `renderPage` mounts a MemoryRouter, so the URL to read is the router's,
    // not `window.location` — a probe inside the tree is the only honest read.
    let search = "";
    function LocationProbe() {
      search = useLocation().search;
      return null;
    }
    render(
      <MemoryRouter initialEntries={["/manage/groups/grp1?version=1"]}>
        <QueryClientProvider client={createTestQueryClient()}>
          <Routes>
            <Route
              path="/manage/groups/:id"
              element={
                <>
                  <GroupDetailPage />
                  <LocationProbe />
                </>
              }
            />
          </Routes>
        </QueryClientProvider>
      </MemoryRouter>,
    );

    const ids = await conversationIds();
    const target = ids[1] ?? ids[0]!;
    await userEvent.click(screen.getByTestId(`discussion-item-${target}`));

    await waitFor(() => {
      expect(new URLSearchParams(search).get("conversation")).toBe(target);
    });
  });
});

describe("GroupDetailPage — deleting a discussion", () => {
  it("asks before deleting, and deletes nothing until confirmed", async () => {
    const deleted: string[] = [];
    server.use(
      http.delete("*/groups/:groupId/conversations/:conversationId", ({ params }) => {
        deleted.push(String(params.conversationId));
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderGroupDetail();
    const ids = await conversationIds();
    const target = ids[0]!;

    await userEvent.click(screen.getByTestId(`delete-discussion-${target}`));

    // The confirmation is the whole point: the trash icon sits beside Cancel,
    // which has always confirmed, and delete is the permanent one of the pair.
    expect(
      await screen.findByText(/removed permanently|cannot be undone/i),
    ).toBeInTheDocument();
    expect(deleted).toEqual([]);
  });

  it("deletes once confirmed", async () => {
    const deleted: string[] = [];
    server.use(
      http.delete("*/groups/:groupId/conversations/:conversationId", ({ params }) => {
        deleted.push(String(params.conversationId));
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderGroupDetail();
    const ids = await conversationIds();
    const target = ids[0]!;

    await userEvent.click(screen.getByTestId(`delete-discussion-${target}`));
    await screen.findByText(/removed permanently|cannot be undone/i);
    await userEvent.click(screen.getByRole("button", { name: /^delete$/i }));

    await waitFor(() => expect(deleted).toEqual([target]));
  });

  it("keeps the delete and cancel controls reachable without a mouse", async () => {
    renderGroupDetail();
    const ids = await conversationIds();
    // `opacity-0` alone left these invisible to keyboard and touch while still
    // being clickable — a control you can hit but cannot see.
    const del = screen.getByTestId(`delete-discussion-${ids[0]!}`);
    expect(del.className).toMatch(/focus-visible:opacity-100/);
    expect(del.className).toMatch(/group-focus-within\/item:opacity-100/);
  });
});

describe("GroupDetailPage — configuration on a narrow viewport", () => {
  it("offers the config panel where the sidebar is hidden", async () => {
    renderGroupDetail();
    // Below xl the sidebar and its re-open button are both `hidden`, so without
    // this there was no route to the group's configuration from this page.
    const trigger = await screen.findByTestId("open-config-sheet");
    await userEvent.click(trigger);
    expect(await screen.findByTestId("config-sheet")).toBeInTheDocument();
  });

});

/**
 * "New Discussion" was a no-op on a group that had ever held one.
 *
 * The handler cleared the selection; the auto-select effect, which lists
 * `selectedConvId` in its dependencies, immediately put the newest conversation
 * back. Worse than cosmetic: attachments are accepted only on a NEW discussion
 * (the backend rejects a continuation carrying any), so the upload control is
 * not rendered while a conversation is selected — a group with any history
 * could never accept a file again, with no error to explain it.
 */
describe("GroupDetailPage — New Discussion", () => {
  it("auto-selects the newest discussion on a plain load", async () => {
    renderGroupDetail();
    const ids = await conversationIds();

    await waitFor(() => {
      expect(screen.getByTestId(`discussion-item-${ids[0]}`)).toHaveAttribute("aria-current", "true");
    });
  });

  it("clears the selection and keeps it cleared", async () => {
    renderGroupDetail();
    const ids = await conversationIds();
    await waitFor(() => {
      expect(screen.getByTestId(`discussion-item-${ids[0]}`)).toHaveAttribute("aria-current", "true");
    });

    await userEvent.click(screen.getByTestId("new-discussion-btn"));

    await waitFor(() => {
      for (const id of ids) {
        expect(screen.getByTestId(`discussion-item-${id}`)).not.toHaveAttribute("aria-current", "true");
      }
    });
    // And it stays cleared — the effect used to re-run on the very change the
    // handler made and undo it within a tick.
    await new Promise((resolve) => setTimeout(resolve, 50));
    for (const id of ids) {
      expect(screen.getByTestId(`discussion-item-${id}`)).not.toHaveAttribute("aria-current", "true");
    }
  });

  it("restores the attachment control, which is the property that was broken", async () => {
    renderGroupDetail();
    const ids = await conversationIds();
    await waitFor(() => {
      expect(screen.getByTestId(`discussion-item-${ids[0]}`)).toHaveAttribute("aria-current", "true");
    });
    // A continuation cannot carry files, so the affordance is absent here.
    // Wait for the composer to settle into continue mode first: the detail
    // query resolves a tick after the selection, and asserting before it lands
    // would pass against the bug.
    await waitFor(() => {
      expect(screen.getByPlaceholderText(/Continue this discussion/i)).toBeInTheDocument();
    });
    expect(screen.queryByTestId("discussion-attach-btn")).not.toBeInTheDocument();

    await userEvent.click(screen.getByTestId("new-discussion-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("discussion-attach-btn")).toBeInTheDocument();
    });
    expect(screen.getByTestId("discussion-file-input")).toBeInTheDocument();
  });

  it("drops the conversation from the URL, so a reload does not restore it", async () => {
    let search = "";
    function LocationProbe() {
      search = useLocation().search;
      return null;
    }
    render(
      <MemoryRouter initialEntries={["/manage/groups/grp1?version=1"]}>
        <QueryClientProvider client={createTestQueryClient()}>
          <Routes>
            <Route
              path="/manage/groups/:id"
              element={
                <>
                  <GroupDetailPage />
                  <LocationProbe />
                </>
              }
            />
          </Routes>
        </QueryClientProvider>
      </MemoryRouter>,
    );
    await conversationIds();
    await waitFor(() => {
      expect(new URLSearchParams(search).get("conversation")).not.toBeNull();
    });

    await userEvent.click(screen.getByTestId("new-discussion-btn"));

    await waitFor(() => {
      expect(new URLSearchParams(search).get("conversation")).toBeNull();
    });
  });

  it("a deliberate pick after New Discussion selects again", async () => {
    renderGroupDetail();
    const ids = await conversationIds();
    await userEvent.click(screen.getByTestId("new-discussion-btn"));
    await waitFor(() => {
      expect(screen.getByTestId("discussion-attach-btn")).toBeInTheDocument();
    });

    await userEvent.click(screen.getByTestId(`discussion-item-${ids[1] ?? ids[0]}`));

    await waitFor(() => {
      expect(screen.getByTestId(`discussion-item-${ids[1] ?? ids[0]}`)).toHaveAttribute(
        "aria-current",
        "true",
      );
    });
  });
});

/**
 * Rejecting a paused discussion has to leave the page on that discussion.
 *
 * The stream hook reports the state the backend puts on `group_complete`, and a
 * HITL rejection ends a run as REJECTED. The page's settle effect used to list
 * COMPLETED / AWAITING_APPROVAL / AWAITING_HUMAN_INPUT by hand, so REJECTED
 * matched nothing: the transcript stayed on the live stream, no conversation
 * was selected, the composer invited a *new* discussion, the Close action — the
 * one action a rejected run offers — was unreachable, and the sidebar went on
 * saying "Awaiting Approval" indefinitely, because the conversation-list poll
 * only runs while a discussion is IN_PROGRESS/SYNTHESIZING and nothing
 * invalidated it.
 *
 * None of the New Discussion cases above exercise the approve path, which is
 * how the regression got in: `userClearedRef` is set on approve and only the
 * settle effect clears it.
 */
describe("GroupDetailPage — rejecting a paused discussion", () => {
  /** An approve/stream that ends the run as REJECTED, as the backend now does. */
  function rejectionStream() {
    // The document the page reads changes when the decision lands, exactly as it
    // does against a real backend: AWAITING_APPROVAL until the resume commits,
    // REJECTED afterwards. A fixture stuck on one or the other cannot show the
    // bug, which is about the transition.
    let decided = false;
    server.use(
      http.get("*/groups/:groupId/conversations", () =>
        HttpResponse.json([
          {
            id: "gconv-paused",
            groupId: "grp1",
            userId: "manager-user",
            state: "AWAITING_APPROVAL",
            originalQuestion: "Fund the modernization grant?",
            transcript: [],
            memberConversationIds: {},
            currentPhaseIndex: 1,
            currentPhaseName: "Synthesis",
            synthesizedAnswer: "Recommend funding.",
            availableActions: [],
            depth: 0,
            taskList: null,
            dynamicMembers: [],
            createdAgentIds: [],
            retainedAgentIds: [],
            created: new Date(Date.now() - 600_000).toISOString(),
            lastModified: new Date(Date.now() - 60_000).toISOString(),
          },
        ]),
      ),
      http.get("*/groups/:groupId/conversations/:convId", () =>
        HttpResponse.json({
          id: "gconv-paused",
          groupId: "grp1",
          userId: "manager-user",
          state: decided ? "REJECTED" : "AWAITING_APPROVAL",
          pausedAt: decided ? null : new Date(Date.now() - 60_000).toISOString(),
          hitlPauseType: decided ? null : "PHASE",
          pausedPhaseName: decided ? null : "Synthesis",
          originalQuestion: "Fund the modernization grant?",
          transcript: [],
          memberConversationIds: {},
          currentPhaseIndex: 1,
          currentPhaseName: "Synthesis",
          synthesizedAnswer: "Recommend funding.",
          // What the backend computes for each state.
          availableActions: decided ? ["close"] : [],
          depth: 0,
          taskList: null,
          dynamicMembers: [],
          createdAgentIds: [],
          retainedAgentIds: [],
          created: new Date(Date.now() - 600_000).toISOString(),
          lastModified: new Date().toISOString(),
        }),
      ),
      http.post("*/groups/:groupId/conversations/:gcId/approve/stream", () => {
        decided = true;
        const encoder = new TextEncoder();
        const stream = new ReadableStream({
          start(controller) {
            controller.enqueue(
              encoder.encode(
                'event: hitl_resume\ndata: {"verdict":"REJECTED","decidedBy":"manager-user"}\n\n',
              ),
            );
            controller.enqueue(
              encoder.encode(
                'event: group_complete\ndata: {"state":"REJECTED","synthesizedAnswer":"Recommend funding."}\n\n',
              ),
            );
            controller.close();
          },
        });
        return new HttpResponse(stream, { headers: { "Content-Type": "text/event-stream" } });
      }),
    );
  }

  it("lands back on the rejected discussion, with its Close action reachable", async () => {
    rejectionStream();
    renderGroupDetail("?version=1&conversation=gconv-paused");

    await waitFor(() => {
      expect(screen.getByTestId("reject-button")).toBeInTheDocument();
    });
    await userEvent.click(screen.getByTestId("reject-button"));
    // No destructive HITL action fires on a single click.
    await userEvent.click(await screen.findByTestId("alert-dialog-confirm"));

    // The settle effect must re-select the discussion the stream just ended, or
    // nothing on the page addresses it any more.
    await waitFor(() => {
      expect(screen.getByTestId("discussion-item-gconv-paused")).toHaveAttribute(
        "aria-current",
        "true",
      );
    });
    // The one action a rejected run offers.
    await waitFor(() => {
      expect(screen.getByTestId("action-close")).toBeInTheDocument();
    });
  });
});
