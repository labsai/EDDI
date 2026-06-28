import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { GroupCard } from "@/components/groups/group-card";

const baseGroup = {
  id: "grp-123",
  version: 2,
  name: "Product Review Panel",
  description: "A group for product reviews",
  lastModifiedOn: Date.now() - 3600000,
};

describe("GroupCard", () => {
  it("renders the group name", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByText("Product Review Panel")).toBeInTheDocument();
  });

  it("renders the group description", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByText("A group for product reviews")).toBeInTheDocument();
  });

  it("renders the group ID", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByText("grp-123")).toBeInTheDocument();
  });

  it("renders the version badge", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByText("v2")).toBeInTheDocument();
  });

  it("renders member count badge (default 0)", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByText("0")).toBeInTheDocument();
  });

  it("renders member count badge with custom value", () => {
    renderWithProviders(<GroupCard group={baseGroup} memberCount={5} />);
    expect(screen.getByText("5")).toBeInTheDocument();
  });

  it("renders style badge when style is provided", () => {
    renderWithProviders(
      <GroupCard group={baseGroup} style="PEER_REVIEW" />
    );
    expect(screen.getByText("Peer Review")).toBeInTheDocument();
  });

  it("renders 'Group' fallback when no style is provided", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByText("Group")).toBeInTheDocument();
  });

  it("renders 'Unnamed Group' when name is empty", () => {
    const group = { ...baseGroup, name: "" };
    renderWithProviders(<GroupCard group={group} />);
    expect(screen.getByText("Unnamed Group")).toBeInTheDocument();
  });

  it("renders 'No description' when description is empty", () => {
    const group = { ...baseGroup, description: "" };
    renderWithProviders(<GroupCard group={group} />);
    expect(screen.getByText("No description")).toBeInTheDocument();
  });

  it("has data-testid with group id", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByTestId("group-card-grp-123")).toBeInTheDocument();
  });

  it("links to the group detail page", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    const link = screen.getByText("Product Review Panel").closest("a");
    expect(link).toHaveAttribute(
      "href",
      "/manage/groups/grp-123?version=2"
    );
  });

  it("shows context menu on button click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <GroupCard
        group={baseGroup}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );

    await user.click(screen.getByTestId("group-menu-grp-123"));
    expect(screen.getByText("Duplicate")).toBeInTheDocument();
    expect(screen.getByText("Delete")).toBeInTheDocument();
  });

  it("calls onDuplicate when duplicate is clicked", async () => {
    const onDuplicate = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <GroupCard group={baseGroup} onDuplicate={onDuplicate} />
    );

    await user.click(screen.getByTestId("group-menu-grp-123"));
    await user.click(screen.getByText("Duplicate"));
    expect(onDuplicate).toHaveBeenCalledWith("grp-123", 2);
  });

  it("calls onDelete when delete is clicked", async () => {
    const onDelete = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <GroupCard group={baseGroup} onDelete={onDelete} />
    );

    await user.click(screen.getByTestId("group-menu-grp-123"));
    await user.click(screen.getByText("Delete"));
    expect(onDelete).toHaveBeenCalledWith("grp-123", 2);
  });

  it("closes menu after clicking an action", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <GroupCard group={baseGroup} onDuplicate={vi.fn()} />
    );

    await user.click(screen.getByTestId("group-menu-grp-123"));
    expect(screen.getByText("Duplicate")).toBeInTheDocument();

    await user.click(screen.getByText("Duplicate"));
    expect(screen.queryByText("Duplicate")).not.toBeInTheDocument();
  });

  // ─── Style badge variants ──────────────────────────────────────────

  it("renders style badge with icon and label for ROUND_TABLE", () => {
    renderWithProviders(
      <GroupCard group={baseGroup} style="ROUND_TABLE" />
    );
    expect(screen.getByText("Round Table")).toBeInTheDocument();
    expect(screen.getByText("🗣️")).toBeInTheDocument();
  });

  it("renders style badge for TASK_FORCE", () => {
    renderWithProviders(
      <GroupCard group={baseGroup} style="TASK_FORCE" />
    );
    expect(screen.getByText("Task Force")).toBeInTheDocument();
    expect(screen.getByText("🎯")).toBeInTheDocument();
  });

  // ─── Member preview chips ─────────────────────────────────────────

  it("renders member preview chips (up to 4)", () => {
    const members = [
      { displayName: "Agent Alpha", memberType: "AGENT" },
      { displayName: "Agent Beta", memberType: "AGENT" },
      { displayName: "Sub Group", memberType: "GROUP" },
    ];
    renderWithProviders(
      <GroupCard group={baseGroup} members={members} memberCount={3} />
    );
    expect(screen.getByText("Agent Alpha")).toBeInTheDocument();
    expect(screen.getByText("Agent Beta")).toBeInTheDocument();
    expect(screen.getByText("Sub Group")).toBeInTheDocument();
  });

  it("shows Bot icon for AGENT type members", () => {
    const members = [{ displayName: "Agent Alpha", memberType: "AGENT" }];
    renderWithProviders(
      <GroupCard group={baseGroup} members={members} memberCount={1} />
    );
    // Bot icon from lucide has aria-hidden="true" on SVG inside the chip
    const chip = screen.getByRole("listitem", { name: "Agent Alpha" });
    const svg = chip.querySelector("svg");
    expect(svg).toBeInTheDocument();
    expect(svg).toHaveAttribute("aria-hidden", "true");
  });

  it("shows Users icon for GROUP type members", () => {
    const members = [{ displayName: "Sub Group", memberType: "GROUP" }];
    renderWithProviders(
      <GroupCard group={baseGroup} members={members} memberCount={1} />
    );
    const chip = screen.getByRole("listitem", { name: "Sub Group" });
    const svg = chip.querySelector("svg");
    expect(svg).toBeInTheDocument();
    expect(svg).toHaveAttribute("aria-hidden", "true");
  });

  it("shows overflow count when more than 4 members", () => {
    const members = [
      { displayName: "A1", memberType: "AGENT" },
      { displayName: "A2", memberType: "AGENT" },
      { displayName: "A3", memberType: "AGENT" },
      { displayName: "A4", memberType: "AGENT" },
      { displayName: "A5", memberType: "AGENT" },
      { displayName: "A6", memberType: "AGENT" },
    ];
    renderWithProviders(
      <GroupCard group={baseGroup} members={members} memberCount={6} />
    );
    // Only first 4 chips rendered; overflow text shows "+2 more"
    expect(screen.queryByText("A5")).not.toBeInTheDocument();
    expect(screen.queryByText("A6")).not.toBeInTheDocument();
    expect(screen.getByText(/\+2 more/)).toBeInTheDocument();
  });

  it("does not render member chips when members is empty", () => {
    renderWithProviders(
      <GroupCard group={baseGroup} members={[]} memberCount={0} />
    );
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });

  it("member chips have role='listitem' and aria-label", () => {
    const members = [
      { displayName: "Agent Alpha", memberType: "AGENT" },
      { displayName: "Agent Beta", memberType: "AGENT" },
    ];
    renderWithProviders(
      <GroupCard group={baseGroup} members={members} memberCount={2} />
    );
    const items = screen.getAllByRole("listitem");
    expect(items.length).toBe(2);
    expect(items[0]).toHaveAttribute("aria-label", "Agent Alpha");
    expect(items[1]).toHaveAttribute("aria-label", "Agent Beta");
  });

  it("member container has role='list' and data-testid", () => {
    const members = [{ displayName: "Agent Alpha", memberType: "AGENT" }];
    renderWithProviders(
      <GroupCard group={baseGroup} members={members} memberCount={1} />
    );
    const list = screen.getByRole("list");
    expect(list).toHaveAttribute("data-testid", "group-card-members-grp-123");
  });

  // ─── Name as link ─────────────────────────────────────────────────

  it("renders group name as a navigable link", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    const link = screen.getByText("Product Review Panel").closest("a");
    expect(link).toBeInTheDocument();
    expect(link).toHaveAttribute("href", "/manage/groups/grp-123?version=2");
  });

  // ─── Relative time ────────────────────────────────────────────────

  it("renders relative time", () => {
    // baseGroup.lastModifiedOn is 1 hour ago
    renderWithProviders(<GroupCard group={baseGroup} />);
    // formatRelativeTime returns something like "1h ago" — just verify something renders
    const card = screen.getByTestId("group-card-grp-123");
    // The footer area should contain time text (not empty)
    const timeSpan = card.querySelector("[title]");
    expect(timeSpan).toBeInTheDocument();
  });

  it("renders '—' when lastModifiedOn is 0", () => {
    const group = { ...baseGroup, lastModifiedOn: 0 };
    renderWithProviders(<GroupCard group={group} />);
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("renders '—' when lastModifiedOn is undefined", () => {
    const group = { ...baseGroup, lastModifiedOn: undefined as unknown as number };
    renderWithProviders(<GroupCard group={group} />);
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("renders valid relative time for valid timestamp", () => {
    const group = { ...baseGroup, lastModifiedOn: Date.now() - 7200000 }; // 2h ago
    renderWithProviders(<GroupCard group={group} />);
    expect(screen.getByText("2h ago")).toBeInTheDocument();
  });
});
