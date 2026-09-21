import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ResourceTypeBadge } from "@/components/shared/resource-type-badge";

describe("ResourceTypeBadge", () => {
  it("renders agent type with purple color", () => {
    const { container } = renderWithProviders(
      <ResourceTypeBadge type="agent" />
    );
    expect(screen.getByText("agent")).toBeInTheDocument();
    const span = container.querySelector(".bg-purple-500\\/10");
    expect(span).not.toBeNull();
  });

  it("renders workflow type with blue color", () => {
    const { container } = renderWithProviders(
      <ResourceTypeBadge type="workflow" />
    );
    expect(screen.getByText("workflow")).toBeInTheDocument();
    const span = container.querySelector(".bg-blue-500\\/10");
    expect(span).not.toBeNull();
  });

  it("renders rules type with amber color", () => {
    const { container } = renderWithProviders(
      <ResourceTypeBadge type="rules" />
    );
    expect(screen.getByText("rules")).toBeInTheDocument();
    const span = container.querySelector(".bg-amber-500\\/10");
    expect(span).not.toBeNull();
  });

  it("renders llm type with pink color", () => {
    const { container } = renderWithProviders(
      <ResourceTypeBadge type="llm" />
    );
    expect(screen.getByText("llm")).toBeInTheDocument();
    const span = container.querySelector(".bg-pink-500\\/10");
    expect(span).not.toBeNull();
  });

  it("renders mcpcalls type with indigo color", () => {
    const { container } = renderWithProviders(
      <ResourceTypeBadge type="mcpcalls" />
    );
    expect(screen.getByText("mcpcalls")).toBeInTheDocument();
    const span = container.querySelector(".bg-indigo-500\\/10");
    expect(span).not.toBeNull();
  });

  it("strips .json extension from type", () => {
    renderWithProviders(
      <ResourceTypeBadge type="behavior.json" />
    );
    expect(screen.getByText("behavior")).toBeInTheDocument();
    expect(screen.queryByText("behavior.json")).not.toBeInTheDocument();
  });

  it("renders unknown type with default secondary styling", () => {
    const { container } = renderWithProviders(
      <ResourceTypeBadge type="unknown-thing" />
    );
    expect(screen.getByText("unknown-thing")).toBeInTheDocument();
    const span = container.querySelector(".bg-secondary");
    expect(span).not.toBeNull();
  });

  it("renders output type with cyan color", () => {
    const { container } = renderWithProviders(
      <ResourceTypeBadge type="output" />
    );
    expect(screen.getByText("output")).toBeInTheDocument();
    const span = container.querySelector(".bg-cyan-500\\/10");
    expect(span).not.toBeNull();
  });

  it("renders dictionary type with teal color", () => {
    const { container } = renderWithProviders(
      <ResourceTypeBadge type="dictionary" />
    );
    expect(screen.getByText("dictionary")).toBeInTheDocument();
    const span = container.querySelector(".bg-teal-500\\/10");
    expect(span).not.toBeNull();
  });

  it("renders rag type with violet color", () => {
    const { container } = renderWithProviders(
      <ResourceTypeBadge type="rag" />
    );
    expect(screen.getByText("rag")).toBeInTheDocument();
    const span = container.querySelector(".bg-violet-500\\/10");
    expect(span).not.toBeNull();
  });

  it("renders snippets type with rose color", () => {
    const { container } = renderWithProviders(
      <ResourceTypeBadge type="snippets" />
    );
    expect(screen.getByText("snippets")).toBeInTheDocument();
    const span = container.querySelector(".bg-rose-500\\/10");
    expect(span).not.toBeNull();
  });
});
