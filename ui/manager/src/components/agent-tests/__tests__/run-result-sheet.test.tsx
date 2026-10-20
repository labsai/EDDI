import { describe, it, expect } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { RunResultBody } from "@/components/agent-tests/run-result-sheet";
import type { AgentTestRun } from "@/lib/api/agent-tests";
import { buildMockRuns } from "@/test/mocks/agent-test-fixtures";

const [failing, passing, aborted, errored, ceiling] = buildMockRuns("agent1") as unknown as AgentTestRun[];

describe("RunResultBody", () => {
  it("shows a passing run with every case green and no notice", () => {
    renderWithProviders(<RunResultBody run={passing!} />);
    expect(screen.getByTestId("run-status-PASSED")).toBeInTheDocument();
    expect(screen.getByTestId("stat-cases")).toHaveTextContent("2/2");
    expect(screen.queryByTestId("expectation-failure")).not.toBeInTheDocument();
    expect(screen.queryByTestId("run-notice-aborted")).not.toBeInTheDocument();
  });

  it("opens the failing case and shows expected next to actual", () => {
    renderWithProviders(<RunResultBody run={failing!} />);
    const failures = screen.getAllByTestId("expectation-failure");
    expect(failures).toHaveLength(4);
    const toolCalled = failures.find((f) => f.dataset.failure === "toolCalled")!;
    expect(within(toolCalled).getByTestId("failure-expected")).toHaveTextContent('"tool": "getAvailability"');
    expect(within(toolCalled).getByTestId("failure-actual")).toHaveTextContent("searchHotels");
    // A property that was never set reads "(nothing)", not "null".
    const prop = failures.find((f) => f.dataset.failure === "properties.city")!;
    expect(within(prop).getByTestId("failure-actual")).toHaveTextContent("(nothing)");
  });

  it("highlights the unmocked tool call on the turn and the attempt", () => {
    renderWithProviders(<RunResultBody run={failing!} />);
    expect(screen.getByTestId("attempt-unmocked")).toHaveTextContent("searchHotels");
    const marked = screen.getByTestId("turn-tool-calls").querySelector("[data-unmocked]");
    expect(marked).toHaveTextContent("searchHotels");
  });

  it("keeps a passing case collapsed until opened", async () => {
    renderWithProviders(<RunResultBody run={failing!} />);
    const cases = screen.getAllByTestId("result-case");
    const passingCase = cases.find((c) => c.dataset.passed === "true")!;
    expect(within(passingCase).queryByTestId("result-attempt")).not.toBeInTheDocument();
    expect(within(passingCase).getByTestId("case-pass-count")).toHaveTextContent("5/5");
    await userEvent.click(within(passingCase).getAllByRole("button")[0]!);
    expect(within(passingCase).getAllByTestId("result-attempt")).toHaveLength(5);
  });

  it("explains ABORTED as not judging the agent and shows the reason", () => {
    renderWithProviders(<RunResultBody run={aborted!} />);
    expect(screen.getByTestId("run-notice-aborted")).toHaveTextContent("not judged");
    expect(screen.getByTestId("run-notice-reason")).toHaveTextContent("stopped before the run finished");
  });

  it("explains ERROR with its reason", () => {
    renderWithProviders(<RunResultBody run={errored!} />);
    expect(screen.getByTestId("run-notice-error")).toBeInTheDocument();
    expect(screen.getByTestId("run-notice-reason")).toHaveTextContent("test environment");
  });

  it("explains a cost-ceiling stop and shows the attempt's own stop reason", () => {
    renderWithProviders(<RunResultBody run={ceiling!} />);
    expect(screen.getByTestId("run-notice-ceiling")).toBeInTheDocument();
    expect(screen.getByTestId("attempt-error")).toHaveTextContent("cost ceiling");
  });

  it("says a RUNNING run is in progress", () => {
    renderWithProviders(<RunResultBody run={{ ...passing!, status: "RUNNING", cases: [] }} />);
    expect(screen.getByTestId("run-in-progress")).toBeInTheDocument();
  });
});
