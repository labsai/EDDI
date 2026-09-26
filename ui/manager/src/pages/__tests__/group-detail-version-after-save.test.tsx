import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { GroupDetailPage } from "@/pages/group-detail";

/**
 * Every group save creates a new version, and the backend refuses a save or a
 * delete addressed to one that is no longer current. The group detail page
 * stayed on the version in its URL after an inline editor saved: the refetch
 * showed the pre-save document (the edit "reverted"), the next save 409'd, and
 * "Delete group + members" soft-deleted every member before its group delete
 * 409'd.
 *
 * The backend here is a small stateful fake: a write to a non-current version
 * is a 409, exactly as the real one answers.
 */

interface GroupDoc {
  name: string;
  hitlConfig?: unknown;
  phases: { name: string; [key: string]: unknown }[];
  [key: string]: unknown;
}

let current: number;
let docs: Record<number, GroupDoc>;
let conflicts: string[];
let deletes: string[];

const BASE: GroupDoc = {
  name: "Versioned Group",
  description: "",
  members: [
    { agentId: "agent1", displayName: "A", speakingOrder: 1, role: null, memberType: "AGENT" },
  ],
  moderatorAgentId: null,
  style: "ROUND_TABLE",
  maxRounds: 1,
  phases: [
    { name: "Initial Opinions", type: "OPINION", participants: "*", turnOrder: "SEQUENTIAL", contextScope: "NONE", targetEachPeer: false, inputTemplate: null, repeats: 1 },
    { name: "Synthesis", type: "SYNTHESIS", participants: "*", turnOrder: "SEQUENTIAL", contextScope: "FULL", targetEachPeer: false, inputTemplate: null, repeats: 1 },
  ],
  protocol: null,
};

beforeEach(() => {
  current = 1;
  docs = { 1: structuredClone(BASE) };
  conflicts = [];
  deletes = [];
  const version = (request: Request) => Number(new URL(request.url).searchParams.get("version"));
  server.use(
    http.get("*/groupstore/groups/gv1", ({ request }) => {
      const doc = docs[version(request)];
      return doc ? HttpResponse.json(doc) : new HttpResponse(null, { status: 404 });
    }),
    http.put("*/groupstore/groups/gv1", async ({ request }) => {
      if (version(request) !== current) {
        conflicts.push(`put@${version(request)}`);
        return new HttpResponse(null, { status: 409 });
      }
      current += 1;
      docs[current] = (await request.json()) as GroupDoc;
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: `eddi://ai.labs.group/groupstore/groups/gv1?version=${current}` },
      });
    }),
    http.delete("*/groupstore/groups/gv1", ({ request }) => {
      if (version(request) !== current) {
        conflicts.push(`delete@${version(request)}`);
        return new HttpResponse(null, { status: 409 });
      }
      deletes.push(`group@${version(request)}`);
      return new HttpResponse(null, { status: 204 });
    }),
    http.get("*/groups/gv1/conversations", () => HttpResponse.json([])),
  );
});

function renderGroup() {
  return renderPage("/manage/groups/gv1?version=1", <GroupDetailPage />, "/manage/groups/:id");
}

/** Toggle approval on the Synthesis phase through the inline HITL editor. */
async function toggleApproval(user: ReturnType<typeof userEvent.setup>) {
  const [editButton] = await screen.findAllByTestId("group-hitl-edit", {}, { timeout: 3000 });
  await user.click(editButton!);
  const editor = screen.getAllByTestId("group-hitl-editor")[0]!;
  await user.click(within(editor).getByTestId("group-hitl-enable"));
  const phase = within(editor).queryByTestId("group-hitl-phase-Synthesis");
  if (phase) await user.click(phase);
  await user.click(within(editor).getByTestId("group-hitl-save"));
}

describe("GroupDetailPage — after an inline save", () => {
  it("shows the saved document and saves again on top of it", async () => {
    renderGroup();
    const user = userEvent.setup();

    await toggleApproval(user);
    await waitFor(() => expect(current).toBe(2));
    // The edit stays on screen: the panel summarises the new approval point
    // (on the pre-save version there is none to summarise).
    await waitFor(() => expect(screen.getAllByText("Approval at").length).toBeGreaterThan(0));

    await toggleApproval(user);
    await waitFor(() => expect(current).toBe(3));

    expect(conflicts).toEqual([]);
    expect(docs[2]!.hitlConfig).toBeTruthy();
    expect(docs[3]!.hitlConfig).toBeUndefined();
  });

  it("deletes the version the save created", async () => {
    renderGroup();
    const user = userEvent.setup();

    await toggleApproval(user);
    await waitFor(() => expect(current).toBe(2));
    await waitFor(() => expect(screen.getAllByText("Approval at").length).toBeGreaterThan(0));

    const [deleteOnly] = await screen.findAllByRole("button", { name: /Delete Group Only/ });
    await user.click(deleteOnly!);
    await user.click(screen.getAllByRole("button", { name: /Confirm/ })[0]!);

    await waitFor(() => expect(deletes).toEqual(["group@2"]));
    expect(conflicts).toEqual([]);
  });
});
