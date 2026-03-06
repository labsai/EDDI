import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { Sidebar } from "@/components/layout/sidebar";

describe("Sidebar", () => {
  it("renders all navigation items", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    expect(screen.getByText("Dashboard")).toBeInTheDocument();
    expect(screen.getByText("Bots")).toBeInTheDocument();
    expect(screen.getByText("Packages")).toBeInTheDocument();
    expect(screen.getByText("Conversations")).toBeInTheDocument();
    expect(screen.getByText("Resources")).toBeInTheDocument();
  });

  it("hides labels when collapsed", () => {
    renderWithProviders(
      <Sidebar collapsed={true} onToggle={() => {}} />
    );

    expect(screen.queryByText("Dashboard")).not.toBeInTheDocument();
    expect(screen.queryByText("Bots")).not.toBeInTheDocument();
  });

  it("renders collapse toggle button", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    expect(screen.getByTestId("sidebar-toggle")).toBeInTheDocument();
  });

  it("shows EDDI logo", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    expect(screen.getByText("EDDI")).toBeInTheDocument();
  });

  it("shows abbreviated logo when collapsed", () => {
    renderWithProviders(
      <Sidebar collapsed={true} onToggle={() => {}} />
    );

    expect(screen.getByText("E")).toBeInTheDocument();
    expect(screen.queryByText("EDDI")).not.toBeInTheDocument();
  });
});
