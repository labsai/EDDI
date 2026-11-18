import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ApiClientError } from "@eddi/app-kit/lib/api-client";
import { conflictDetails } from "@eddi/app-kit/lib/conflict-details";
import { ConflictDetailsPanel } from "@eddi/app-kit/components/shared/conflict-details-panel";

describe("conflictDetails", () => {
  it("is null for anything but a 409", () => {
    expect(conflictDetails(new ApiClientError(500, "boom"))).toBeNull();
    expect(conflictDetails(new Error("x"))).toBeNull();
  });

  it("keeps the server's sentence and renders every list it carries, whatever the field names", () => {
    const err = new ApiClientError(409, "Agent is still in use", undefined, undefined, {
      activeConversations: ["conv-1", "conv-2"],
      usedBy: { workflows: [{ id: "wf-1", name: "Support flow" }, { resource: "eddi://x" }] },
      count: 2,
    });
    const details = conflictDetails(err)!;
    expect(details.message).toBe("Agent is still in use");
    expect(details.lists.map((l) => l.field)).toEqual(["activeConversations", "usedBy.workflows"]);
    expect(details.lists[1]!.items).toEqual(["Support flow · wf-1", "eddi://x"]);
  });

  it("copes with a 409 whose body is not JSON", () => {
    const details = conflictDetails(new ApiClientError(409, "conflict"))!;
    expect(details.lists).toEqual([]);
  });

  it("caps very long lists", () => {
    const many = Array.from({ length: 60 }, (_, i) => `c${i}`);
    const details = conflictDetails(new ApiClientError(409, "x", undefined, undefined, { conversations: many }))!;
    expect(details.lists[0]!.items).toHaveLength(26);
  });
});

describe("ConflictDetailsPanel", () => {
  it("renders the message and the lists as text", () => {
    render(
      <ConflictDetailsPanel
        details={{ message: "Still in use", lists: [{ field: "conversations", items: ["<b>c1</b>"] }] }}
      />,
    );
    expect(screen.getByTestId("conflict-details-message")).toHaveTextContent("Still in use");
    expect(screen.getByText("<b>c1</b>")).toBeInTheDocument();
  });
});
