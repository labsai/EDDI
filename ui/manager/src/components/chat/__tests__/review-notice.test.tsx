import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { ReviewNotice } from "@/components/chat/review-notice";

const AGENT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
const PROFILE = `*/agents/${AGENT_ID}/profile`;

/**
 * Before the first message, the person chatting learns whether the agent's
 * maintainers may read the conversation — the disclosure half of review.
 */
describe("ReviewNotice", () => {
  it("shows the notice of a version that opted into review", async () => {
    server.use(
      http.get(PROFILE, () => HttpResponse.json({ agentId: AGENT_ID, reviewNotice: "The team may read this chat." }))
    );

    renderWithProviders(<ReviewNotice agentId={AGENT_ID} />);

    expect(await screen.findByTestId("chat-review-notice")).toHaveTextContent("The team may read this chat.");
  });

  it("renders nothing for a version that did not opt in", async () => {
    let asked = false;
    server.use(
      http.get(PROFILE, () => {
        asked = true;
        return HttpResponse.json({ agentId: AGENT_ID, reviewNotice: null });
      })
    );

    renderWithProviders(<ReviewNotice agentId={AGENT_ID} />);

    await waitFor(() => expect(asked).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId("chat-review-notice")).not.toBeInTheDocument();
  });

  it("never stands in the way of a chat when the profile cannot be read", async () => {
    server.use(http.get(PROFILE, () => HttpResponse.json({ message: "not found" }, { status: 404 })));

    const { container } = renderWithProviders(<ReviewNotice agentId={AGENT_ID} />);

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(container).toBeEmptyDOMElement();
  });
});
