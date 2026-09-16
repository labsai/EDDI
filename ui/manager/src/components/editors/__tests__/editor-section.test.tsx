import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { EditorSection } from "@/components/editors/editor-section";
import { Sparkles } from "lucide-react";

describe("EditorSection", () => {
  it("renders label text", () => {
    renderWithProviders(
      <EditorSection label="Test Section">
        <p>Content</p>
      </EditorSection>
    );
    expect(screen.getByText("Test Section")).toBeInTheDocument();
  });

  it("shows children by default (defaultOpen=true)", () => {
    renderWithProviders(
      <EditorSection label="Open Section">
        <p>Visible Content</p>
      </EditorSection>
    );
    expect(screen.getByText("Visible Content")).toBeInTheDocument();
  });

  it("hides children when defaultOpen=false", () => {
    renderWithProviders(
      <EditorSection label="Closed Section" defaultOpen={false}>
        <p>Hidden Content</p>
      </EditorSection>
    );
    expect(screen.queryByText("Hidden Content")).not.toBeInTheDocument();
  });

  it("toggles content on click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <EditorSection label="Toggle Section">
        <p>Toggleable Content</p>
      </EditorSection>
    );

    expect(screen.getByText("Toggleable Content")).toBeInTheDocument();
    await user.click(screen.getByText("Toggle Section"));
    expect(
      screen.queryByText("Toggleable Content")
    ).not.toBeInTheDocument();
    await user.click(screen.getByText("Toggle Section"));
    expect(screen.getByText("Toggleable Content")).toBeInTheDocument();
  });

  it("renders icon when provided", () => {
    renderWithProviders(
      <EditorSection label="Icon Section" icon={Sparkles}>
        <p>Content</p>
      </EditorSection>
    );
    // Icon renders as SVG
    const svgs = document.querySelectorAll("svg");
    // ChevronDown + icon
    expect(svgs.length).toBeGreaterThanOrEqual(2);
  });

  it("renders card variant with border and heading", () => {
    renderWithProviders(
      <EditorSection label="Card Section" variant="card">
        <p>Card Content</p>
      </EditorSection>
    );
    expect(screen.getByText("Card Section")).toBeInTheDocument();
    expect(screen.getByText("Card Content")).toBeInTheDocument();
    // Card variant renders h2
    expect(
      screen.getByRole("heading", { level: 2, name: "Card Section" })
    ).toBeInTheDocument();
  });

  it("toggles card variant content on click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <EditorSection label="Card Toggle" variant="card">
        <p>Card Content</p>
      </EditorSection>
    );

    expect(screen.getByText("Card Content")).toBeInTheDocument();
    await user.click(screen.getByText("Card Toggle"));
    expect(screen.queryByText("Card Content")).not.toBeInTheDocument();
  });

  it("opens closed card variant on click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <EditorSection label="Closed Card" variant="card" defaultOpen={false}>
        <p>Initially Hidden</p>
      </EditorSection>
    );

    expect(screen.queryByText("Initially Hidden")).not.toBeInTheDocument();
    await user.click(screen.getByText("Closed Card"));
    expect(screen.getByText("Initially Hidden")).toBeInTheDocument();
  });

  it("renders icon with custom accent color", () => {
    renderWithProviders(
      <EditorSection label="Accented" icon={Sparkles} accent="text-emerald-500">
        <p>Content</p>
      </EditorSection>
    );
    expect(screen.getByText("Accented")).toBeInTheDocument();
  });
});
