import { describe, it, expect, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { AuditPage } from "@/pages/audit";
import { server } from "@/test/mocks/server";

// Only "agent1" counts as deployed; the descriptor list (MSW) holds more.
vi.mock("@/hooks/use-chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/hooks/use-chat")>();
  return {
    ...actual,
    useDeployedAgents: () => ({
      data: [{ id: "agent1", name: "Deployed One", version: 1, environments: ["production"] }],
    }),
  };
});

describe("AuditPage - agent list and URL state", () => {
  it("lists agents that are not deployed too, in their own group", async () => {
    renderWithProviders(<AuditPage />, { initialRoute: "/manage/audit" });
    const select = screen.getByTestId("agent-input");
    await waitFor(() => {
      expect(within(select).getAllByRole("group").length).toBe(2);
    });
    const groups = within(select).getAllByRole("group");
    expect(groups[0]).toHaveAttribute("label", "Deployed");
    const deployedValues = within(groups[0]!).getAllByRole("option").map((o) => (o as HTMLOptionElement).value);
    expect(deployedValues).toEqual(["agent1"]);
    expect(groups[1]).toHaveAttribute("label", "Not deployed");
    expect(within(groups[1]!).getAllByRole("option").length).toBeGreaterThan(0);
  });

  it("restores the selected agent from the URL and searches it", async () => {
    let requested = "";
    server.use(
      http.get("*/auditstore/agent/:agentId", ({ params }) => {
        requested = String(params.agentId);
        return HttpResponse.json([]);
      }),
    );
    renderWithProviders(<AuditPage />, { initialRoute: "/manage/audit?agent=agent1&version=2" });
    expect((screen.getByTestId("version-input") as HTMLInputElement).value).toBe("2");
    await waitFor(() => expect(requested).toBe("agent1"));
  });

  it("restores conversation mode and its search from the URL", () => {
    renderWithProviders(<AuditPage />, {
      initialRoute: "/manage/audit?mode=conversation&conversation=conv-42",
    });
    expect((screen.getByTestId("conversation-input") as HTMLInputElement).value).toBe("conv-42");
  });
});
