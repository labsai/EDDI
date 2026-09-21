import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { NegotiationLedger } from "@/components/groups/negotiation-ledger";
import type { NegotiationState } from "@/lib/api/groups";

const names = { "agent-a": "Party A", "agent-b": "Party B" };

describe("NegotiationLedger", () => {
  it("renders nothing when there is neither a proposal nor a concession", () => {
    const { container } = renderWithProviders(
      <NegotiationLedger negotiation={{ proposals: [], concessions: [] }} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("shows an OPEN proposal with its acceptors, resolved to display names", () => {
    const negotiation: NegotiationState = {
      proposals: [
        {
          id: "p1", byAgentId: "agent-a", round: 2, terms: "50/50 split",
          status: "OPEN", acceptedBy: ["agent-a", "agent-b"], acceptanceEntryIndices: {},
        },
      ],
      concessions: [],
    };
    renderWithProviders(<NegotiationLedger negotiation={negotiation} memberDisplayNames={names} />);

    const card = screen.getByTestId("negotiation-proposal-p1");
    expect(card).toHaveTextContent("Party A");
    expect(card).toHaveTextContent("50/50 split");
    expect(card).toHaveTextContent("Party A, Party B");
    expect(screen.getByTestId("negotiation-proposal-status-p1")).toHaveTextContent("Open");
  });

  it("visually distinguishes a SUPERSEDED proposal from an OPEN one", () => {
    const negotiation: NegotiationState = {
      proposals: [
        { id: "p1", byAgentId: "agent-a", round: 1, terms: "first offer", status: "SUPERSEDED", acceptedBy: [], acceptanceEntryIndices: {} },
        { id: "p2", byAgentId: "agent-a", round: 2, terms: "second offer", status: "OPEN", acceptedBy: [], acceptanceEntryIndices: {} },
      ],
      concessions: [],
    };
    renderWithProviders(<NegotiationLedger negotiation={negotiation} />);

    expect(screen.getByTestId("negotiation-proposal-status-p1")).toHaveTextContent("Superseded");
    expect(screen.getByTestId("negotiation-proposal-status-p2")).toHaveTextContent("Open");
  });

  it("renders the concession ledger with what was given up and received", () => {
    const negotiation: NegotiationState = {
      proposals: [],
      concessions: [
        { byAgentId: "agent-b", round: 3, gaveUp: "exclusivity clause", inReturnFor: "faster timeline", refProposalId: "p2" },
      ],
    };
    renderWithProviders(<NegotiationLedger negotiation={negotiation} memberDisplayNames={names} />);

    const row = screen.getByTestId("negotiation-concession-0");
    expect(row).toHaveTextContent("Party B");
    expect(row).toHaveTextContent("exclusivity clause");
    expect(row).toHaveTextContent("faster timeline");
    expect(row).toHaveTextContent("p2");
  });

  it("falls back to the raw agent id when no display name is known", () => {
    const negotiation: NegotiationState = {
      proposals: [
        { id: "p1", byAgentId: "unknown-agent", round: 1, terms: "x", status: "OPEN", acceptedBy: [], acceptanceEntryIndices: {} },
      ],
      concessions: [],
    };
    renderWithProviders(<NegotiationLedger negotiation={negotiation} />);
    expect(screen.getByTestId("negotiation-proposal-p1")).toHaveTextContent("unknown-agent");
  });
});
