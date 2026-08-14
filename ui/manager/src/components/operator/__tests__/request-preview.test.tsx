import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
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
