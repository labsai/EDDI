import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { PackagesPage } from "@/pages/packages";

describe("PackagesPage", () => {
  it("renders page heading", () => {
    renderWithProviders(<PackagesPage />);
    expect(screen.getByText("Packages")).toBeInTheDocument();
  });

  it("renders search input", () => {
    renderWithProviders(<PackagesPage />);
    expect(screen.getByTestId("package-search")).toBeInTheDocument();
  });

  it("renders create package button", () => {
    renderWithProviders(<PackagesPage />);
    expect(screen.getByTestId("create-package-btn")).toBeInTheDocument();
  });

  it("shows subtitle text", () => {
    renderWithProviders(<PackagesPage />);
    expect(
      screen.getByText("Configure bot packages and extensions")
    ).toBeInTheDocument();
  });
});
