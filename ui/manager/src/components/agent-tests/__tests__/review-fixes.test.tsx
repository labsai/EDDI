import { describe, it, expect, vi, afterEach } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { TestSuiteEditor } from "@/components/editors/test-suite-editor";
import { AgentTestsSection } from "@/components/agent-tests/agent-tests-section";
import { RunResultBody } from "@/components/agent-tests/run-result-sheet";
import { server } from "@/test/mocks/server";
import { buildMockRuns } from "@/test/mocks/agent-test-fixtures";
import type { AgentTestRun, AgentTestSuite } from "@/lib/api/agent-tests";

const runs = buildMockRuns("agent1") as unknown as AgentTestRun[];

afterEach(() => server.resetHandlers());

describe("OptionalNumberField bounds", () => {
  it("does not commit a repetition count above 20", async () => {
    const onChange = vi.fn();
    const data: AgentTestSuite = { cases: [{ name: "c", turns: [{ input: "hi" }] }] };
    renderWithProviders(<TestSuiteEditor data={data} onChange={onChange} />);
    const field = screen.getByTestId("case-repetitions");
    fireEvent.change(field, { target: { value: "21" } });
    expect(onChange).not.toHaveBeenCalled();
    expect(field).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("alert")).toBeInTheDocument();
    fireEvent.change(field, { target: { value: "5" } });
    expect(onChange).toHaveBeenLastCalledWith({ cases: [{ name: "c", turns: [{ input: "hi" }], repetitions: 5 }] });
    expect(field).not.toHaveAttribute("aria-invalid");
  });
});

describe("new property row", () => {
  it("starts as an exists check, not an empty-string equality", async () => {
    const onChange = vi.fn();
    const data: AgentTestSuite = { cases: [{ name: "c", turns: [{ input: "hi" }] }] };
    renderWithProviders(<TestSuiteEditor data={data} onChange={onChange} />);
    await userEvent.click(screen.getByTestId("turn-expect-toggle"));
    await userEvent.click(screen.getByTestId("expect-add-property"));
    const saved = onChange.mock.calls[onChange.mock.calls.length - 1]![0] as AgentTestSuite;
    expect(saved.cases?.[0]?.turns?.[0]?.expect).toEqual({ properties: { property_1: { exists: true } } });
  });
});

describe("property names", () => {
  it("refuses a rename onto another row's name instead of dropping a row", async () => {
    const onChange = vi.fn();
    const data: AgentTestSuite = {
      cases: [
        {
          name: "c",
          turns: [{ input: "hi", expect: { properties: { city: { equals: "Vienna" }, country: { equals: "AT" } } } }],
        },
      ],
    };
    renderWithProviders(<TestSuiteEditor data={data} onChange={onChange} />);
    await userEvent.click(screen.getByTestId("turn-expect-toggle"));
    const [, second] = screen.getAllByLabelText("Property name");
    await userEvent.clear(second!);
    await userEvent.type(second!, "city");
    await userEvent.tab();
    expect(onChange).not.toHaveBeenCalled();
    expect(second).toHaveValue("country");
  });
});

describe("AgentTestsSection", () => {
  it("sends the agent version the page is on and the run reports it", async () => {
    let body: { agentVersion?: number } = {};
    server.use(
      http.post("*/agenttests/runs", async ({ request }) => {
        body = (await request.json()) as typeof body;
        return HttpResponse.json({ id: "run-v", status: "RUNNING", agentId: "agent1", suiteId: "res1", agentVersion: 3 }, { status: 202 });
      })
    );
    renderWithProviders(<AgentTestsSection agentId="agent1" agentVersion={3} />);
    await userEvent.click(screen.getByRole("button", { name: /golden conversations/i }));
    await waitFor(() => expect(screen.getByTestId("agent-test-run-btn")).toBeEnabled());
    await userEvent.click(screen.getByTestId("agent-test-run-btn"));
    await waitFor(() => expect(body.agentVersion).toBe(3));
  });

  it("announces the latest status in a polite live region", async () => {
    renderWithProviders(<AgentTestsSection agentId="agent1" agentVersion={7} />);
    await waitFor(() => expect(screen.getByTestId("agent-tests-live")).toHaveTextContent("Failed"));
  });
});

describe("RunResultBody live status", () => {
  it("announces PASSED itself, and leaves ABORTED to its notice", () => {
    const { container, rerender } = renderWithProviders(<RunResultBody run={runs[1]!} />);
    expect(container.querySelector('[role="status"] [data-testid="run-status-PASSED"]')).not.toBeNull();
    rerender(<RunResultBody run={runs[2]!} />);
    expect(container.querySelector('[role="status"] [data-testid="run-status-ABORTED"]')).toBeNull();
    expect(screen.getByTestId("run-notice-aborted")).toHaveAttribute("role", "status");
  });
});
