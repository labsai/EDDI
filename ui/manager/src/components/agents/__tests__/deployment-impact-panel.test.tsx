import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse, type JsonBodyType } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { DeploymentImpactPanel } from "@/components/agents/deployment-impact-panel";
import { CompatibilityGenerationBadge } from "@/components/agents/compatibility-generation-badge";

function stubImpact(body: JsonBodyType, status = 200) {
  server.use(
    http.get("*/administration/:env/deploymentimpact/:agentId", () =>
      status === 200 ? HttpResponse.json(body) : new HttpResponse(null, { status }),
    ),
  );
}

const renderPanel = () =>
  renderWithProviders(
    <DeploymentImpactPanel agentId="agent9" version={6} environment="production" environmentLabel="Production" />,
  );

describe("DeploymentImpactPanel", () => {
  it("renders a FOLLOW row and a STAY row with their counts and versions", async () => {
    stubImpact({
      agentId: "agent9",
      version: 6,
      compatibilityGeneration: 3,
      deployedVersions: [
        { version: 5, compatibilityGeneration: 3, activeConversations: 12, outcome: "FOLLOW" },
        { version: 3, compatibilityGeneration: 2, activeConversations: 4, outcome: "STAY" },
      ],
    });
    renderPanel();

    const follow = await screen.findByTestId("impact-row-5");
    expect(follow).toHaveAttribute("data-outcome", "FOLLOW");
    expect(follow).toHaveTextContent("12 active conversations on v5 will continue on v6");

    const stay = screen.getByTestId("impact-row-3");
    expect(stay).toHaveAttribute("data-outcome", "STAY");
    expect(stay).toHaveTextContent("4 active conversations on v3 will stay on v3");
    expect(stay).toHaveTextContent("breaking change");
  });

  it("names why a row stays: a newer version, or one that predates version following", async () => {
    stubImpact({
      agentId: "agent9",
      version: 6,
      compatibilityGeneration: 3,
      deployedVersions: [
        { version: 7, compatibilityGeneration: 3, activeConversations: 1, outcome: "STAY" },
        { version: 2, compatibilityGeneration: null, activeConversations: 2, outcome: "STAY" },
      ],
    });
    renderPanel();

    expect(await screen.findByTestId("impact-row-7")).toHaveTextContent("1 active conversation on v7");
    expect(screen.getByTestId("impact-row-7")).toHaveTextContent("newer version");
    expect(screen.getByTestId("impact-row-2")).toHaveTextContent("predates version following");
  });

  it("renders nothing when no other version is deployed", async () => {
    let answered = false;
    server.use(
      http.get("*/administration/:env/deploymentimpact/:agentId", () => {
        answered = true;
        return HttpResponse.json({ agentId: "agent9", version: 6, compatibilityGeneration: 3, deployedVersions: [] });
      }),
    );
    const { container } = renderPanel();
    await waitFor(() => expect(answered).toBe(true));
    await new Promise((r) => setTimeout(r, 20));
    expect(container).toBeEmptyDOMElement();
  });

  it("stays quiet when the preview fails — it never blocks a deploy", async () => {
    stubImpact(null, 404);
    const { container } = renderPanel();
    await new Promise((r) => setTimeout(r, 50));
    expect(container).toBeEmptyDOMElement();
  });
});

describe("CompatibilityGenerationBadge", () => {
  it("shows the generation with an explaining tooltip", () => {
    renderWithProviders(<CompatibilityGenerationBadge generation={4} />);
    const badge = screen.getByTestId("compatibility-generation-badge");
    expect(badge).toHaveTextContent("compat. gen 4");
    expect(badge.getAttribute("title")).toMatch(/Compatibility generation 4/);
  });

  it("renders nothing for a version without a generation", () => {
    const { container } = renderWithProviders(<CompatibilityGenerationBadge generation={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});
