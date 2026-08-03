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
});
