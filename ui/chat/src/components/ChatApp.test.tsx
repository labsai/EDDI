import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import { ChatApp } from "./ChatApp";
import { ChatLocationProvider } from "./ChatLocationProvider";
import { useChatLocation } from "@/location";

vi.mock("./ChatWidget", () => ({ ChatWidget: () => <span data-testid="screen">widget</span> }));
vi.mock("./ChatRoute", () => ({ ChatRoute: () => <span data-testid="screen">route</span> }));

function Where() {
  return <span data-testid="where">{useChatLocation().location.pathname}</span>;
}

function renderAt(path: string) {
  return render(
    <ChatLocationProvider initialPath={path}>
      <ChatApp />
      <Where />
    </ChatLocationProvider>,
  );
}

describe("ChatApp", () => {
  it("renders the widget for a managed route", () => {
    renderAt("/chat/managed/support/user-7");
    expect(screen.getByTestId("screen").textContent).toBe("widget");
  });

  it.each(["/chat/production/agent-1", "/chat/production/agent-1/user-7"])(
    "sends a direct route through ChatRoute: %s",
    (path) => {
      renderAt(path);
      expect(screen.getByTestId("screen").textContent).toBe("route");
    },
  );

  it.each(["/", "/chat", "/chat/x/y/z/w"])(
    "replaces an unknown path with the default agent: %s",
    async (path) => {
      renderAt(path);
      expect(await screen.findByTestId("screen")).toHaveTextContent("route");
      expect(screen.getByTestId("where").textContent).toBe("/chat/production/default");
    },
  );
});
