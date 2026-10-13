import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CreateAgentDialog } from "@/components/agents/create-agent-dialog";
import { server } from "@/test/mocks/server";

const toastWarning = vi.fn();
const toastError = vi.fn();
const toastSuccess = vi.fn();
vi.mock("sonner", () => ({
  toast: {
    success: (...a: unknown[]) => toastSuccess(...a),
    error: (...a: unknown[]) => toastError(...a),
    warning: (...a: unknown[]) => toastWarning(...a),
  },
}));

describe("CreateAgentDialog — naming", () => {
  const onClose = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("requires a name", async () => {
    let posts = 0;
    server.use(
      http.post("*/agentstore/agents", () => {
        posts++;
        return new HttpResponse(null, { status: 201, headers: { Location: "/agentstore/agents/new1?version=1" } });
      }),
    );
    renderWithProviders(<CreateAgentDialog open onClose={onClose} />);
    const user = userEvent.setup();
    expect(screen.getByRole("button", { name: "Create" })).toBeDisabled();
    await user.type(screen.getByTestId("agent-name-input"), "   ");
    expect(screen.getByRole("button", { name: "Create" })).toBeDisabled();
    expect(posts).toBe(0);
    await user.type(screen.getByTestId("agent-name-input"), "Real name");
    expect(screen.getByRole("button", { name: "Create" })).toBeEnabled();
  });

  it("treats a failed naming step as created, with a warning, never as a failure to retry", async () => {
    let posts = 0;
    server.use(
      http.post("*/agentstore/agents", () => {
        posts++;
        return new HttpResponse(null, { status: 201, headers: { Location: "/agentstore/agents/new1?version=1" } });
      }),
      http.patch("*/descriptorstore/descriptors/:id", () => HttpResponse.json({ message: "down" }, { status: 500 })),
    );
    renderWithProviders(<CreateAgentDialog open onClose={onClose} />);
    const user = userEvent.setup();
    await user.type(screen.getByTestId("agent-name-input"), "My agent");
    await user.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(posts).toBe(1);
    expect(toastWarning).toHaveBeenCalledTimes(1);
    expect(toastError).not.toHaveBeenCalled();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
