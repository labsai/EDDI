import { describe, it, expect } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ArtifactsPanel } from "@/components/groups/artifacts-panel";
import type { SharedArtifact } from "@/lib/api/groups";

// No Monaco stub needed: this panel is a read-only viewer and renders JSON in a
// plain <pre>. Mounting a full editor here previously crashed the entire page
// (`monaco.languages.json` undefined → error boundary), which a stubbed-out
// editor in the test would have hidden.

function makeArtifact(overrides: Partial<SharedArtifact> = {}): SharedArtifact {
  return {
    id: "art-1",
    groupConversationId: "gc-1",
    ownerUserId: "user-1",
    name: "research-notes.md",
    type: "MARKDOWN",
    content: "# Notes\n\nSome findings.",
    version: 1,
    lastEditorAgentId: "agent-1",
    status: "DRAFT",
    history: [],
    createdAt: "2026-06-01T00:00:00Z",
    updatedAt: "2026-06-01T00:00:00Z",
    ...overrides,
  };
}

describe("ArtifactsPanel", () => {
  it("renders nothing when there are no artifacts", () => {
    const { container } = renderWithProviders(<ArtifactsPanel artifacts={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("lists artifacts collapsed, showing name/status/version but not content", () => {
    renderWithProviders(<ArtifactsPanel artifacts={[makeArtifact()]} />);

    expect(screen.getByTestId("artifact-art-1")).toHaveTextContent("research-notes.md");
    expect(screen.getByTestId("artifact-art-1")).toHaveTextContent("v1");
    expect(screen.queryByTestId("artifact-content-art-1")).not.toBeInTheDocument();
  });

  it("expands to show markdown content on click", () => {
    renderWithProviders(<ArtifactsPanel artifacts={[makeArtifact()]} />);

    fireEvent.click(screen.getByTestId("artifact-toggle-art-1"));

    const content = screen.getByTestId("artifact-content-art-1");
    expect(content).toHaveTextContent("Some findings.");
  });

  it("renders JSON content through the JSON editor, pretty-printed", () => {
    const artifact = makeArtifact({
      id: "art-json", type: "JSON", content: '{"a":1,"b":[2,3]}',
    });
    renderWithProviders(<ArtifactsPanel artifacts={[artifact]} />);
    fireEvent.click(screen.getByTestId("artifact-toggle-art-json"));

    const editor = screen.getByTestId("artifact-json-art-json");
    expect(editor.textContent).toContain('"a": 1');
  });

  it("falls back to the raw string when JSON content does not actually parse", () => {
    const artifact = makeArtifact({ id: "art-bad", type: "JSON", content: "{not valid json" });
    renderWithProviders(<ArtifactsPanel artifacts={[artifact]} />);
    fireEvent.click(screen.getByTestId("artifact-toggle-art-bad"));

    expect(screen.getByTestId("artifact-json-art-bad")).toHaveTextContent("{not valid json");
  });

  it("shows a FINAL badge distinctly from DRAFT", () => {
    renderWithProviders(<ArtifactsPanel artifacts={[makeArtifact({ status: "FINAL" })]} />);
    expect(screen.getByTestId("artifact-art-1")).toHaveTextContent("Final");
  });
});
