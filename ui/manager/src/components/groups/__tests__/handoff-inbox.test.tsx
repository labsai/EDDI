import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { HandoffInbox } from "@/components/groups/handoff-inbox";
import { HandoffOriginBadge } from "@/components/groups/handoff-origin-badge";
import type { HandoffOrigin, InboxItem } from "@eddi/app-kit/lib/api/team-handoffs";

function origin(overrides: Partial<HandoffOrigin> = {}): HandoffOrigin {
  return {
    handoffId: "h1",
    sourceKind: "AGENT",
    sourceId: "agent-9",
    sourceVersion: 2,
    sourceConversationId: "conv-1",
    trigger: "TOOL",
    hop: 1,
    lineage: ["agent:agent-9"],
    dedupeKey: "dk",
    filedAt: "2026-11-02T10:00:00Z",
    intake: "PENDING",
    ...overrides,
  };
}

function item(overrides: Partial<InboxItem> = {}): InboxItem {
  return {
    handoffId: "h1",
    subject: "Warm lead: Acme",
    description: "Asked for a quote on 40 seats",
    priority: 5,
    origin: origin(),
    ...overrides,
  };
}

describe("HandoffInbox", () => {
  it("renders nothing for an empty inbox", () => {
    const { container } = renderWithProviders(<HandoffInbox groupId="g1" items={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("shows what was handed over, from whom, and how", () => {
    renderWithProviders(<HandoffInbox groupId="g1" items={[item()]} nameOf={(id) => (id === "agent-9" ? "Inbound Agent" : id)} />);
    expect(screen.getByText("Warm lead: Acme")).toBeInTheDocument();
    expect(screen.getByText("Asked for a quote on 40 seats")).toBeInTheDocument();
    expect(screen.getByTestId("handoff-from-h1")).toHaveTextContent("From agent Inbound Agent · tool call · hop 1");
    expect(screen.getByRole("heading", { name: /Intake \(1\)/ })).toBeInTheDocument();
  });

  it("names a decision-rule handoff from a team by its id", () => {
    renderWithProviders(
      <HandoffInbox
        groupId="g1"
        items={[item({ origin: origin({ sourceKind: "GROUP", sourceId: "radar-group", trigger: "DECISION", hop: 2 }) })]}
      />,
    );
    expect(screen.getByTestId("handoff-from-h1")).toHaveTextContent("From team radar-group · decision rule · hop 2");
  });

  it("accepts as filed with a bodyless request", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post("*/groupstore/groups/g1/workspace/handoffs/h1/accept", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ message: "Accepted onto the backlog.", task: null, origin: null });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<HandoffInbox groupId="g1" items={[item()]} />);

    await user.click(screen.getByTestId("handoff-accept"));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body).toEqual({});
  });

  it("accepts with edits, sending the changed subject, description and priority", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post("*/groupstore/groups/g1/workspace/handoffs/h1/accept", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ message: "ok", task: null, origin: null });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<HandoffInbox groupId="g1" items={[item()]} />);

    await user.click(screen.getByTestId("handoff-edit"));
    const subject = screen.getByTestId("handoff-edit-subject");
    await user.clear(subject);
    await user.type(subject, "Warm lead: Acme (40 seats)");
    await user.clear(screen.getByTestId("handoff-edit-priority"));
    await user.type(screen.getByTestId("handoff-edit-priority"), "8");
    await user.click(screen.getByTestId("handoff-accept-edited"));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body).toMatchObject({ subject: "Warm lead: Acme (40 seats)", description: "Asked for a quote on 40 seats", priority: 8 });
  });

  it("will not accept an edit that blanks the subject", async () => {
    const user = userEvent.setup();
    renderWithProviders(<HandoffInbox groupId="g1" items={[item()]} />);
    await user.click(screen.getByTestId("handoff-edit"));
    await user.clear(screen.getByTestId("handoff-edit-subject"));
    expect(screen.getByTestId("handoff-accept-edited")).toBeDisabled();
  });

  it("declines with a reason", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post("*/groupstore/groups/g1/workspace/handoffs/h1/decline", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ message: "Declined.", task: null, origin: null });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<HandoffInbox groupId="g1" items={[item()]} />);

    await user.click(screen.getByTestId("handoff-decline"));
    await user.type(screen.getByTestId("handoff-decline-reason"), "out_of_scope");
    await user.click(screen.getByTestId("handoff-decline-confirm"));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body).toEqual({ reasonCode: "out_of_scope" });
  });

  it("shows the server's sentence when the backlog is full", async () => {
    server.use(
      http.post("*/groupstore/groups/g1/workspace/handoffs/h1/accept", () =>
        HttpResponse.json({ error: "The backlog already holds 200 tasks — delete finished tasks before accepting more." }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<HandoffInbox groupId="g1" items={[item()]} />);

    await user.click(screen.getByTestId("handoff-accept"));

    expect(await screen.findByTestId("handoff-error")).toHaveTextContent("The backlog already holds 200 tasks");
  });

  it("hides the decision buttons for a read-only viewer", () => {
    renderWithProviders(<HandoffInbox groupId="g1" items={[item()]} readOnly />);
    expect(screen.queryByTestId("handoff-accept")).not.toBeInTheDocument();
    expect(screen.queryByTestId("handoff-decline")).not.toBeInTheDocument();
    expect(screen.getByText("Warm lead: Acme")).toBeInTheDocument();
  });
});

describe("HandoffOriginBadge", () => {
  it("renders nothing for a task the team authored itself", () => {
    const { container } = renderWithProviders(<HandoffOriginBadge origin={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("says where a handed-off task came from, and whether it was edited on the way in", () => {
    const { rerender } = renderWithProviders(
      <HandoffOriginBadge origin={origin({ intake: "APPROVED" })} nameOf={() => "Inbound Agent"} />,
    );
    const badge = screen.getByTestId("handoff-origin-h1");
    expect(badge).toHaveTextContent("from Inbound Agent");
    expect(badge).toHaveAttribute("title", "Handed off from Inbound Agent (hop 1)");

    rerender(<HandoffOriginBadge origin={origin({ intake: "APPROVED_EDITED" })} nameOf={() => "Inbound Agent"} />);
    expect(screen.getByTestId("handoff-origin-h1")).toHaveAttribute(
      "title",
      "Handed off from Inbound Agent (hop 1), edited on acceptance",
    );
  });
});
