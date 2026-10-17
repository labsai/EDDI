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

  it("promises stale-request refusal only for the timestamped eddi-v1 scheme", () => {
    const { rerender } = renderWithProviders(<WebhookSigningInstructions scheme="eddi-v1" />);
    expect(screen.getByTestId("webhook-replay-note").textContent).toContain("5 minutes");
    for (const scheme of ["github", "generic-hmac"]) {
      rerender(<WebhookSigningInstructions scheme={scheme} />);
      const note = screen.getByTestId("webhook-replay-note").textContent ?? "";
      expect(note).toContain("signs no timestamp");
      expect(note).not.toContain("5 minutes");
      expect(screen.getByTestId("webhook-signing-instructions").textContent).not.toMatch(/stale/i);
    }
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

  it("explains the chosen scheme and labels the generic-hmac fields", () => {
    const { rerender } = renderWithProviders(<WebhookPlatformFields draft={draft({})} onChange={vi.fn()} />);
    expect(screen.getByTestId("webhook-scheme-hint")).toHaveTextContent("X-Eddi-Signature");
    expect(screen.queryByLabelText("Signature header")).toBeNull();

    rerender(<WebhookPlatformFields draft={draft({ signatureScheme: "generic-hmac" })} onChange={vi.fn()} />);
    expect(screen.getByTestId("webhook-scheme-hint")).toHaveTextContent("sent in X-Signature unless");
    expect(screen.getByLabelText("Signature header")).toBeInTheDocument();
    expect(screen.getByLabelText("Signature encoding")).toBeInTheDocument();
    expect(screen.getByLabelText("Signature prefix")).toBeInTheDocument();
  });

  it("labels the event type fields", () => {
    renderWithProviders(<WebhookPlatformFields draft={draft({})} onChange={vi.fn()} />);
    expect(screen.getByLabelText("Event type header")).toBeInTheDocument();
    expect(screen.getByLabelText("Event type JSON pointer")).toBeInTheDocument();
  });
});
