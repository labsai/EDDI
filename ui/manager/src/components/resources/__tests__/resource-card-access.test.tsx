import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ResourceCard } from "@/components/resources/resource-card";
import { WorkflowCard } from "@/components/workflows/workflow-card";
import type { AgentDescriptor } from "@/lib/api/agents";

const base = {
  resource: "eddi://ai.labs.output/outputstore/outputsets/r1?version=1",
  name: "Greeting output",
  description: "",
  lastModifiedOn: Date.now(),
  id: "r1",
  version: 1,
} as unknown as AgentDescriptor & { id: string; version: number };

const row = (callerLevel?: string) =>
  ({ ...base, ...(callerLevel ? { callerLevel } : {}) }) as typeof base;

const noop = vi.fn();

function renderResource(callerLevel?: string) {
  renderWithProviders(
    <ResourceCard
      item={row(callerLevel)}
      typeSlug="output"
      iconName="GitBranch"
      onDuplicate={noop}
      onDelete={noop}
    />,
  );
}

function renderWorkflow(callerLevel?: string) {
  renderWithProviders(
    <WorkflowCard workflow={row(callerLevel)} onDuplicate={noop} onDelete={noop} />,
  );
}

/**
 * Row actions, gated on what the backend says this caller may do.
 *
 * EDDI stamps `callerLevel` on every descriptor listing — `readDescriptors` in
 * `RestVersionInfo` is the shared base every per-store `/{store}/descriptors`
 * endpoint inherits, not something the agent store does specially. The Manager
 * read it on the agents page alone, so a workflow or extension a colleague
 * shared at VIEW still offered Duplicate and Delete, both of which 403 — which
 * reads as the product being broken rather than as the resource not being
 * yours.
 *
 * Both directions matter. Too permissive and the 403s come back; too
 * restrictive and every existing deployment, where the backend sends no level
 * at all, loses the buttons it had yesterday.
 */
describe.each([
  ["ResourceCard", renderResource, "resource-card", "resource-menu-r1"],
  ["WorkflowCard", renderWorkflow, "workflow-card", "workflow-menu-r1"],
] as const)("%s row actions", (_name, render, prefix, menuId) => {
  /** Render at `level` and open the menu, if there is one to open. */
  async function openMenu(level: string) {
    render(level);
    const user = userEvent.setup();
    await user.click(screen.getByTestId(menuId));
  }

  it("offers everything when the backend sends no level", () => {
    // Enforcement off, or an EDDI predating workspaces. Reading absence as "no
    // access" would empty the UI on upgrade.
    render(undefined);

    expect(screen.getByTestId(menuId)).toBeInTheDocument();
  });

  it("offers nothing at USE, and hides the menu rather than emptying it", () => {
    // The share that motivated the whole feature: an agent a colleague shared
    // so you could TALK to it. Duplicate reads the configuration and Delete is
    // the owner's, so neither belongs here.
    render("USE");

    expect(screen.queryByTestId(menuId)).not.toBeInTheDocument();
  });

  it("offers Duplicate but not Delete at VIEW", async () => {
    await openMenu("VIEW");

    expect(screen.getByTestId(`${prefix}-duplicate-r1`)).toBeInTheDocument();
    expect(screen.queryByTestId(`${prefix}-delete-r1`)).not.toBeInTheDocument();
  });

  it("still withholds Delete at EDIT", async () => {
    // EDIT changes and deploys a resource; removing it and deciding who else
    // may reach it stay with the owner.
    await openMenu("EDIT");

    expect(screen.getByTestId(`${prefix}-duplicate-r1`)).toBeInTheDocument();
    expect(screen.queryByTestId(`${prefix}-delete-r1`)).not.toBeInTheDocument();
  });

  it("offers both at OWN", async () => {
    await openMenu("OWN");

    expect(screen.getByTestId(`${prefix}-duplicate-r1`)).toBeInTheDocument();
    expect(screen.getByTestId(`${prefix}-delete-r1`)).toBeInTheDocument();
  });
});
