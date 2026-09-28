import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { RequestAccessPanel } from "@/components/workspaces/request-access-panel";

const RESOURCE_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
const REQUESTS = `*/descriptorstore/descriptors/${RESOURCE_ID}/shares/requests`;
const PROFILE = `*/agents/${RESOURCE_ID}/profile`;

/**
 * What replaced "Something went wrong — Retry" on a resource the caller may not
 * open. The Retry could never succeed; this says why and offers the way out.
 */
describe("RequestAccessPanel", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => vi.restoreAllMocks());

  function captureRequests(outcome = "SENT") {
    const sent = vi.fn();
    server.use(
      http.post(REQUESTS, ({ request }) => {
        const params = new URL(request.url).searchParams;
        sent(params.get("level"), params.get("message"));
        return HttpResponse.json({ outcome });
      })
    );
    return sent;
  }

  it("asks the owner for the chosen level, with the message", async () => {
    const sent = captureRequests();
    renderWithProviders(<RequestAccessPanel resourceId={RESOURCE_ID} />);

    await userEvent.selectOptions(screen.getByTestId("request-access-level"), "EDIT");
    await userEvent.type(screen.getByTestId("request-access-message"), "fixing the prompt");
    await userEvent.click(screen.getByTestId("request-access-submit"));

    await waitFor(() => expect(sent).toHaveBeenCalledWith("EDIT", "fixing the prompt"));
    expect(await screen.findByTestId("request-access-sent")).toBeInTheDocument();
  });

  it("does not claim a named person was told", async () => {
    // The server answers an id that matches nothing exactly like a delivered
    // request, so the page must not reveal more than the server does.
    captureRequests();
    renderWithProviders(<RequestAccessPanel resourceId={RESOURCE_ID} />);

    await userEvent.click(screen.getByTestId("request-access-submit"));

    const sent = await screen.findByTestId("request-access-sent");
    expect(sent).toHaveTextContent(/If this exists/);
  });

  it("recognises an agent the caller may already chat with, and offers the chat", async () => {
    // Shared "for chatting" is USE: the configuration stays closed by design,
    // which is not the same as having no access.
    server.use(http.get(PROFILE, () => HttpResponse.json({ agentId: RESOURCE_ID, name: "Support" })));
    const sent = captureRequests();
    renderWithProviders(<RequestAccessPanel resourceId={RESOURCE_ID} isAgent />);

    expect(await screen.findByTestId("request-access-open-chat")).toHaveAttribute(
      "href",
      expect.stringContaining(`/chat/production/${RESOURCE_ID}`)
    );
    // Asking for chat access they already hold would be a no-op request.
    const options = Array.from(screen.getByTestId("request-access-level").querySelectorAll("option")).map((o) => o.value);
    expect(options).toEqual(["VIEW", "EDIT"]);

    await userEvent.click(screen.getByTestId("request-access-submit"));
    await waitFor(() => expect(sent).toHaveBeenCalledWith("VIEW", null));
  });

  it("does not offer a chat for an agent the caller cannot reach at all", async () => {
    server.use(http.get(PROFILE, () => HttpResponse.json({ message: "forbidden" }, { status: 403 })));
    renderWithProviders(<RequestAccessPanel resourceId={RESOURCE_ID} isAgent />);

    expect(screen.getByTestId("request-access-panel")).toHaveTextContent("You don't have access to this");
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId("request-access-open-chat")).not.toBeInTheDocument();
  });
});
