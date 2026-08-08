import { describe, it, expect, beforeEach, vi, afterEach, afterAll, beforeAll } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderPage } from "@/test/test-utils";
import { WorkforceSettings } from "../workforce-settings";
import { setupServer } from "msw/node";
import { handlers } from "@/test/mocks/handlers";
import { http, HttpResponse } from "msw";

const server = setupServer(...handlers);

beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function render() {
  renderPage(
    "/workforce/grp1/settings?version=1",
    <WorkforceSettings />,
    "/workforce/:boardId/settings",
  );
}

/** Capture the config body of the PUT the save button issues. */
function capturePut() {
  const seen: { body?: Record<string, unknown> } = {};
  server.use(
    http.put("*/groupstore/groups/:id", async ({ request }) => {
      seen.body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({ location: "/groupstore/groups/grp1?version=2" });
    }),
  );
  return seen;
}

async function expandSection(user: ReturnType<typeof userEvent.setup>, name: RegExp) {
  await user.click(await screen.findByRole("button", { name }));
}

describe("WorkforceSettings — group collaboration parity", () => {
  beforeEach(() => vi.clearAllMocks());

  describe("cost ceiling (I1)", () => {
    it("shows the stored ceiling and its policy", async () => {
      const user = userEvent.setup();
      render();
      await screen.findByDisplayValue("Product Review Panel");
      await expandSection(user, /Protocol & Resilience/i);

      expect(screen.getByTestId("settings-max-cost")).toHaveValue(2.5);
      expect(screen.getByLabelText(/When the ceiling is hit/i)).toHaveValue("SYNTHESIZE_NOW");
    });

    it("disables the policy control when there is no ceiling", async () => {
      const user = userEvent.setup();
      render();
      await screen.findByDisplayValue("Product Review Panel");
      await expandSection(user, /Protocol & Resilience/i);

      await user.clear(screen.getByTestId("settings-max-cost"));
      expect(screen.getByLabelText(/When the ceiling is hit/i)).toBeDisabled();
    });

    /**
     * The backend coerces a non-positive ceiling to "unlimited" with a log
     * warning — i.e. saving 0 means the exact opposite of what it reads as. The
     * editor has to refuse it rather than let that happen silently.
     */
    it("refuses to save a zero ceiling", async () => {
      const user = userEvent.setup();
      const put = capturePut();
      render();
      await screen.findByDisplayValue("Product Review Panel");
      await expandSection(user, /Protocol & Resilience/i);

      const field = screen.getByTestId("settings-max-cost");
      await user.clear(field);
      await user.type(field, "0");
      await user.click(screen.getByRole("button", { name: /Save Changes/i }));

      await waitFor(() => expect(screen.getByTestId("settings-max-cost")).toHaveValue(0));
      expect(put.body).toBeUndefined();
    });

    it("saves a positive ceiling", async () => {
      const user = userEvent.setup();
      const put = capturePut();
      render();
      await screen.findByDisplayValue("Product Review Panel");
      await expandSection(user, /Protocol & Resilience/i);

      const field = screen.getByTestId("settings-max-cost");
      await user.clear(field);
      await user.type(field, "7.5");
      await user.click(screen.getByRole("button", { name: /Save Changes/i }));

      await waitFor(() => expect(put.body).toBeDefined());
      expect((put.body!.protocol as Record<string, unknown>).maxCostPerDiscussion).toBe(7.5);
    });
  });

  describe("deliberation quality (I4 / I5)", () => {
    it("reflects the stored minority-report and agent-filed-task settings", async () => {
      const user = userEvent.setup();
      render();
      await screen.findByDisplayValue("Product Review Panel");
      await expandSection(user, /Deliberation Quality/i);

      expect(screen.getByTestId("settings-record-dissents")).toHaveAttribute("aria-checked", "true");
      expect(screen.getByTestId("settings-agent-filed-tasks")).toHaveAttribute("aria-checked", "true");
      expect(screen.getByLabelText(/Max filed tasks \/ discussion/i)).toHaveValue(20);
      expect(screen.getByLabelText(/Max filed tasks \/ turn/i)).toHaveValue(3);
    });

    it("hides the caps while agent-filed tasks are off", async () => {
      const user = userEvent.setup();
      render();
      await screen.findByDisplayValue("Product Review Panel");
      await expandSection(user, /Deliberation Quality/i);

      await user.click(screen.getByTestId("settings-agent-filed-tasks"));
      expect(screen.queryByLabelText(/Max filed tasks \/ turn/i)).not.toBeInTheDocument();
    });

    it("persists both blocks", async () => {
      const user = userEvent.setup();
      const put = capturePut();
      render();
      await screen.findByDisplayValue("Product Review Panel");
      await expandSection(user, /Deliberation Quality/i);

      await user.click(screen.getByTestId("settings-record-dissents"));
      // fireEvent-style single change: these numeric fields ignore an
      // unparseable value (the pattern the whole page uses), so clearing one
      // leaves the previous number in the controlled value and a subsequent
      // `type` would append to it rather than replace it.
      fireEvent.change(screen.getByLabelText(/Max filed tasks \/ turn/i), { target: { value: "5" } });
      await user.click(screen.getByRole("button", { name: /Save Changes/i }));

      await waitFor(() => expect(put.body).toBeDefined());
      expect(put.body!.recordDissents).toBe(false);
      expect(put.body!.taskListConfig).toMatchObject({
        allowAgentTaskCreation: true, maxPerTurn: 5,
      });
    });
  });

  describe("dynamic agents (I7 / F18)", () => {
    /**
     * `LifecyclePolicy` is the one group enum with Jackson's @JsonValue, so the
     * backend writes "keep-deployed". Before normalization the select matched no
     * option and silently showed the wrong policy.
     */
    it("selects the stored lifecycle policy despite its wire format", async () => {
      const user = userEvent.setup();
      render();
      await screen.findByDisplayValue("Product Review Panel");
      await expandSection(user, /Dynamic Agents/i);
      await user.click(screen.getAllByRole("switch")[0]!);

      expect(screen.getByLabelText(/Lifecycle Policy/i)).toHaveValue("KEEP_DEPLOYED");
    });

    it("shows delegation guardrails only while delegation is allowed", async () => {
      const user = userEvent.setup();
      render();
      await screen.findByDisplayValue("Product Review Panel");
      await expandSection(user, /Dynamic Agents/i);
      await user.click(screen.getAllByRole("switch")[0]!);

      expect(screen.getByTestId("settings-delegation-depth")).toHaveValue(3);
      expect(screen.getByTestId("settings-delegation-timeout")).toHaveValue(120);

      await user.click(screen.getByRole("switch", { name: /Allow Delegation/i }));
      expect(screen.queryByTestId("settings-delegation-depth")).not.toBeInTheDocument();
    });

    it("saves the delegation allow-list as a trimmed list", async () => {
      const user = userEvent.setup();
      const put = capturePut();
      render();
      await screen.findByDisplayValue("Product Review Panel");
      await expandSection(user, /Dynamic Agents/i);
      await user.click(screen.getAllByRole("switch")[0]!);

      await user.type(screen.getByTestId("settings-delegation-targets"), "agent-a ,  agent-b , ");
      await user.click(screen.getByRole("button", { name: /Save Changes/i }));

      await waitFor(() => expect(put.body).toBeDefined());
      const dynamic = put.body!.dynamicAgents as Record<string, unknown>;
      expect(dynamic.allowedDelegationTargets).toEqual(["agent-a", "agent-b"]);
      // …and the canonical constant, not the wire form it was read as.
      expect(dynamic.lifecyclePolicy).toBe("KEEP_DEPLOYED");
    });
  });

  describe("moderator-less phases", () => {
    it("says nothing while a moderator is named", async () => {
      render();
      await screen.findByDisplayValue("Product Review Panel");
      expect(screen.queryByTestId("moderatorless-phase-warning")).not.toBeInTheDocument();
    });

    it("warns as soon as the moderator is cleared", async () => {
      const user = userEvent.setup();
      render();
      await screen.findByDisplayValue("Product Review Panel");

      await user.selectOptions(screen.getByLabelText(/^Moderator$/i), "");

      const warning = await screen.findByTestId("moderatorless-phase-warning");
      expect(warning).toHaveTextContent("Synthesis");
    });
  });

  it("caps max rounds at the backend's own ceiling rather than a lower one", async () => {
    render();
    await screen.findByDisplayValue("Product Review Panel");
    expect(screen.getByLabelText(/Max Rounds/i)).toHaveAttribute("max", "50");
  });
});
