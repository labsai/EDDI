import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { WebhookPlatformFields, WebhookSigningInstructions } from "@/components/channels/webhook-settings";
import { webhookEndpointUrl, type ChannelIntegrationConfiguration } from "@/lib/api/channels";

function draft(platformConfig: Record<string, string>): ChannelIntegrationConfiguration {
  return {
    name: "hooks",
    channelType: "webhook",
    platformConfig,
    targets: [],
    defaultTargetName: "default",
  } as unknown as ChannelIntegrationConfiguration;
}

describe("webhook settings", () => {
  it("builds the endpoint from the integration id", () => {
    expect(webhookEndpointUrl("https://eddi.example", "0123456789abcdef01234567")).toBe(
      "https://eddi.example/integrations/webhook/0123456789abcdef01234567",
    );
  });

  it("shows the header format of the configured scheme", () => {
    const { rerender } = renderWithProviders(<WebhookSigningInstructions scheme="eddi-v1" />);
    expect(screen.getByTestId("webhook-signing-instructions").textContent).toContain("X-Eddi-Signature: t=");
    rerender(<WebhookSigningInstructions scheme="github" />);
    expect(screen.getByTestId("webhook-signing-instructions").textContent).toContain("X-Hub-Signature-256: sha256=");
  });

  it("asks for a correlation key only for the correlate strategy", async () => {
    const onChange = vi.fn();
    const { rerender } = renderWithProviders(<WebhookPlatformFields draft={draft({})} onChange={onChange} />);
    expect(screen.queryByTestId("webhook-correlation-path")).toBeNull();

    await userEvent.setup().selectOptions(screen.getByTestId("webhook-strategy-select"), "correlate");
    expect(onChange).toHaveBeenLastCalledWith({ conversationStrategy: "correlate" });

    rerender(<WebhookPlatformFields draft={draft({ conversationStrategy: "correlate" })} onChange={onChange} />);
    expect(screen.getByTestId("webhook-correlation-path")).toBeInTheDocument();
  });

  it("removes a key when its field is cleared, so the backend default applies", async () => {
    const onChange = vi.fn();
    renderWithProviders(<WebhookPlatformFields draft={draft({ signingSecret: "${vault:k}", inputTemplate: "x" })} onChange={onChange} />);
    await userEvent.setup().clear(screen.getByTestId("webhook-input-template"));
    expect(onChange).toHaveBeenLastCalledWith({ signingSecret: "${vault:k}" });
  });
});
