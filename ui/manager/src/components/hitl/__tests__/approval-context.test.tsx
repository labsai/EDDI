import { describe, it, expect, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { ApprovalContext } from "@/components/hitl/approval-context";
import { ApprovalBanner } from "@/components/hitl/approval-banner";
import type { ApprovalContextView } from "@eddi/app-kit/lib/api/hitl";

function view(overrides: Partial<ApprovalContextView> = {}): ApprovalContextView {
  return {
    items: [
      { label: "Draft", text: "Acme cuts onboarding to 3 days.", source: "agent" },
      { label: "Sources", text: "1. Case study — https://acme.example/cs", source: "agent" },
    ],
    digest: "abc",
    ...overrides,
  };
}

describe("ApprovalContext", () => {
  it("renders nothing without context", () => {
    const { container } = renderWithProviders(<ApprovalContext context={null} />);
    expect(container.querySelector('[data-testid="approval-context"]')).toBeNull();
    const { container: empty } = renderWithProviders(<ApprovalContext context={{ items: [] }} />);
    expect(empty.querySelector('[data-testid="approval-context"]')).toBeNull();
  });

  it("shows each block's label and text, and says the text is unverified", () => {
    renderWithProviders(<ApprovalContext context={view()} />);

    const items = screen.getAllByTestId("approval-context-item");
    expect(items).toHaveLength(2);
    const first = items[0]!;
    expect(within(first).getByRole("heading").textContent).toBe("Draft");
    expect(within(first).getByTestId("approval-context-text").textContent).toBe("Acme cuts onboarding to 3 days.");
    expect(screen.getByTestId("approval-context").textContent).toMatch(/not verified by EDDI/);
  });

  it("renders markup, links and mentions as inert text — never as HTML or markdown", () => {
    const hostile = '<img src=x onerror="alert(1)"> <a href="https://evil.example">pay</a> **bold** <script>alert(2)</script> @channel';
    const { container } = renderWithProviders(<ApprovalContext context={view({ items: [{ label: "Draft", text: hostile }] })} />);

    expect(screen.getByTestId("approval-context-text").textContent).toBe(hostile);
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("a")).toBeNull();
    expect(container.querySelector("script")).toBeNull();
    expect(container.querySelector("strong")).toBeNull();
  });

  it("keeps line breaks (pre-wrap) rather than collapsing them", () => {
    renderWithProviders(<ApprovalContext context={view({ items: [{ label: "Claims", text: "• one\n• two" }] })} />);
    const el = screen.getByTestId("approval-context-text");
    expect(el.textContent).toBe("• one\n• two");
    expect(el.className).toContain("whitespace-pre-wrap");
  });

  it("shows an error block as a notice instead of an empty value", () => {
    renderWithProviders(
      <ApprovalContext
        context={view({
          items: [
            { label: "Claims", text: "", error: "render_failed" },
            { label: "Sources", text: "", error: "too_large" },
          ],
        })}
      />,
    );

    const errors = screen.getAllByTestId("approval-context-error");
    expect(errors.map((e) => e.textContent)).toEqual(["This block could not be rendered.", "This block was too large to render."]);
    expect(screen.queryByTestId("approval-context-text")).toBeNull();
  });

  it("flags a cut-short block and a context that left blocks out", () => {
    renderWithProviders(
      <ApprovalContext context={view({ truncated: true, items: [{ label: "Draft", text: "long…", truncated: true }] })} />,
    );
    expect(screen.getByText("Cut short.")).toBeTruthy();
    expect(screen.getByTestId("approval-context-truncated")).toBeTruthy();
  });
});

describe("ApprovalBanner — approval context", () => {
  it("mounts the context above the decision on a rule pause", () => {
    renderWithProviders(
      <ApprovalBanner surface="regular" pauseReason="Needs sign-off" approvalContext={view()} onDecide={vi.fn()} />,
    );
    const banner = screen.getByTestId("approval-banner");
    expect(within(banner).getByTestId("approval-context")).toBeTruthy();
    expect(within(banner).getAllByTestId("approval-context-item")).toHaveLength(2);
  });

  it("mounts it on a group approval too", () => {
    renderWithProviders(<ApprovalBanner surface="group" pausedPhaseName="Synthesis" approvalContext={view()} onDecide={vi.fn()} />);
    expect(screen.getByTestId("approval-context")).toBeTruthy();
  });

  it("renders no context section when the pause has none", () => {
    renderWithProviders(<ApprovalBanner surface="regular" approvalContext={null} onDecide={vi.fn()} />);
    expect(screen.queryByTestId("approval-context")).toBeNull();
    renderWithProviders(<ApprovalBanner surface="regular" onDecide={vi.fn()} />);
    expect(screen.queryByTestId("approval-context")).toBeNull();
  });
});
