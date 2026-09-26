import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { GroupsPage } from "@/pages/groups";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

/**
 * Each distinct filter is an enriched listing: the descriptors plus one config
 * read per listed group. Querying per keystroke fired that fan-out for every
 * character typed.
 */
describe("GroupsPage — search", () => {
  it("queries once for what was typed, not once per keystroke", async () => {
    const filters: string[] = [];
    server.use(
      http.get("*/groupstore/groups/descriptors", ({ request }) => {
        filters.push(new URL(request.url).searchParams.get("filter") ?? "");
        return HttpResponse.json([]);
      }),
    );
    renderWithProviders(<GroupsPage />, { initialRoute: "/manage/groups" });
    await waitFor(() => expect(filters).toContain(""));

    await userEvent.type(screen.getByTestId("group-search"), "review");

    await waitFor(() => expect(filters).toContain("review"));
    // The initial unfiltered listing, then the finished word — no "r", "re", …
    expect(filters.filter((f) => f !== "")).toEqual(["review"]);
  });
});
