import { describe, it, expect, vi, beforeEach, beforeAll, afterAll } from "vitest";
import { screen, fireEvent, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

import { CommandPalette } from "../command-palette";
import { CreateAgentDialog } from "@/components/agents/create-agent-dialog";
import { useCommandPalette } from "@/hooks/use-command-palette";
import { parseResourceUri } from "@/lib/api/agents";

/**
 * Agent detail lives at /manage/agentview/:id. Two places built
 * /manage/agents/:id instead, which matches no route and therefore fell through
 * to the catch-all redirect to /welcome — silently ejecting the user right
 * after they picked an agent or created one.
 */

const mockNavigate = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>(
    "react-router-dom",
  );
  return { ...actual, useNavigate: () => mockNavigate };
});

// cmdk uses ResizeObserver and scrollIntoView internally; jsdom has neither.
beforeAll(() => {
  global.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  Element.prototype.scrollIntoView = vi.fn();
});

afterAll(() => {
  // @ts-expect-error restore
  delete global.ResizeObserver;
});

beforeEach(() => {
  mockNavigate.mockClear();
  useCommandPalette.setState({ isOpen: false });
});

describe("parseResourceUri", () => {
  it("extracts the trailing id from a real descriptor resource URI", () => {
    // Real shape includes the store path, e.g.
    // eddi://ai.labs.agent/agentstore/agents/<id>?version=<n>
    expect(
      parseResourceUri("eddi://ai.labs.agent/agentstore/agents/abc123?version=2"),
    ).toEqual({ id: "abc123", version: 2 });
  });

  it("falls back to the whole string when the URI carries no path", () => {
    // Documents a sharp edge: with no path segment there is no id to take, and
    // the function returns the input. A caller that builds a route from this
    // would produce a URL that matches nothing — which is exactly how the
    // command palette broke when it passed the raw resource through instead.
    const pathless = "eddi://ai.labs.agent?id=abc123&version=2";
    expect(parseResourceUri(pathless).id).toBe(pathless);
  });
});

describe("command palette agent results", () => {
  it("navigates to /manage/agentview/:id, not /manage/agents/:id", async () => {
    server.use(
      http.get("*/agentstore/agents/descriptors", () =>
        HttpResponse.json([
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/agent42?version=1",
            name: "Support Bot",
            description: "handles tickets",
            createdOn: 0,
            lastModifiedOn: 0,
          },
        ]),
      ),
    );

    // Open before mounting so the state change is not an un-acted update.
    useCommandPalette.setState({ isOpen: true });
    renderWithProviders(<CommandPalette />);

    const item = await screen.findByText("Support Bot");
    fireEvent.click(item);

    await waitFor(() => expect(mockNavigate).toHaveBeenCalled());
    const target = mockNavigate.mock.calls[0]![0] as string;
    expect(target).toBe("/manage/agentview/agent42");
    expect(target).not.toMatch(/^\/manage\/agents\//);
  });
});

describe("command palette with a malformed agent resource", () => {
  it("falls back to the agent list instead of throwing or building a dead route", async () => {
    // parseResourceUri constructs a `new URL(...)`, which throws on input like
    // this. Uncaught, selecting the row took the palette down.
    server.use(
      http.get("*/agentstore/agents/descriptors", () =>
        HttpResponse.json([
          {
            resource: "http://[",
            name: "Broken Agent",
            description: "malformed resource",
            createdOn: 0,
            lastModifiedOn: 0,
          },
        ]),
      ),
    );

    useCommandPalette.setState({ isOpen: true });
    renderWithProviders(<CommandPalette />);

    fireEvent.click(await screen.findByText("Broken Agent"));

    await waitFor(() => expect(mockNavigate).toHaveBeenCalled());
    expect(mockNavigate.mock.calls[0]![0]).toBe("/manage/agents");
  });
});

describe("create agent dialog", () => {
  it("navigates to /manage/agentview/:id after a successful create", async () => {
    server.use(
      // The backend returns a URL *path* in Location, not an eddi:// URI.
      http.post("*/agentstore/agents", () =>
        HttpResponse.json(
          {},
          {
            status: 201,
            headers: { Location: "/agentstore/agents/new99?version=1" },
          },
        ),
      ),
      // useCreateAgent follows up with a descriptor patch when a name is given.
      http.patch("*/descriptorstore/descriptors/*", () =>
        HttpResponse.json({}, { status: 200 }),
      ),
    );

    renderWithProviders(<CreateAgentDialog open onClose={() => {}} />);

    const nameInput = screen.getByTestId("create-agent-dialog").querySelector("input");
    expect(nameInput).not.toBeNull();
    fireEvent.change(nameInput!, { target: { value: "New Agent" } });
    fireEvent.submit(screen.getByTestId("create-agent-dialog").querySelector("form")!);

    await waitFor(() => expect(mockNavigate).toHaveBeenCalled());
    const target = mockNavigate.mock.calls[0]![0] as string;
    expect(target).toBe("/manage/agentview/new99");
    expect(target).not.toMatch(/^\/manage\/agents\//);
  });
});
