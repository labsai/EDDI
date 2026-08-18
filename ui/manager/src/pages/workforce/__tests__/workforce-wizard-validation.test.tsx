import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderPage } from "@/test/test-utils";
import { WorkforceWizard } from "@/pages/workforce/workforce-wizard";

/**
 * Creating a Workforce provisions one agent per advisor through
 * `POST /administration/agents/setup`. `AgentSetupService.setupAgent` rejects,
 * in order: a blank name, a blank `systemPrompt`, and — for every provider
 * that is not local, including the anthropic default a blank provider resolves
 * to — a missing API key. Each came back as 400 `{"error": "…"}`.
 *
 * Three things made that unreadable:
 *   1. The system prompt sits inside the member card's collapsed half and
 *      carried no required marker, and there was nowhere to enter an API key
 *      once for the team — so the wizard happily walked to the Review step and
 *      only failed once it had started provisioning.
 *   2. The catch block used `err instanceof Error ? err.message : String(err)`,
 *      and `ApiClient` threw a plain object — so every backend rejection
 *      reached the user as "[object Object]" while the real text sat in the
 *      server log.
 *   3. "Try Again" remembered which rows had finished but not the ids they
 *      were given, so a retry built the group with `agentId: ""` for them.
 */

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

type User = ReturnType<typeof userEvent.setup>;

function renderWizard(template = "custom") {
  return renderPage(`/workforce/new?template=${template}`, <WorkforceWizard />, "/workforce/new");
}

const next = () => screen.getByRole("button", { name: /^next$/i });
const back = () => screen.getByRole("button", { name: /^back$/i });

/** Advance from the template step to the team step. */
async function gotoTeamStep(user: User) {
  await waitFor(() => expect(next()).toBeEnabled());
  await user.click(next());
  return await screen.findByLabelText(/workforce name/i);
}

/** Custom template: board name + both advisor names, nothing else. */
async function fillNamesOnly(user: User) {
  const boardName = await gotoTeamStep(user);
  await user.type(boardName, "Product Strategy Board");
  const nameFields = screen.getAllByLabelText(/advisor name/i);
  await user.type(nameFields[0]!, "Ana");
  await user.type(nameFields[1]!, "Bo");
}

/** Click Next (which fails and opens the cards), then fill both prompts. */
async function fillPrompts(user: User) {
  await user.click(next());
  const prompts = await screen.findAllByLabelText(/personality & expertise/i);
  await user.type(prompts[0]!, "You are a market analyst.");
  await user.type(prompts[1]!, "You are a risk officer.");
}

async function setDefaultKey(user: User, key = "sk-test") {
  await user.type(within(screen.getByTestId("apikey-defaults")).getByTestId("apikey-defaults-picker-input"), key);
}

/** Everything a custom team needs, ending on the Review step. */
async function completeCustomTeam(user: User) {
  await fillNamesOnly(user);
  await fillPrompts(user);
  await setDefaultKey(user);
  await user.click(next());
  return await screen.findByRole("button", { name: /create workforce/i });
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("Workforce wizard — the team step says what the backend will refuse", () => {
  it("blocks on a missing system prompt and names it", async () => {
    const user = userEvent.setup();
    renderWizard();
    await fillNamesOnly(user);

    await user.click(next());

    // Still on the team step — the board name field is the anchor.
    expect(screen.getByLabelText(/workforce name/i)).toBeInTheDocument();
    const errors = await screen.findAllByRole("alert");
    expect(errors).toHaveLength(2);
    for (const error of errors) expect(error).toHaveTextContent(/system prompt is required/i);
  });

  it("holds the flagged card open and moves focus to the field", async () => {
    const user = userEvent.setup();
    renderWizard();
    await fillNamesOnly(user);

    // The prompt lives behind the collapse toggle and is not rendered while
    // the card is shut.
    expect(screen.queryByLabelText(/personality & expertise/i)).toBeNull();

    await user.click(next());

    const prompts = await screen.findAllByLabelText(/personality & expertise/i);
    expect(prompts).toHaveLength(2);
    expect(prompts[0]).toHaveAttribute("aria-invalid", "true");
    // Focus lands on the first problem, not on the Next button that was clicked.
    expect(document.activeElement).toBe(prompts[0]);
    // …and the card cannot be collapsed out from under it.
    const collapse = screen.getAllByRole("button", { name: /collapse/i });
    expect(collapse[0]).toBeDisabled();
  });

  it("then blocks on the API key the default (anthropic) provider needs", async () => {
    const user = userEvent.setup();
    renderWizard();
    await fillNamesOnly(user);
    await fillPrompts(user);

    // Validation is live: as soon as the prompt is in, the message moves on
    // to the next thing the server would reject — no second click needed to
    // learn about it.
    await waitFor(() =>
      expect(screen.getAllByText(/anthropic needs an api key/i)).toHaveLength(2),
    );
    expect(screen.queryByText(/system prompt is required/i)).toBeNull();

    // And Next still refuses to advance.
    await user.click(next());
    expect(screen.getByLabelText(/workforce name/i)).toBeInTheDocument();
    // Both advisors inherit the shared provider, so the shared key block is
    // flagged too — it is the one field that fixes both — and gets focus.
    const errors = screen.getAllByRole("alert");
    expect(errors[0]).toHaveTextContent(/add a key here to cover every advisor/i);
    expect(errors[1]).toHaveTextContent(/anthropic needs an api key.*llm defaults above/i);
    expect(document.activeElement).toBe(screen.getByTestId("apikey-defaults-picker-input"));
  });

  it("a shared default key satisfies every new advisor at once", async () => {
    const user = userEvent.setup();
    renderWizard();
    const create = await completeCustomTeam(user);

    expect(create).toBeInTheDocument();
    // The review names what each new advisor is built from.
    const anthropic = screen.getAllByText(/anthropic · claude-sonnet-5/i);
    expect(anthropic).toHaveLength(2);
    expect(screen.getByText(/you are a market analyst/i)).toBeInTheDocument();
  });

  it("a local default provider needs no key", async () => {
    const user = userEvent.setup();
    renderWizard();
    await fillNamesOnly(user);
    await fillPrompts(user);

    await user.selectOptions(screen.getByLabelText(/llm provider/i, { selector: "#provider-defaults" }), "ollama");
    // The key field disappears with the requirement.
    expect(screen.queryByTestId("apikey-defaults")).toBeNull();

    await user.click(next());
    expect(await screen.findByRole("button", { name: /create workforce/i })).toBeInTheDocument();
    expect(screen.getAllByText(/ollama \(local\) · llama3\.3:70b/i)).toHaveLength(2);
  });

  it("keeps a held-open card open once its flag clears, so typing is not interrupted", async () => {
    const user = userEvent.setup();
    renderWizard();
    await fillNamesOnly(user);
    // Key first, so filling the prompt resolves the advisor completely — the
    // card was held open only by the flag, and must not snap shut on the
    // first keystroke and unmount the textarea being typed into.
    await setDefaultKey(user);

    await user.click(next());
    const prompts = await screen.findAllByLabelText(/personality & expertise/i);
    await user.type(prompts[0]!, "You are a market analyst.");

    expect(screen.getAllByLabelText(/personality & expertise/i)[0]).toBe(prompts[0]);
    expect(prompts[0]).toHaveValue("You are a market analyst.");
    expect(document.activeElement).toBe(prompts[0]);
    // …and now that nothing is flagged, it can be collapsed by hand again.
    expect(screen.getAllByRole("button", { name: /collapse/i })[0]).toBeEnabled();
  });

  it("switching an advisor back to 'new' does not silently reuse the agent that was picked", async () => {
    // `agentId` (the picked agent) and `createdAgentId` (what a failed attempt
    // provisioned) were once one field. Picking an existing agent and then
    // changing your mind left the id behind: the advisor counted as complete,
    // never asked for a prompt or a key, was badged "Created", and the group
    // was assembled with the picked agent instead of a new advisor.
    const user = userEvent.setup();
    renderWizard();
    await fillNamesOnly(user);

    await user.click(next()); // opens the cards on the prompt requirement
    await screen.findAllByLabelText(/personality & expertise/i);

    // Advisor 1: use an existing agent…
    await user.click(screen.getAllByRole("button", { name: /use existing agent/i })[0]!);
    await user.click(screen.getAllByPlaceholderText(/select agent/i)[0]!);
    // AgentPicker's options carry no role/name of their own — the suite's own
    // agent-picker tests reach them the same way.
    const option = await waitFor(() => {
      const el = document.querySelector<HTMLElement>("[data-agent-item]");
      if (!el) throw new Error("no agent options yet");
      return el;
    });
    await user.click(option);
    await waitFor(() => expect(screen.getByLabelText(/clear selection/i)).toBeInTheDocument());

    // …then change your mind.
    await user.click(screen.getAllByRole("button", { name: /create new advisor/i })[0]!);

    // It is a new advisor again: no "Created" badge or note…
    expect(screen.queryByText(/^created$/i)).toBeNull();
    expect(screen.queryByTestId(/member-created-note-/)).toBeNull();

    // …and it owes a prompt like any other. Count both advisors rather than
    // reading the first alert: advisor 2 is flagged either way, so only the
    // total distinguishes "advisor 1 still counts as complete" from the fix.
    await user.click(next());
    const flagged = await screen.findAllByTestId(/^member-error-/);
    expect(flagged).toHaveLength(2);
    for (const f of flagged) expect(f).toHaveTextContent(/system prompt is required/i);
    expect(screen.getByLabelText(/workforce name/i)).toBeInTheDocument();
  });

  it("still requires a key for gemini-vertex, which LLM_PROVIDERS calls keyless", async () => {
    // LLM_PROVIDERS marks gemini-vertex needsKey: false, but the backend's
    // isLocalLlmProvider allow-list (ollama, jlama, bedrock, oracle-genai)
    // does not include it — so a keyless request is rejected server-side,
    // after earlier advisors have already been provisioned. Validation
    // mirrors the backend's list, not the UI flag.
    const user = userEvent.setup();
    renderWizard();
    await fillNamesOnly(user);
    await fillPrompts(user);

    await user.selectOptions(
      screen.getByLabelText(/llm provider/i, { selector: "#provider-defaults" }),
      "gemini-vertex",
    );

    expect(screen.getByTestId("apikey-defaults")).toBeInTheDocument();
    await user.click(next());
    expect(screen.getByLabelText(/workforce name/i)).toBeInTheDocument();
    expect(screen.getAllByRole("alert")[1]).toHaveTextContent(/google vertex ai needs an api key/i);
  });

  it("associates the API key label and its error with the input itself", async () => {
    // SecretKeyPicker renders its own input, so the wrapper's htmlFor used to
    // resolve to nothing: the required marker and the error were announced to
    // nobody, on the very field the wizard had just moved focus to.
    const user = userEvent.setup();
    renderWizard();
    await fillNamesOnly(user);
    await fillPrompts(user);

    await user.click(next());

    const input = screen.getByTestId("apikey-defaults-picker-input");
    // The label resolves to the focusable control…
    // Scoped: every advisor renders its own key field with the same label.
    const block = screen.getByTestId("apikey-defaults");
    expect(within(block).getByLabelText(/api key/i)).toBe(input);
    expect(input).toHaveAttribute("aria-invalid", "true");
    // …and the message it points at is the one on screen.
    const describedBy = input.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy!)).toHaveTextContent(
      /add a key here to cover every advisor/i,
    );
  });

  it("associates the agent-picker label and its error with the input too", async () => {
    // AgentPicker has the same shape as SecretKeyPicker — it renders its own
    // input — so the "Select agent *" label had the same orphaned htmlFor.
    const user = userEvent.setup();
    renderWizard();
    await fillNamesOnly(user);

    await user.click(next());
    await screen.findAllByLabelText(/personality & expertise/i);
    await user.click(screen.getAllByRole("button", { name: /use existing agent/i })[0]!);
    await user.click(next());

    const card = screen.getAllByTestId(/^member-card-/)[0]!;
    const input = within(card).getByPlaceholderText(/select agent/i);
    expect(within(card).getByLabelText(/select agent/i)).toBe(input);
    expect(input).toHaveAttribute("aria-invalid", "true");
    const describedBy = input.getAttribute("aria-describedby");
    expect(document.getElementById(describedBy!)).toHaveTextContent(
      /pick the existing agent/i,
    );
    expect(document.activeElement).toBe(input);
  });

  it("flags a missing board name rather than silently disabling Next", async () => {
    const user = userEvent.setup();
    renderWizard();
    await gotoTeamStep(user);

    await user.click(next());

    expect(await screen.findByTestId("board-name-error")).toHaveTextContent(/give this workforce a name/i);
    expect(document.activeElement).toBe(screen.getByLabelText(/workforce name/i));
  });

  it("does not carry a failed attempt's flags back onto a freshly picked template", async () => {
    const user = userEvent.setup();
    renderWizard();
    await gotoTeamStep(user);
    await user.click(next()); // fails: no name, no prompts
    expect(await screen.findAllByRole("alert")).not.toHaveLength(0);

    await user.click(back());
    await user.click(await screen.findByText(/advisory board/i));
    await user.click(next());

    await screen.findByLabelText(/workforce name/i);
    expect(screen.queryAllByRole("alert")).toHaveLength(0);
  });
});

describe("Workforce wizard — templates and starter prompts", () => {
  it("a template seeds every role's system prompt, so only the key is left to add", async () => {
    const user = userEvent.setup();
    renderWizard("advisory-board");
    await gotoTeamStep(user);

    // Five roles, five prompts, none of them blank — the only thing missing
    // is the key, flagged once on the shared block and once per advisor.
    await user.click(next());
    const errors = await screen.findAllByRole("alert");
    expect(errors).toHaveLength(6);
    for (const e of errors) expect(e).toHaveTextContent(/key/i);
    expect(screen.queryByText(/system prompt is required/i)).toBeNull();

    await setDefaultKey(user);
    await user.click(next());
    expect(await screen.findByRole("button", { name: /create workforce/i })).toBeInTheDocument();
    // The seeded prompt is what will be sent, and the review shows it.
    expect(screen.getAllByText(/you are marketing expert, the marketing voice/i)).toHaveLength(1);
  });

  it("a custom advisor can insert a starter prompt from its name and role", async () => {
    const user = userEvent.setup();
    renderWizard();
    await fillNamesOnly(user);
    const roles = screen.getAllByLabelText(/^role$/i);
    await user.type(roles[0]!, "Finance");

    await user.click(next()); // opens the cards
    const buttons = await screen.findAllByRole("button", { name: /insert a starter prompt/i });
    await user.click(buttons[0]!);

    const prompts = screen.getAllByLabelText(/personality & expertise/i);
    expect((prompts[0] as HTMLTextAreaElement).value).toMatch(/^You are Ana, the Finance voice on this team/);
    // The button is gone once there is text to edit instead.
    expect(screen.getAllByRole("button", { name: /insert a starter prompt/i })).toHaveLength(1);
  });

  it("a starter prompt never opens with a dangling comma when the name is blank", async () => {
    const user = userEvent.setup();
    renderWizard();
    await gotoTeamStep(user);
    const roles = screen.getAllByLabelText(/^role$/i);
    await user.type(roles[0]!, "Finance");
    // Open the first card by hand — nothing is flagged yet.
    await user.click(screen.getAllByRole("button", { name: /expand/i })[0]!);

    await user.click(screen.getAllByRole("button", { name: /insert a starter prompt/i })[0]!);

    const prompt = screen.getAllByLabelText(/personality & expertise/i)[0] as HTMLTextAreaElement;
    expect(prompt.value).toMatch(/^You are the Finance voice on this team/);
    expect(prompt.value).not.toMatch(/You are ,/);
  });
});

describe("Workforce wizard — creation failures", () => {
  it("does not send the shared key to a provider that takes none", async () => {
    // The shared defaults are inherited by every advisor, so a team key entered
    // for Anthropic and then switched to a local provider would otherwise ride
    // along into the setup request — where the backend vaults it and writes a
    // reference into an LLM config that has no use for it.
    const bodies: Array<Record<string, unknown>> = [];
    server.use(
      http.post("*/administration/agents/setup", async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        bodies.push(body);
        return HttpResponse.json({
          action: "created",
          agentId: `agent-${bodies.length}`,
          agentName: String(body.name),
          provider: "ollama",
          model: "llama3.3:70b",
        });
      }),
    );

    const user = userEvent.setup();
    renderWizard();
    await fillNamesOnly(user);
    await fillPrompts(user);
    // A key is entered first, while the default provider still needs one…
    await setDefaultKey(user, "sk-secret");
    // …then the team moves to a local provider, and the field disappears.
    await user.selectOptions(
      screen.getByLabelText(/llm provider/i, { selector: "#provider-defaults" }),
      "ollama",
    );
    expect(screen.queryByTestId("apikey-defaults")).toBeNull();

    await user.click(next());
    await user.click(await screen.findByRole("button", { name: /create workforce/i }));

    await waitFor(() => expect(bodies).toHaveLength(2));
    for (const body of bodies) {
      expect(body.provider).toBe("ollama");
      expect(body.apiKey).toBeUndefined();
    }
    expect(JSON.stringify(bodies)).not.toContain("sk-secret");
  });

  it("shows the backend's message instead of [object Object]", async () => {
    server.use(
      http.post("*/administration/agents/setup", () =>
        HttpResponse.json(
          { error: "System prompt is too long (9000 characters, maximum 8000)" },
          { status: 400 },
        ),
      ),
    );

    const user = userEvent.setup();
    renderWizard();
    await user.click(await completeCustomTeam(user));

    await screen.findByText(/creation failed/i);
    expect(await screen.findByText(/system prompt is too long/i)).toBeInTheDocument();
    expect(screen.queryByText(/\[object Object\]/)).toBeNull();
  });

  it("Try Again reuses the advisors it already created instead of re-creating them", async () => {
    const setupBodies: Array<{ name: string }> = [];
    let groupBody: { members: Array<{ agentId: string; displayName: string }> } | null = null;
    let calls = 0;
    server.use(
      http.post("*/administration/agents/setup", async ({ request }) => {
        const body = (await request.json()) as { name: string };
        setupBodies.push(body);
        calls += 1;
        // Second advisor fails the first time only.
        if (calls === 2) {
          return HttpResponse.json({ error: "Agent setup failed: store unavailable" }, { status: 500 });
        }
        return HttpResponse.json({
          action: "created",
          agentId: `agent-${body.name.toLowerCase()}`,
          agentName: body.name,
          provider: "anthropic",
          model: "claude-sonnet-5",
          deployed: true,
        });
      }),
      http.post("*/groupstore/groups", async ({ request }) => {
        groupBody = (await request.json()) as typeof groupBody;
        return new HttpResponse(null, {
          status: 201,
          headers: { Location: "eddi://ai.labs.group/groupstore/groups/grp-new?version=1" },
        });
      }),
    );

    const user = userEvent.setup();
    renderWizard();
    await user.click(await completeCustomTeam(user));

    await screen.findByText(/creation failed/i);
    expect(await screen.findByText(/store unavailable/i)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /try again/i }));

    await waitFor(() => expect(groupBody).not.toBeNull());
    // Ana once, Bo twice — never Ana again.
    expect(setupBodies.map((b) => b.name)).toEqual(["Ana", "Bo", "Bo"]);
    // And the group carries the ids that were actually minted, not "".
    expect(groupBody!.members.map((m) => [m.displayName, m.agentId])).toEqual([
      ["Ana", "agent-ana"],
      ["Bo", "agent-bo"],
    ]);
  });

  it("Back from a failed attempt shows the summary again and marks what was created", async () => {
    let calls = 0;
    server.use(
      http.post("*/administration/agents/setup", async ({ request }) => {
        const body = (await request.json()) as { name: string };
        calls += 1;
        if (calls === 2) return HttpResponse.json({ error: "boom" }, { status: 500 });
        return HttpResponse.json({ action: "created", agentId: `agent-${body.name}`, agentName: body.name, provider: "anthropic", model: "m" });
      }),
    );

    const user = userEvent.setup();
    renderWizard();
    await user.click(await completeCustomTeam(user));
    await screen.findByText(/creation failed/i);

    await user.click(back()); // → team step

    // Ana was provisioned before the failure: her card says so and no longer
    // offers a prompt to edit — an edit there would silently not apply.
    await screen.findByLabelText(/workforce name/i);
    expect(screen.getByText(/^created$/i)).toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: /expand/i })[0]!);
    expect(screen.getByTestId(/member-created-note-/)).toHaveTextContent(/reused as is/i);
    // Bo's card still has its editable prompt.
    expect(screen.queryAllByLabelText(/personality & expertise/i)).toHaveLength(0);
    await user.click(screen.getAllByRole("button", { name: /expand/i })[0]!);
    expect(screen.getAllByLabelText(/personality & expertise/i)).toHaveLength(1);

    await user.click(next()); // → review, summary view again

    expect(await screen.findByRole("button", { name: /create workforce/i })).toBeInTheDocument();
    expect(screen.getByText(/^created$/i)).toBeInTheDocument(); // Ana
    expect(screen.getByText(/^new$/i)).toBeInTheDocument(); // Bo
  });
});
