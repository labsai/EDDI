import { describe, it, expect } from "vitest";
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
