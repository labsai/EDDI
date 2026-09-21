import { describe, it, expect, beforeEach, vi, afterEach, afterAll, beforeAll } from "vitest";
import { screen } from "@testing-library/react";
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

describe("WorkforceSettings", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders loading skeleton while config loads", async () => {
    // Delay the network response to observe loading state
    server.use(
      http.get("*/groupstore/groups/grp1", async () => {
        await new Promise(r => setTimeout(r, 150));
        return HttpResponse.json({});
      })
    );
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    
    // Skeleton should be visible
    expect(screen.queryByText(/Back to Task Force/i)).not.toBeInTheDocument();
  });
  it("renders general settings section with name, description, style, max rounds, moderator", async () => {
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    expect(await screen.findByDisplayValue("Product Review Panel")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Peer-review discussion for product decisions")).toBeInTheDocument();
    expect(screen.getByLabelText(/Collaboration Framework/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Max Rounds/i)).toHaveValue(3);
    expect(screen.getByLabelText(/Moderator/i)).toBeInTheDocument();
  });

  it("renders team management section with member cards", async () => {
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    const supportAgents = await screen.findAllByDisplayValue("Support Agent");
    expect(supportAgents.length).toBeGreaterThan(0);
    expect(screen.getAllByDisplayValue("FAQ Agent").length).toBeGreaterThan(0);
    expect(screen.getAllByDisplayValue("Reviewer").length).toBeGreaterThan(0);
    expect(screen.getAllByDisplayValue("Critic").length).toBeGreaterThan(0);
  });

  it("shows add member form when button clicked", async () => {
    const user = userEvent.setup();
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    const addMemberBtn = await screen.findByRole("button", { name: /Add Member/i });
    await user.click(addMemberBtn);
    expect(screen.getByText(/Add Team Member/i)).toBeInTheDocument();
    expect(screen.getAllByText("Agent").length).toBeGreaterThan(0);
  });


  it("shows unsaved changes indicator when form is dirty", async () => {
    const user = userEvent.setup();
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    
    const nameInput = await screen.findByLabelText(/Task Force Name/i);
    await user.clear(nameInput);
    await user.type(nameInput, "New Name");

    const saveBtn = screen.getByRole("button", { name: /Save Changes/i });
    expect(saveBtn).not.toBeDisabled();
  });

  it("renders Protocol & Resilience section (collapsed by default, expandable)", async () => {
    const user = userEvent.setup();
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    
    const protocolSection = await screen.findByRole("button", { name: /Protocol & Resilience/i });
    expect(protocolSection).toHaveAttribute("aria-expanded", "false");
    
    await user.click(protocolSection);
    expect(protocolSection).toHaveAttribute("aria-expanded", "true");
    
    expect(screen.getByLabelText(/Agent Timeout/i)).toBeInTheDocument();
  });

  it("renders Human Oversight (HITL) section (collapsed by default, expandable)", async () => {
    const user = userEvent.setup();
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    
    const hitlSection = await screen.findByRole("button", { name: /Human Oversight/i });
    expect(hitlSection).toHaveAttribute("aria-expanded", "false");
    
    await user.click(hitlSection);
    expect(hitlSection).toHaveAttribute("aria-expanded", "true");
    
    expect(screen.getByLabelText(/Approval Timeout/i)).toBeInTheDocument();
  });

  it("renders Dynamic Agents section (collapsed by default, expandable)", async () => {
    const user = userEvent.setup();
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    
    const dynamicSection = await screen.findByRole("button", { name: /Dynamic Agents/i });
    expect(dynamicSection).toHaveAttribute("aria-expanded", "false");
    
    await user.click(dynamicSection);
    expect(dynamicSection).toHaveAttribute("aria-expanded", "true");
    
    expect(screen.getByText(/Enable Dynamic Agents/i)).toBeInTheDocument();
  });

  it("Dynamic Agents: shows sub-fields when enabled toggle is on", async () => {
    const user = userEvent.setup();
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    
    const dynamicSection = await screen.findByRole("button", { name: /Dynamic Agents/i });
    await user.click(dynamicSection);
    
    // Click the master toggle (first switch in the section)
    const switches = screen.getAllByRole("switch");
    const enableSwitch = switches[0];
    expect(enableSwitch).toBeDefined();
    await user.click(enableSwitch!);
    
    expect(screen.getByLabelText(/Max Created/i)).toBeInTheDocument();
  });

  it("Shows Task Definitions section ONLY when style is TASK_FORCE", async () => {
    const user = userEvent.setup();
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    
    await screen.findByDisplayValue("Product Review Panel");
    expect(screen.queryByText(/Pre-configured Tasks/i)).not.toBeInTheDocument();
    
    // Change style to TASK_FORCE in the UI
    const styleSelect = screen.getByLabelText(/Collaboration Framework/i);
    await user.selectOptions(styleSelect, "TASK_FORCE");
    
    expect(screen.getByText(/Pre-configured Tasks/i)).toBeInTheDocument();
  });

  it("Can add a task definition", async () => {
    const user = userEvent.setup();
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    
    await screen.findByDisplayValue("Product Review Panel");
    const styleSelect = screen.getByLabelText(/Collaboration Framework/i);
    await user.selectOptions(styleSelect, "TASK_FORCE");
    
    const addBtn = screen.getByRole("button", { name: /Add Task/i });
    await user.click(addBtn);
    
    expect(screen.getByPlaceholderText(/What needs to be done\?/i)).toBeInTheDocument();
  });

  it("renders danger zone with delete buttons", async () => {
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    await screen.findByDisplayValue("Product Review Panel");
    expect(screen.getByText(/Danger Zone/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Dissolve Task Force" })).toBeInTheDocument();
  });

  it("Save button is disabled when form is not dirty", async () => {
    renderPage("/workforce/grp1/settings?version=1", <WorkforceSettings />, "/workforce/:boardId/settings");
    await screen.findByDisplayValue("Product Review Panel");
    
    const saveBtn = screen.getByRole("button", { name: /Save Changes/i });
    expect(saveBtn).toBeDisabled();
  });
});
