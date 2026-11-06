import { describe, it, expect } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { ThemeProvider } from "@eddi/ui";
import { AuthContext, GUEST_CONTEXT } from "@eddi/app-kit/components/auth/auth-context";
import { createTestQueryClient } from "@eddi/app-kit/test/test-utils";
import { MOCK_CATALOG_AGENTS, MOCK_CATALOG_GROUPS } from "@eddi/app-kit/test/mocks/catalog-fixtures";
import { readInitialTeamQuestion } from "@/lib/workforce-team-start";
import { EndUserCatalog } from "@/components/workforce/catalog-view";
import { readInitialChatMessage } from "@eddi/app-kit/lib/workforce-chat-start";
import { NEW_CHAT_STATE } from "@/lib/workforce-keyboard";

/**
 * Workforce Home for an end user (T1.4): a composer first with an agent
 * picker and the agent's starters, the recent conversations, then the
 * catalog with each agent's appearance (B4).
 */

const ADA = MOCK_CATALOG_AGENTS[0];
const TRAVEL = MOCK_CATALOG_AGENTS[1];

function TeamProbe() {
  const location = useLocation();
  const initial = readInitialTeamQuestion(location.state);
  return (
    <div data-testid="team-probe">
      <span data-testid="team-probe-path">{location.pathname + location.search}</span>
      <span data-testid="team-probe-question">{initial ? `${initial.groupId}|${initial.text}` : ""}</span>
    </div>
  );
}

function ChatProbe() {
  const location = useLocation();
  const initial = readInitialChatMessage(location.state);
  return (
    <div data-testid="chat-probe">
      <span data-testid="probe-search">{location.search}</span>
      <span data-testid="probe-message">{initial ? `${initial.agentId}|${initial.text}` : ""}</span>
    </div>
  );
}

function renderHome(state?: unknown) {
  return render(
    <AuthContext.Provider value={{ ...GUEST_CONTEXT, method: "keycloak", roles: ["eddi-user"] }}>
      <MemoryRouter initialEntries={[{ pathname: "/workforce", state }]}>
        <QueryClientProvider client={createTestQueryClient()}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <Routes>
              <Route path="/workforce" element={<EndUserCatalog />} />
              <Route path="/workforce/chat" element={<ChatProbe />} />
              <Route path="/workforce/:boardId" element={<TeamProbe />} />
            </Routes>
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe("Workforce Home", () => {
  it("takes the caret into its composer when it is opened for a new chat (⌘⇧O)", async () => {
    renderHome(NEW_CHAT_STATE);
    const input = await screen.findByTestId("home-composer-input");
    await waitFor(() => expect(input).toHaveFocus());
  });

  it("leaves focus alone on an ordinary visit", async () => {
    renderHome();
    const input = await screen.findByTestId("home-composer-input");
    expect(input).not.toHaveFocus();
  });

  it("puts the composer first, picks a listed agent and offers its starters", async () => {
    renderHome();
    const composer = await screen.findByTestId("home-composer");
    const catalog = screen.getByTestId("workforce-catalog");
    // The composer comes before the catalog's cards.
    const firstCard = within(catalog).getByTestId(`catalog-agent-${ADA.agentId}`);
    expect(composer.compareDocumentPosition(firstCard) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    const picker = screen.getByTestId("home-agent-picker") as HTMLSelectElement;
    // The agents, then the catalog's teams (T2.1).
    await waitFor(() => expect(picker.options.length).toBe(MOCK_CATALOG_AGENTS.length + MOCK_CATALOG_GROUPS.length));
    // The appearance's display name, not the stored name.
    expect([...picker.options].map((o) => o.textContent)).toContain(ADA.appearance.displayName);
    await waitFor(() => expect(picker.value).toBe(ADA.agentId));
    const starters = await screen.findByTestId("home-starters");
    expect(within(starters).getAllByRole("button").map((b) => b.textContent)).toEqual([...ADA.appearance.starters]);
  });

  it("starts a chat with a starter's text, carried in router state and not in the URL", async () => {
    renderHome();
    await waitFor(() => expect((screen.getByTestId("home-agent-picker") as HTMLSelectElement).value).toBe(ADA.agentId));
    fireEvent.click(await screen.findByTestId("home-starter-0"));
    const probe = await screen.findByTestId("chat-probe");
    expect(within(probe).getByTestId("probe-search")).toHaveTextContent(`?agentId=${ADA.agentId}`);
    expect(within(probe).getByTestId("probe-search").textContent).not.toContain("order");
    expect(within(probe).getByTestId("probe-message")).toHaveTextContent(`${ADA.agentId}|${ADA.appearance.starters[0]}`);
  });

  it("sends what is typed to the picked agent, on Enter", async () => {
    renderHome();
    const picker = (await screen.findByTestId("home-agent-picker")) as HTMLSelectElement;
    // The agents, then the catalog's teams (T2.1).
    await waitFor(() => expect(picker.options.length).toBe(MOCK_CATALOG_AGENTS.length + MOCK_CATALOG_GROUPS.length));
    fireEvent.change(picker, { target: { value: TRAVEL.agentId } });
    // An agent without starters shows none.
    expect(screen.queryByTestId("home-starters")).toBeNull();
    const input = screen.getByTestId("home-composer-input");
    fireEvent.change(input, { target: { value: "A weekend in Lisbon" } });
    await waitFor(() => expect(screen.getByTestId("home-composer-send")).not.toBeDisabled());
    fireEvent.keyDown(input, { key: "Enter" });
    expect(await screen.findByTestId("probe-message")).toHaveTextContent(`${TRAVEL.agentId}|A weekend in Lisbon`);
  });

  it("offers the catalog's teams beside the agents, and sends to a team's board in router state (T2.1)", async () => {
    renderHome();
    const picker = (await screen.findByTestId("home-agent-picker")) as HTMLSelectElement;
    const team = MOCK_CATALOG_GROUPS[0];
    await screen.findByTestId(`home-team-option-${team.groupId}`);
    const groups = [...picker.querySelectorAll("optgroup")].map((g) => g.label);
    expect(groups).toEqual(["Agents", "Teams"]);
    expect(screen.getByLabelText("Agent or team")).toBe(picker);

    fireEvent.change(picker, { target: { value: `team:${team.groupId}` } });
    const input = screen.getByTestId("home-composer-input");
    expect(input).toHaveAttribute("placeholder", `Ask the team ${team.name}…`);
    // A team has no starters.
    expect(screen.queryByTestId("home-starters")).toBeNull();
    fireEvent.change(input, { target: { value: "Should we ship the beta?" } });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(await screen.findByTestId("team-probe-question")).toHaveTextContent(`${team.groupId}|Should we ship the beta?`);
    // The question is not in the URL.
    expect(screen.getByTestId("team-probe-path")).toHaveTextContent(`/workforce/${team.groupId}?version=${team.version}`);
    expect(screen.getByTestId("team-probe-path").textContent).not.toContain("ship");
  });

  it("keeps Shift+Enter for a new line and sends nothing empty", async () => {
    renderHome();
    const input = await screen.findByTestId("home-composer-input");
    fireEvent.keyDown(input, { key: "Enter" });
    fireEvent.change(input, { target: { value: "line one" } });
    fireEvent.keyDown(input, { key: "Enter", shiftKey: true });
    expect(screen.queryByTestId("chat-probe")).toBeNull();
    expect(screen.getByTestId("home-composer-send")).toBeInTheDocument();
  });

  it("lists the recent conversations from the sidebar's data", async () => {
    renderHome();
    const recent = await screen.findByTestId("home-recent");
    expect(within(recent).getAllByRole("link").length).toBeLessThanOrEqual(5);
    expect(within(recent).getByTestId("home-recent-chat-conv8")).toHaveAttribute(
      "href",
      "/workforce/chat?agentId=agent7&conversationId=conv8",
    );
  });

  it("shows an agent's accent colour on its card and its display name", async () => {
    renderHome();
    const card = await screen.findByTestId(`catalog-agent-${ADA.agentId}`);
    expect(within(card).getByText(ADA.appearance.displayName)).toBeInTheDocument();
    expect(within(card).getByRole("button")).toHaveStyle({ borderColor: ADA.appearance.accentColor });
    // No appearance: the stored name, no accent.
    const plain = screen.getByTestId(`catalog-agent-${TRAVEL.agentId}`);
    expect(within(plain).getByText(TRAVEL.name)).toBeInTheDocument();
  });
});
