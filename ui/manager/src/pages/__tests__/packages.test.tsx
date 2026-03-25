import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { WorkflowsPage } from "@/pages/packages";

describe("WorkflowsPage", () => {
  it("renders page heading", () => {
    renderWithProviders(<WorkflowsPage />);
    expect(screen.getByText("Workflows")).toBeInTheDocument();
  });

  it("renders search input", () => {
    renderWithProviders(<WorkflowsPage />);
    expect(screen.getByTestId("workflow-search")).toBeInTheDocument();
  });

  it("renders create package button", () => {
    renderWithProviders(<WorkflowsPage />);
    expect(screen.getByTestId("create-workflow-btn")).toBeInTheDocument();
  });

  it("shows subtitle text", () => {
    renderWithProviders(<WorkflowsPage />);
    expect(
      screen.getByText("Configure agent workflows and extensions")
    ).toBeInTheDocument();
  });
});
