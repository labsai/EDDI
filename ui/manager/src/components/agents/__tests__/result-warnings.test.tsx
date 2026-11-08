import { describe, it, expect, vi, afterEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ResultWarnings } from "@/components/agents/result-warnings";

describe("ResultWarnings", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  // A batch sync that first-promotes two source agents of the same name gets the
  // same text twice: the import's reason does not name the agent, and the Sync
  // page prefixes only the name. Keyed by text, React sees two children with one
  // key, warns, and may drop or merge one of them on the next render.
  it("lists two identical warnings as two entries, without a duplicate-key warning", () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    const warning = "Support Agent: signInterAgentMessages was turned off: the imported agent is new and has no signing key yet.";

    renderWithProviders(<ResultWarnings warnings={[warning, warning]} />);

    expect(screen.getByTestId("result-warnings").querySelectorAll("li")).toHaveLength(2);
    const keyWarnings = consoleError.mock.calls.filter((call) => String(call[0]).includes("same key"));
    expect(keyWarnings).toEqual([]);
  });

  it("renders nothing when there is nothing to say", () => {
    renderWithProviders(<ResultWarnings warnings={[]} />);
    expect(screen.queryByTestId("result-warnings")).not.toBeInTheDocument();
  });
});
