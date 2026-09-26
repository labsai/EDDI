import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { useNavigate } from "react-router-dom";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { WorkforceSettings } from "../workforce-settings";

/**
 * Task force settings after a save.
 *
 * The page stayed on the version in its URL. That document never changes, so
 * after a save the form stayed "dirty" forever, and the next save or the
 * delete addressed a version that was no longer current — a 409.
 *
 * The fake backend below answers exactly like the real one on the parts that
 * matter: a write to a non-current version is a 409, and what it stores comes
 * back NORMALISED (here: every member gains `role: null`), so the page has to
 * adopt the server's copy rather than compare against what it sent.
 */

interface Doc {
  name: string;
  members: Record<string, unknown>[];
  [key: string]: unknown;
}

let current: number;
let docs: Record<number, Doc>;
let conflicts: string[];
let deletes: number[];
/** A read of this version waits for `releaseRead()` — to land a refetch mid-edit. */
let heldVersion: number | null;
let releaseRead: () => void;

beforeEach(() => {
  current = 1;
  docs = {
    1: {
      name: "Board One",
      description: "",
      style: "ROUND_TABLE",
      maxRounds: 3,
      moderatorAgentId: null,
      members: [{ agentId: "agent1", displayName: "A", speakingOrder: 1, memberType: "AGENT", role: null }],
      phases: null,
      protocol: null,
    },
  };
  conflicts = [];
  deletes = [];
  heldVersion = null;
  const gate = new Promise<void>((resolve) => (releaseRead = resolve));
  const version = (request: Request) => Number(new URL(request.url).searchParams.get("version"));
  server.use(
    http.get("*/groupstore/groups/wb1", async ({ request }) => {
      if (version(request) === heldVersion) await gate;
      const doc = docs[version(request)];
      return doc ? HttpResponse.json(doc) : new HttpResponse(null, { status: 404 });
    }),
    http.put("*/groupstore/groups/wb1", async ({ request }) => {
      if (version(request) !== current) {
        conflicts.push(`put@${version(request)}`);
        return new HttpResponse(null, { status: 409 });
      }
      const body = (await request.json()) as Doc;
      current += 1;
      docs[current] = {
        ...body,
        members: body.members.map((m) => ({ role: null, ...m })),
      };
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: `eddi://ai.labs.group/groupstore/groups/wb1?version=${current}` },
      });
    }),
    http.delete("*/groupstore/groups/wb1", ({ request }) => {
      if (version(request) !== current) {
        conflicts.push(`delete@${version(request)}`);
        return new HttpResponse(null, { status: 409 });
      }
      deletes.push(version(request));
      return new HttpResponse(null, { status: 204 });
    }),
  );
});

/** Stands in for any in-app link to an older version of the board. */
function OpenVersionOne() {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate("/workforce/wb1/settings?version=1")}>
      open v1
    </button>
  );
}

function renderSettings() {
  return renderPage(
    "/workforce/wb1/settings?version=1",
    <>
      <WorkforceSettings />
      <OpenVersionOne />
    </>,
    "/workforce/:boardId/settings",
  );
}

async function rename(user: ReturnType<typeof userEvent.setup>, name: string) {
  const input = await screen.findByLabelText(/Task Force Name/i);
  await user.clear(input);
  await user.type(input, name);
  await user.click(screen.getByRole("button", { name: /Save Changes/i }));
  // Wait for the page to finish handling the save, not just for the server.
  await waitFor(() => expect(screen.queryByText(/Saving…/)).not.toBeInTheDocument());
}

describe("WorkforceSettings — after a save", () => {
  it("is clean again, and a second save lands on top of the first", async () => {
    renderSettings();
    const user = userEvent.setup();
    await screen.findByDisplayValue("Board One");

    await rename(user, "Board Two");
    await waitFor(() => expect(current).toBe(2));
    await waitFor(() => expect(screen.getByRole("button", { name: /Save Changes/i })).toBeDisabled());
    expect(screen.getByDisplayValue("Board Two")).toBeInTheDocument();

    await rename(user, "Board Three");
    await waitFor(() => expect(current).toBe(3));

    expect(conflicts).toEqual([]);
    expect(docs[3]!.name).toBe("Board Three");
    // And it stays clean once the server's (normalised) copy has been read.
    await waitFor(() => expect(screen.getByRole("button", { name: /Save Changes/i })).toBeDisabled());
  });

  it("deletes the version the save created", async () => {
    renderSettings();
    const user = userEvent.setup();
    await screen.findByDisplayValue("Board One");

    await rename(user, "Board Two");
    await waitFor(() => expect(current).toBe(2));

    await user.click(screen.getByRole("button", { name: "Dissolve Task Force" }));
    await user.click(await screen.findByTestId("alert-dialog-confirm"));

    await waitFor(() => expect(deletes).toEqual([2]));
    expect(conflicts).toEqual([]);
  });

  it("does not overwrite an edit that is still in progress", async () => {
    renderSettings();
    const user = userEvent.setup();
    await screen.findByDisplayValue("Board One");

    // Hold the refetch of the saved version until a new edit is under way.
    heldVersion = 2;
    await rename(user, "Board Two");
    await waitFor(() => expect(current).toBe(2));
    const input = screen.getByLabelText(/Task Force Name/i);
    await user.clear(input);
    await user.type(input, "Draft");

    // The server's (normalised, so different) copy of v2 arrives now.
    releaseRead();
    await waitFor(() => expect(screen.getByRole("button", { name: /Save Changes/i })).toBeEnabled());
    await new Promise((r) => setTimeout(r, 100));
    expect(screen.getByDisplayValue("Draft")).toBeInTheDocument();
  });

  it("follows the URL back to an older version after a save", async () => {
    renderSettings();
    const user = userEvent.setup();
    await screen.findByDisplayValue("Board One");

    await rename(user, "Board Two");
    await waitFor(() => expect(current).toBe(2));
    await waitFor(() => expect(screen.getByRole("button", { name: /Save Changes/i })).toBeDisabled());

    await user.click(screen.getByRole("button", { name: "open v1" }));
    await screen.findByDisplayValue("Board One");
  });
});
