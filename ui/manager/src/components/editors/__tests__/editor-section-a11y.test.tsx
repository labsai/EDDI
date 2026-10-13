import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EditorSection } from "@/components/editors/editor-section";
import { SessionManagementSection } from "@/components/editors/agent-config-sections";
import { renderWithProviders } from "@/test/test-utils";

describe("EditorSection — accessibility", () => {
  it("card variant: the heading wraps the button, which reports its state", async () => {
    render(
      <EditorSection label="Security" variant="card" defaultOpen={false}>
        <p>body</p>
      </EditorSection>,
    );
    const button = screen.getByRole("button", { name: "Security" });
    expect(button.closest("h2")).not.toBeNull();
    expect(button.querySelector("h2")).toBeNull();
    expect(button).toHaveAttribute("aria-expanded", "false");

    await userEvent.setup().click(button);
    expect(button).toHaveAttribute("aria-expanded", "true");
    const panel = document.getElementById(button.getAttribute("aria-controls")!);
    expect(panel).toHaveTextContent("body");
  });

  it("inline variant reports its state too", async () => {
    render(
      <EditorSection label="Advanced" defaultOpen>
        <p>body</p>
      </EditorSection>,
    );
    const button = screen.getByRole("button", { name: "Advanced" });
    expect(button).toHaveAttribute("aria-expanded", "true");
    await userEvent.setup().click(button);
    expect(button).toHaveAttribute("aria-expanded", "false");
  });
});

describe("SessionManagementSection — forking note", () => {
  it("shows the endpoint path literally instead of an unfilled variable", async () => {
    renderWithProviders(
      <SessionManagementSection
        agent={{ workflows: [], channels: [] } as never}
        agentId="a1"
        version={1}
      />,
    );
    await userEvent.setup().click(screen.getByRole("button", { name: /Session Management/ }));
    const note = await screen.findByText(/Session forking endpoint/);
    // i18next reads {{id}} as an interpolation and rendered it as "".
    expect(note).toHaveTextContent("POST /v6/conversations/:id/fork");
    expect(note.textContent).not.toContain("{{");
    // The disabled control no longer shows a dead "Max Forks" field.
    expect(screen.queryByText("Max Forks")).not.toBeInTheDocument();
  });
});
