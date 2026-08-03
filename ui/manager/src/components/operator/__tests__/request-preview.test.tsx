import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
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

    it("prefers the concrete finding over the incomplete-scan note when both could apply", () => {
      // A truncated body that still yielded a flag: naming the actual grant is
      // strictly more useful than saying the scan may have missed something.
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
      expect(
        screen.queryByTestId("request-preview-escalation-unchecked-call-1"),
      ).not.toBeInTheDocument();
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
