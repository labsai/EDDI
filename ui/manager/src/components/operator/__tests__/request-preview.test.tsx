import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";

const authState = vi.hoisted(() => ({ roles: [] as string[], method: "none" as "none" | "keycloak" }));

vi.mock("@/hooks/use-auth", () => ({
  useAuth: () => ({ authenticated: true, loading: false, user: null, roles: authState.roles, method: authState.method, login: () => {}, logout: () => {} }),
  useHasRole: (role: string) => authState.method === "none" || authState.roles.includes(role),
}));
import { RequestPreview } from "@/components/operator/request-preview";
import type { ResolvedRequestPreview } from "@/lib/api/hitl";

function preview(overrides: Partial<ResolvedRequestPreview> = {}): ResolvedRequestPreview {
  return {
    method: "POST",
    uri: "https://eddi.example.com/agentstore/agents",
    queryParams: {},
    headers: {},
    body: null,
    bodyTruncated: false,
    ...overrides,
  };
}

describe("RequestPreview", () => {
  it("renders the method and uri", () => {
    renderWithProviders(<RequestPreview preview={preview()} pinned callId="call-1" />);
    expect(screen.getByText(/POST https:\/\/eddi\.example\.com\/agentstore\/agents/)).toBeInTheDocument();
  });

  it("shows a 'verified' badge when pinned", () => {
    renderWithProviders(<RequestPreview preview={preview()} pinned callId="call-1" />);
    expect(screen.getByTestId("request-preview-badge-call-1")).toHaveTextContent(/verified/i);
  });

  it("shows a 'preview' badge when not pinned, distinct from the verified case", () => {
    renderWithProviders(<RequestPreview preview={preview()} pinned={false} callId="call-1" />);
    const badge = screen.getByTestId("request-preview-badge-call-1");
    expect(badge).toHaveTextContent(/preview/i);
    expect(badge).not.toHaveTextContent(/verified/i);
  });

  it("renders query params when present", () => {
    renderWithProviders(
      <RequestPreview preview={preview({ queryParams: { limit: "10", q: "foo" } })} pinned callId="call-1" />,
    );
    expect(screen.getByText(/limit=10, q=foo/)).toBeInTheDocument();
  });

  it("omits the query line when there are no query params", () => {
    renderWithProviders(<RequestPreview preview={preview()} pinned callId="call-1" />);
    expect(screen.queryByText(/^Query:/)).not.toBeInTheDocument();
  });

  it("renders headers when present", () => {
    renderWithProviders(
      <RequestPreview
        preview={preview({ headers: { "Content-Type": "application/json" } })}
        pinned
        callId="call-1"
      />,
    );
    expect(screen.getByText(/Content-Type: application\/json/)).toBeInTheDocument();
  });

  it("renders the body in a pre block when present", () => {
    renderWithProviders(
      <RequestPreview preview={preview({ body: '{"name":"foo"}' })} pinned callId="call-1" />,
    );
    expect(screen.getByTestId("request-preview-body-call-1")).toHaveTextContent('{"name":"foo"}');
  });

  it("omits the body block when body is null (e.g. a GET request)", () => {
    renderWithProviders(<RequestPreview preview={preview({ body: null })} pinned callId="call-1" />);
    expect(screen.queryByTestId("request-preview-body-call-1")).not.toBeInTheDocument();
  });

  it("shows a truncation note when bodyTruncated is true", () => {
    renderWithProviders(
      <RequestPreview preview={preview({ body: "...", bodyTruncated: true })} pinned callId="call-1" />,
    );
    expect(screen.getByText(/truncated/i)).toBeInTheDocument();
  });

  it("does not show a truncation note when bodyTruncated is false", () => {
    renderWithProviders(
      <RequestPreview preview={preview({ body: "small", bodyTruncated: false })} pinned callId="call-1" />,
    );
    expect(screen.queryByText(/truncated/i)).not.toBeInTheDocument();
  });

  it("scopes testids to callId so multiple calls in a batch don't collide", () => {
    renderWithProviders(<RequestPreview preview={preview()} pinned callId="call-42" />);
    expect(screen.getByTestId("request-preview-call-42")).toBeInTheDocument();
  });

  describe("escalation warnings", () => {
    it("calls out a body that grants further capability", () => {
      // The setting is in the JSON below too, but an approver skimming a config
      // document misses exactly this line — which is the whole point.
      renderWithProviders(
        <RequestPreview
          preview={preview({
            body: JSON.stringify({ name: "board", dynamicAgents: { enabled: true, allowCreation: true } }),
          })}
          pinned
          callId="call-1"
        />,
      );
      const warning = screen.getByTestId("request-preview-escalations-call-1");
      expect(warning).toHaveTextContent(/grants further capability/i);
      expect(warning).toHaveTextContent(/dynamicAgents\.allowCreation/);
      expect(warning).toHaveTextContent(/create new agents/i);
    });

    it("calls out an agent being created with no approval gate", () => {
      renderWithProviders(
        <RequestPreview
          preview={preview({
            body: JSON.stringify({ agentName: "Refund helper", systemPrompt: "You help with refunds." }),
          })}
          pinned
          callId="call-1"
        />,
      );
      const warning = screen.getByTestId("request-preview-escalations-call-1");
      expect(warning).toHaveTextContent(/no approval gate/i);
    });

    it("calls out a create_api_agent with write access to its own API", () => {
      renderWithProviders(
        <RequestPreview
          preview={preview({
            body: JSON.stringify({
              agentName: "Ticketing bridge",
              systemPrompt: "You file tickets.",
              openApiSpec: "https://tickets.example.com/openapi.json",
              endpoints: "GET /tickets,DELETE /tickets/{id}",
              hitlConfig: { toolApprovals: { requireApproval: ["http.post:*"] } },
            }),
          })}
          pinned
          callId="call-1"
        />,
      );
      const warning = screen.getByTestId("request-preview-escalations-call-1");
      expect(warning).toHaveTextContent(/write access to its api/i);
    });

    it("stays silent for an ordinary body", () => {
      renderWithProviders(
        <RequestPreview
          preview={preview({ body: JSON.stringify({ name: "board", members: [{ agentId: "a1" }] }) })}
          pinned
          callId="call-1"
        />,
      );
      expect(screen.queryByTestId("request-preview-escalations-call-1")).not.toBeInTheDocument();
    });

    it("says the scan was incomplete when the body was truncated, rather than showing nothing", () => {
      // The false negative this closes: a body over the preview cap does not
      // parse, so the scan finds nothing — and an approver reads "no warning"
      // as "no capability grant". A group config can exceed the cap.
      renderWithProviders(
        <RequestPreview
          preview={preview({ body: '{"name":"board","members":[{"agentId', bodyTruncated: true })}
          pinned
          callId="call-1"
        />,
      );
      expect(screen.getByTestId("request-preview-escalation-unchecked-call-1")).toHaveTextContent(
        /too long to scan/i,
      );
    });

    it("does not claim an incomplete scan when the body was not truncated", () => {
      renderWithProviders(
        <RequestPreview
          preview={preview({ body: JSON.stringify({ name: "board" }), bodyTruncated: false })}
          pinned
          callId="call-1"
        />,
      );
      expect(
        screen.queryByTestId("request-preview-escalation-unchecked-call-1"),
      ).not.toBeInTheDocument();
    });

    it("shows BOTH the concrete finding and the incomplete-scan note on a truncated body", () => {
      // These say different things and both are true: "here is a grant we
      // found" does not imply "and there is nothing past the cut". Suppressing
      // the note whenever any flag fired was defensible only while every check
      // needed the whole document — an inline credential in the first line now
      // raises a flag immediately, which silently withdrew the notice exactly
      // when a grant past the cut is most likely to be missed.
      renderWithProviders(
        <RequestPreview
          preview={preview({
            body: JSON.stringify({ dynamicAgents: { enabled: true, allowCreation: true } }),
            bodyTruncated: true,
          })}
          pinned
          callId="call-1"
        />,
      );
      expect(screen.getByTestId("request-preview-escalations-call-1")).toBeInTheDocument();
      expect(screen.getByTestId("request-preview-escalation-unchecked-call-1")).toBeInTheDocument();
    });

    it("announces itself to assistive tech rather than being a silent colour change", () => {
      renderWithProviders(
        <RequestPreview
          preview={preview({
            body: JSON.stringify({ hitlConfig: { timeoutPolicy: "AUTO_APPROVE" } }),
          })}
          pinned
          callId="call-1"
        />,
      );
      expect(screen.getByTestId("request-preview-escalations-call-1")).toHaveAttribute("role", "alert");
    });
  });
});

/* ─── Whole-document PUT diff ─── */

/** Diff rows inside a rendered preview, optionally narrowed to one kind. */
function diffRows(diff: HTMLElement, kind?: "added" | "removed" | "context"): string[] {
  return within(diff)
    .getAllByTestId("diff-line")
    .filter((row) => !kind || row.getAttribute("data-diff-kind") === kind)
    .map((row) => row.lastElementChild?.textContent ?? "");
}

const STORED = { id: "r1", name: "Greeting rules", threshold: 5, description: "unchanged" };
const PROPOSED = { ...STORED, threshold: 9 };

function putPreview(overrides: Partial<ResolvedRequestPreview> = {}): ResolvedRequestPreview {
  return {
    method: "PUT",
    uri: "http://localhost:7070/rulestore/rulesets/r1?version=3",
    queryParams: { version: "3" },
    headers: {},
    body: JSON.stringify(PROPOSED, null, 2),
    bodyTruncated: false,
    ...overrides,
  };
}

function serveStored(body: Record<string, unknown> | null = STORED, status = 200) {
  server.use(
    http.get("*/rulestore/rulesets/r1", () =>
      status === 200 ? HttpResponse.json(body) : HttpResponse.json({ message: "no" }, { status }),
    ),
  );
}

describe("RequestPreview — whole-document PUT diff", () => {
  beforeEach(() => {
    authState.roles = [];
    authState.method = "none";
    server.resetHandlers();
  });

  it("shows what CHANGES rather than only the whole proposed document", async () => {
    // The gap this closes: approving a one-line edit to a large config meant
    // finding that line by eye in a 128px scroll box.
    serveStored();
    renderWithProviders(<RequestPreview preview={putPreview()} pinned callId="c1" />);

    const diff = await screen.findByTestId("request-preview-diff-c1");
    expect(diff).toHaveTextContent(/threshold/);
    // The legend speaks the approver's language, not the import dialog's.
    expect(diff).toHaveTextContent("Stored v3");
    expect(diff).toHaveTextContent("Proposed");
    expect(diff).not.toHaveTextContent(/Target/);
    expect(screen.queryByTestId("request-preview-body-issue-c1")).not.toBeInTheDocument();
  });

  describe("a proposed document that isn't JSON — the extra-brace report", () => {
    // The operator sent an updateLlm body with one closing brace too many. It
    // parsed on neither side of the approval, so the diff showed the compact
    // body as a single added line and the capability scan found nothing.

    it("says extra text after a complete document may be dropped on write, and still lines the diff up", async () => {
      serveStored();
      renderWithProviders(
        <RequestPreview preview={putPreview({ body: `${JSON.stringify(PROPOSED)}}` })} pinned callId="c1" />,
      );

      const warning = await screen.findByTestId("request-preview-body-issue-c1");
      expect(warning).toHaveAttribute("role", "alert");
      expect(warning).toHaveAttribute("data-issue", "trailingText");
      expect(warning).toHaveTextContent(/drop the rest/);

      const diff = await screen.findByTestId("request-preview-diff-c1");
      expect(diffRows(diff, "added")).toEqual(['  "threshold": 9,', "}"]);
      expect(diffRows(diff, "removed")).toEqual(['  "threshold": 5,']);
      expect(diffRows(diff).filter((line) => line.includes("unchanged"))).toHaveLength(1);
      // Warned once, above the diff — not again inside it.
      expect(within(diff).queryByTestId("diff-raw-comparison")).not.toBeInTheDocument();
    });

    it("says a document broken inside will be rejected", async () => {
      serveStored();
      renderWithProviders(
        <RequestPreview
          preview={putPreview({ body: JSON.stringify(PROPOSED).replace(/}$/, ",}") })}
          pinned
          callId="c1"
        />,
      );

      const warning = await screen.findByTestId("request-preview-body-issue-c1");
      expect(warning).toHaveAttribute("data-issue", "notJson");
      expect(warning).toHaveTextContent(/will reject this write/);
    });

    it("hedges when redacted credentials are in the body, since the preview is not the exact request", async () => {
      serveStored();
      renderWithProviders(
        <RequestPreview
          preview={putPreview({ body: JSON.stringify(PROPOSED).replace(/}$/, ',"apiKey=<REDACTED>",}') })}
          pinned
          callId="c1"
        />,
      );

      const warning = await screen.findByTestId("request-preview-body-issue-c1");
      expect(warning).toHaveAttribute("data-issue", "notJsonRedacted");
      expect(warning).toHaveTextContent(/most likely reject/);
    });
  });

  it("survives the redaction filter's mangled credential field — the shape from the report", async () => {
    // SecretRedactionFilter rewrites `"apiKey":"sk-…"` as `"apiKey=<REDACTED>"`,
    // which no longer parses. Before the repair this put the proposed body on
    // one raw line and every stored line in red.
    serveStored({ ...STORED, apiKey: "sk-ant-stored" });
    renderWithProviders(
      <RequestPreview
        preview={putPreview({
          body: '{"id":"r1","name":"Greeting rules","threshold":9,"description":"unchanged","apiKey=<REDACTED>"}',
        })}
        pinned
        callId="c1"
      />,
    );

    const diff = await screen.findByTestId("request-preview-diff-c1");
    expect(within(diff).queryByTestId("diff-raw-comparison")).not.toBeInTheDocument();
    expect(diffRows(diff, "added")).toContain('  "threshold": 9');
    // Unchanged, so one shared context row — not one on each side.
    expect(diffRows(diff).filter((line) => line.includes("unchanged"))).toHaveLength(1);
    // The redaction note still explains why apiKey shows as changed.
    expect(screen.getByTestId("request-preview-diff-redaction-note-c1")).toBeInTheDocument();
  });

  it("diffs a compact request body against the stored document without reporting a rewrite", async () => {
    // EDDI sends the resolved body as the operator built it — compact, one
    // line. Compared against the stored document as-is that reads as "every
    // line deleted, one line added", which is the opposite of a review aid.
    serveStored();
    renderWithProviders(
      <RequestPreview preview={putPreview({ body: JSON.stringify(PROPOSED) })} pinned callId="c1" />,
    );

    const diff = await screen.findByTestId("request-preview-diff-c1");
    expect(diffRows(diff, "added")).toContain('  "threshold": 9');
    expect(diffRows(diff, "removed")).toContain('  "threshold": 5');
    // The fields that did not change are rendered once, as shared context.
    expect(diffRows(diff).filter((line) => line.includes("unchanged"))).toHaveLength(1);
  });

  it("keeps the full proposed document reachable behind a toggle", async () => {
    // The diff is a reading aid; approval still covers the whole document.
    serveStored();
    renderWithProviders(<RequestPreview preview={putPreview()} pinned callId="c1" />);
    await screen.findByTestId("request-preview-diff-c1");

    expect(screen.queryByTestId("request-preview-body-c1")).not.toBeInTheDocument();
    await userEvent.click(screen.getByTestId("request-preview-body-toggle-c1"));
    expect(screen.getByTestId("request-preview-body-c1")).toHaveTextContent(/threshold/);
  });

  it("warns that redacted credentials will diff as false changes", async () => {
    serveStored();
    renderWithProviders(
      <RequestPreview
        preview={putPreview({ body: JSON.stringify({ ...PROPOSED, apiKey: "<REDACTED>" }, null, 2) })}
        pinned
        callId="c1"
      />,
    );
    expect(await screen.findByTestId("request-preview-diff-redaction-note-c1")).toBeInTheDocument();
  });

  it("does NOT diff a truncated body — that would report everything after the cut as deleted", async () => {
    serveStored();
    renderWithProviders(
      <RequestPreview preview={putPreview({ bodyTruncated: true })} pinned callId="c1" />,
    );
    await waitFor(() => expect(screen.getByTestId("request-preview-body-c1")).toBeInTheDocument());
    expect(screen.queryByTestId("request-preview-diff-c1")).not.toBeInTheDocument();
  });

  it("falls back to the raw body when the stored version can't be read", async () => {
    serveStored(null, 500);
    renderWithProviders(<RequestPreview preview={putPreview()} pinned callId="c1" />);

    expect(await screen.findByTestId("request-preview-diff-unavailable-c1")).toHaveTextContent(
      /couldn't load/i,
    );
    expect(screen.getByTestId("request-preview-body-c1")).toBeInTheDocument();
  });

  it("does not even attempt the read for a role that lacks editor access", async () => {
    // Reading the stored document is eddi-admin/eddi-editor only, and this
    // surface is used by eddi-approver — whose whole job is approving.
    authState.method = "keycloak";
    authState.roles = ["eddi-approver"];
    let requested = false;
    server.use(
      http.get("*/rulestore/rulesets/r1", () => {
        requested = true;
        return HttpResponse.json(STORED);
      }),
    );

    renderWithProviders(<RequestPreview preview={putPreview()} pinned callId="c1" />);

    expect(await screen.findByTestId("request-preview-diff-unavailable-c1")).toHaveTextContent(
      /editor access/i,
    );
    expect(screen.getByTestId("request-preview-body-c1")).toBeInTheDocument();
    await waitFor(() => expect(requested).toBe(false));
  });

  it("leaves a non-document write (a sub-resource verb) rendering exactly as before", () => {
    renderWithProviders(
      <RequestPreview
        preview={putPreview({ uri: "http://x/agentstore/agents/a1/updateResourceUri?version=1" })}
        pinned
        callId="c1"
      />,
    );
    expect(screen.getByTestId("request-preview-body-c1")).toBeInTheDocument();
    expect(screen.queryByTestId("request-preview-diff-c1")).not.toBeInTheDocument();
    expect(screen.queryByTestId("request-preview-diff-unavailable-c1")).not.toBeInTheDocument();
  });
});

describe("RequestPreview — cascade summary", () => {
  const LLM = "112233445566778899aabbcc";
  const WF = "ff00112233445566aa770011";
  const cascade = (body: unknown, overrides: Partial<ResolvedRequestPreview> = {}) =>
    preview({
      uri: "https://eddi.example/agentstore/agents/68f1c2a9b34d5e6f70819a2b/cascade?version=4",
      queryParams: { version: "4" },
      body: JSON.stringify(body),
      ...overrides,
    });

  it("lists each resource as old -> new and says it will redeploy, and where", () => {
    renderWithProviders(
      <RequestPreview
        preview={cascade({
          resourceMappings: [
            {
              oldUri: `eddi://ai.labs.llm/llmstore/llms/${LLM}?version=1`,
              newUri: `eddi://ai.labs.llm/llmstore/llms/${LLM}?version=2`,
            },
            {
              oldUri: `eddi://ai.labs.workflow/workflowstore/workflows/${WF}?version=3`,
              newUri: `eddi://ai.labs.workflow/workflowstore/workflows/${WF}?version=4`,
            },
          ],
          redeploy: true,
          environment: "test",
          compatible: false,
        })}
        pinned
        callId="c1"
      />,
    );

    const summary = screen.getByTestId("cascade-summary-c1");
    expect(summary).toHaveTextContent("agent 68f1c2a9b34d5e6f70819a2b");
    const rows = within(screen.getByTestId("cascade-mappings-c1")).getAllByRole("listitem");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent(`llm${LLM}v1 → v2`);
    expect(rows[1]).toHaveTextContent(`workflow${WF}v3 → v4`);
    expect(screen.getByTestId("cascade-redeploy-c1")).toHaveTextContent("Will redeploy to test");
    expect(screen.getByTestId("cascade-compatible-c1")).toHaveTextContent("breaking change");
    // The raw body is still there to read in full — the summary never replaces it.
    expect(screen.getByTestId("request-preview-body-c1")).toBeInTheDocument();
  });

  it("promises the switch only after a SUCCESSFUL deployment, with or without a redeploy in this call", () => {
    for (const redeploy of [true, false]) {
      const { unmount } = renderWithProviders(
        <RequestPreview
          preview={cascade({
            resourceMappings: [
              {
                oldUri: `eddi://ai.labs.llm/llmstore/llms/${LLM}?version=1`,
                newUri: `eddi://ai.labs.llm/llmstore/llms/${LLM}?version=2`,
              },
            ],
            redeploy,
            compatible: true,
          })}
          pinned
          callId="c1"
        />,
      );
      expect(screen.getByTestId("cascade-compatible-c1")).toHaveTextContent("after it is deployed successfully");
      unmount();
    }
  });

  it("states when it will NOT redeploy", () => {
    renderWithProviders(
      <RequestPreview
        preview={cascade({
          resourceMappings: [
            {
              oldUri: `eddi://ai.labs.llm/llmstore/llms/${LLM}?version=1`,
              newUri: `eddi://ai.labs.llm/llmstore/llms/${LLM}?version=2`,
            },
          ],
        })}
        pinned
        callId="c1"
      />,
    );
    expect(screen.getByTestId("cascade-redeploy-c1")).toHaveTextContent("Will not redeploy");
  });

  it("shows no summary for a truncated body, and no summary on any other request", () => {
    renderWithProviders(<RequestPreview preview={cascade({ resourceMappings: [] }, { bodyTruncated: true })} pinned callId="c1" />);
    expect(screen.queryByTestId("cascade-summary-c1")).not.toBeInTheDocument();
  });

  it("is not shown for an ordinary POST", () => {
    renderWithProviders(<RequestPreview preview={preview({ body: '{"name":"foo"}' })} pinned callId="c2" />);
    expect(screen.queryByTestId("cascade-summary-c2")).not.toBeInTheDocument();
  });
});

describe("RequestPreview — PATCH (RFC 6902)", () => {
  beforeEach(() => {
    authState.roles = [];
    authState.method = "none";
    server.resetHandlers();
  });

  const OPS = [
    { op: "add", path: "/tasks/0/knowledgeBases", value: [{ name: "eddi-docs" }] },
    { op: "replace", path: "/tasks/0/parameters/modelName", value: "claude-sonnet-4-5" },
    { op: "remove", path: "/tasks/0/parameters/temperature" },
  ];

  function patchPreview(overrides: Partial<ResolvedRequestPreview> = {}): ResolvedRequestPreview {
    return {
      method: "PATCH",
      uri: "http://localhost:7070/llmstore/llms/82b0aa?version=4",
      queryParams: { version: "4" },
      headers: {},
      body: JSON.stringify(OPS),
      bodyTruncated: false,
      ...overrides,
    };
  }

  it("lists each operation with its op, path and value", () => {
    renderWithProviders(<RequestPreview preview={patchPreview()} pinned callId="p1" />);

    expect(screen.getByTestId("request-preview-patch-op-p1-0")).toHaveTextContent(
      'add /tasks/0/knowledgeBases = [{"name":"eddi-docs"}]',
    );
    expect(screen.getByTestId("request-preview-patch-op-p1-1")).toHaveTextContent(
      'replace /tasks/0/parameters/modelName = "claude-sonnet-4-5"',
    );
    // A remove carries no value, and none is invented.
    expect(screen.getByTestId("request-preview-patch-op-p1-2").textContent).toBe("remove /tasks/0/parameters/temperature");
  });

  it("names the document and the version the patch applies to, and the version it will store", () => {
    renderWithProviders(<RequestPreview preview={patchPreview()} pinned callId="p1" />);

    const heading = screen.getByTestId("request-preview-patch-heading-p1");
    expect(heading).toHaveTextContent("Patch to llm 82b0aa");
    expect(heading).toHaveTextContent("82b0aa");
    expect(heading).toHaveTextContent("applied to version 4, stored as version 5");
  });

  it("does not fetch the stored document — the patch is the diff", async () => {
    let requested = false;
    server.use(
      http.get("*/llmstore/llms/82b0aa", () => {
        requested = true;
        return HttpResponse.json({});
      }),
    );
    renderWithProviders(<RequestPreview preview={patchPreview()} pinned callId="p1" />);

    expect(screen.queryByTestId("request-preview-diff-p1")).not.toBeInTheDocument();
    await waitFor(() => expect(requested).toBe(false));
  });

  it("keeps the raw patch reachable behind a toggle, off by default", async () => {
    renderWithProviders(<RequestPreview preview={patchPreview()} pinned callId="p1" />);

    expect(screen.queryByTestId("request-preview-body-p1")).not.toBeInTheDocument();
    await userEvent.click(screen.getByTestId("request-preview-body-toggle-p1"));
    expect(screen.getByTestId("request-preview-body-p1")).toHaveTextContent("knowledgeBases");
    expect(screen.getByTestId("request-preview-body-toggle-p1")).toHaveTextContent("Hide the raw patch");
  });

  it("shows a whole-document operation as such, and a move's source", () => {
    renderWithProviders(
      <RequestPreview
        preview={patchPreview({
          body: JSON.stringify([
            { op: "replace", path: "", value: { tasks: [] } },
            { op: "move", from: "/tasks/0", path: "/tasks/1" },
          ]),
        })}
        pinned
        callId="p1"
      />,
    );
    expect(screen.getByTestId("request-preview-patch-op-p1-0")).toHaveTextContent("replace (whole document) = {\"tasks\":[]}");
    expect(screen.getByTestId("request-preview-patch-op-p1-1")).toHaveTextContent("move /tasks/1 ← /tasks/0");
  });

  it("falls back to the raw body for a PATCH that is not a patch — the descriptor metadata edit", () => {
    renderWithProviders(
      <RequestPreview
        preview={patchPreview({
          uri: "http://localhost:7070/descriptorstore/descriptors/d1?version=2",
          body: JSON.stringify({ name: "New name" }),
        })}
        pinned
        callId="p1"
      />,
    );
    expect(screen.queryByTestId("request-preview-patch-p1")).not.toBeInTheDocument();
    expect(screen.getByTestId("request-preview-body-p1")).toHaveTextContent("New name");
  });

  it("does not list a truncated patch — a cut list reads as a shorter one", () => {
    renderWithProviders(<RequestPreview preview={patchPreview({ bodyTruncated: true })} pinned callId="p1" />);
    expect(screen.queryByTestId("request-preview-patch-p1")).not.toBeInTheDocument();
    expect(screen.getByTestId("request-preview-body-p1")).toBeInTheDocument();
  });

  it("still flags a literal credential written by a patch", () => {
    renderWithProviders(
      <RequestPreview
        preview={patchPreview({
          body: JSON.stringify([{ op: "replace", path: "/tasks/0/parameters/apiKey", value: "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789" }]),
        })}
        pinned
        callId="p1"
      />,
    );
    expect(screen.getByTestId("request-preview-escalations-p1")).toBeInTheDocument();
  });
});
