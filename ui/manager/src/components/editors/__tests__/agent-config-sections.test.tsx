import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import {
  SecurityIdentitySection,
  CapabilitiesSection,
  UserMemorySection,
  MemoryPolicySection,
  SessionManagementSection,
  ChannelsSection,
} from "@/components/editors/agent-config-sections";
import type { Agent } from "@/lib/api/agents";

// Mock hooks
const mockMutate = vi.fn();
vi.mock("@/hooks/use-agents", () => ({
  useUpdateAgent: () => ({
    mutate: mockMutate,
    isPending: false,
  }),
}));

vi.mock("@/hooks/use-capabilities", () => ({
  useSkills: () => ({
    data: ["summarization", "translation", "code_generation"],
  }),
}));

vi.mock("@/hooks/use-secrets", () => ({
  useSecrets: () => ({
    data: [{ keyName: "slack-bot-token", description: "Bot token" }, { keyName: "slack-signing-secret", description: "Signing secret" }],
    isLoading: false,
  }),
  useSecretsQuery: () => ({
    data: [{ keyName: "slack-bot-token", description: "Bot token" }, { keyName: "slack-signing-secret", description: "Signing secret" }],
    isLoading: false,
  }),
  useStoreSecret: () => ({
    mutate: vi.fn(),
    isPending: false,
  }),
  useVaultHealth: () => ({
    data: { available: true, healthy: true, backend: "internal" },
  }),
}));

const baseAgent: Agent = {
  description: "A test agent",
};

const agentId = "agent-123";
const version = 1;

describe("SecurityIdentitySection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders section label", () => {
    renderWithProviders(
      <SecurityIdentitySection agent={baseAgent} agentId={agentId} version={version} />
    );
    expect(screen.getByText("Security & Identity")).toBeInTheDocument();
  });

  it("shows identity section when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SecurityIdentitySection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Security & Identity"));
    expect(screen.getByTestId("identity-section")).toBeInTheDocument();
    expect(screen.getByText("Cryptographic Identity")).toBeInTheDocument();
    expect(screen.getByText("Agent DID")).toBeInTheDocument();
    expect(screen.getByText("Public Key")).toBeInTheDocument();
  });

  it("shows security toggles section", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SecurityIdentitySection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Security & Identity"));
    expect(screen.getByTestId("security-toggles")).toBeInTheDocument();
    expect(screen.getByText("Sign inter-agent (A2A) messages")).toBeInTheDocument();
    expect(screen.getByText("Sign MCP invocations")).toBeInTheDocument();
    expect(screen.getByText("Require peer verification")).toBeInTheDocument();
  });

  it("shows warning when a security flag is enabled", () => {
    const agentWithFlag: Agent = {
      ...baseAgent,
      security: { signInterAgentMessages: true },
    };
    renderWithProviders(
      <SecurityIdentitySection agent={agentWithFlag} agentId={agentId} version={version} />
    );
    // Section auto-opens since defaultOpen depends on security flags being set
    expect(screen.getByTestId("security-flag-warning")).toBeInTheDocument();
  });

  it("shows versioned keys when identity has keys array", () => {
    const agentWithKeys: Agent = {
      ...baseAgent,
      identity: {
        agentDid: "did:eddi:agent:123",
        publicKey: "abc123",
        keys: [
          { version: 1, publicKeyB64: "ABCDEF1234567890ABCDEF1234567890" },
        ],
      },
    };
    renderWithProviders(
      <SecurityIdentitySection agent={agentWithKeys} agentId={agentId} version={version} />
    );
    // Section auto-opens since agentDid is set
    expect(screen.getByText("Public Keys")).toBeInTheDocument();
    expect(screen.getByText(/Key v1/)).toBeInTheDocument();
  });

  it("shows legacy key when identity has publicKey but no keys array", async () => {
    const user = userEvent.setup();
    const agentWithLegacyKey: Agent = {
      ...baseAgent,
      identity: {
        publicKey: "legacyPubKey123456789012345678",
      },
    };
    renderWithProviders(
      <SecurityIdentitySection agent={agentWithLegacyKey} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Security & Identity"));
    expect(screen.getByText(/Legacy key/)).toBeInTheDocument();
  });

  it("shows confirmation dialog when enabling a security flag", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SecurityIdentitySection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Security & Identity"));
    // Click to enable signInterAgentMessages
    await user.click(screen.getByTestId("security-flag-signInterAgentMessages"));
    // Confirmation dialog should appear
    await waitFor(() => {
      expect(screen.getByText("Enable security flag?")).toBeInTheDocument();
    });
  });

  it("calls mutate after confirming security flag dialog", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SecurityIdentitySection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Security & Identity"));
    await user.click(screen.getByTestId("security-flag-signInterAgentMessages"));
    // Confirm dialog
    await waitFor(() => {
      expect(screen.getByText("Enable anyway")).toBeInTheDocument();
    });
    await user.click(screen.getByText("Enable anyway"));
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        id: agentId,
        version,
        agent: expect.objectContaining({
          security: expect.objectContaining({ signInterAgentMessages: true }),
        }),
      }),
      expect.anything()
    );
  });

  it("can toggle off a security flag without confirmation", async () => {
    const user = userEvent.setup();
    const agentWithFlag: Agent = {
      ...baseAgent,
      security: { signInterAgentMessages: true },
    };
    renderWithProviders(
      <SecurityIdentitySection agent={agentWithFlag} agentId={agentId} version={version} />
    );
    // Section auto-opens
    await user.click(screen.getByTestId("security-flag-signInterAgentMessages"));
    // Should call mutate directly without dialog
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        agent: expect.objectContaining({
          security: expect.objectContaining({ signInterAgentMessages: false }),
        }),
      }),
      expect.anything()
    );
  });
});

describe("CapabilitiesSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders section label", () => {
    renderWithProviders(
      <CapabilitiesSection agent={baseAgent} agentId={agentId} version={version} />
    );
    expect(screen.getByText("Capabilities")).toBeInTheDocument();
  });

  it("shows add capability button when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CapabilitiesSection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Capabilities"));
    expect(screen.getByTestId("capabilities-section")).toBeInTheDocument();
    expect(screen.getByTestId("add-capability-btn")).toBeInTheDocument();
  });

  it("shows capabilities when agent has them", async () => {
    const agentWithCaps: Agent = {
      ...baseAgent,
      capabilities: [
        { skill: "summarization", confidence: "high", attributes: {} },
      ],
    };
    renderWithProviders(
      <CapabilitiesSection agent={agentWithCaps} agentId={agentId} version={version} />
    );
    // defaultOpen since caps.length > 0
    expect(screen.getByTestId("capability-entry-0")).toBeInTheDocument();
    expect(screen.getByText("summarization")).toBeInTheDocument();
  });

  it("shows confidence select", async () => {
    const agentWithCaps: Agent = {
      ...baseAgent,
      capabilities: [
        { skill: "translation", confidence: "medium", attributes: {} },
      ],
    };
    renderWithProviders(
      <CapabilitiesSection agent={agentWithCaps} agentId={agentId} version={version} />
    );
    expect(screen.getByTestId("confidence-select-0")).toHaveValue("medium");
  });

  it("shows autocomplete input", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CapabilitiesSection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Capabilities"));
    expect(screen.getByTestId("skill-autocomplete-input")).toBeInTheDocument();
  });

  it("shows autocomplete dropdown when typing", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CapabilitiesSection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Capabilities"));
    await user.type(screen.getByTestId("skill-autocomplete-input"), "sum");
    expect(screen.getByTestId("skill-autocomplete-dropdown")).toBeInTheDocument();
    expect(screen.getByText("summarization")).toBeInTheDocument();
  });

  it("adds a capability via autocomplete selection", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CapabilitiesSection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Capabilities"));
    // Type a skill name to enable the add button
    await user.type(screen.getByTestId("skill-autocomplete-input"), "custom-skill");
    await user.click(screen.getByTestId("add-capability-btn"));
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        agent: expect.objectContaining({
          capabilities: [
            expect.objectContaining({
              skill: "custom-skill",
              confidence: "medium",
            }),
          ],
        }),
      })
    );
  });

  it("changes confidence level for a capability", async () => {
    const user = userEvent.setup();
    const agentWithCaps: Agent = {
      ...baseAgent,
      capabilities: [
        { skill: "summarization", confidence: "medium", attributes: {} },
      ],
    };
    renderWithProviders(
      <CapabilitiesSection agent={agentWithCaps} agentId={agentId} version={version} />
    );
    await user.selectOptions(screen.getByTestId("confidence-select-0"), "high");
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        agent: expect.objectContaining({
          capabilities: [
            expect.objectContaining({ confidence: "high" }),
          ],
        }),
      })
    );
  });

  it("removes a capability", async () => {
    const user = userEvent.setup();
    const agentWithCaps: Agent = {
      ...baseAgent,
      capabilities: [
        { skill: "translation", confidence: "medium", attributes: {} },
      ],
    };
    renderWithProviders(
      <CapabilitiesSection agent={agentWithCaps} agentId={agentId} version={version} />
    );
    await user.click(screen.getByTestId("remove-capability-0"));
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        agent: expect.objectContaining({
          capabilities: [],
        }),
      })
    );
  });

  it("shows capability attributes for entries with attributes", () => {
    const agentWithAttrs: Agent = {
      ...baseAgent,
      capabilities: [
        { skill: "translation", confidence: "high", attributes: { lang: "en" } },
      ],
    };
    renderWithProviders(
      <CapabilitiesSection agent={agentWithAttrs} agentId={agentId} version={version} />
    );
    // Capability entry is shown with attribute
    expect(screen.getByTestId("capability-entry-0")).toBeInTheDocument();
    expect(screen.getByText("translation")).toBeInTheDocument();
  });
});

describe("UserMemorySection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders section label", () => {
    renderWithProviders(
      <UserMemorySection agent={baseAgent} agentId={agentId} version={version} />
    );
    expect(screen.getByText("User Memory")).toBeInTheDocument();
  });

  it("shows enable memory tools checkbox when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <UserMemorySection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("User Memory"));
    expect(screen.getByTestId("user-memory-section")).toBeInTheDocument();
    expect(screen.getByText("Enable Memory Tools")).toBeInTheDocument();
  });

  it("shows memory config when enabled", async () => {
    const agentWithMemory: Agent = {
      ...baseAgent,
      enableMemoryTools: true,
    };
    renderWithProviders(
      <UserMemorySection agent={agentWithMemory} agentId={agentId} version={version} />
    );
    // defaultOpen since enabled = true
    expect(screen.getByText("Default Visibility")).toBeInTheDocument();
    expect(screen.getByText("Max Recall")).toBeInTheDocument();
    expect(screen.getByText("Max per User")).toBeInTheDocument();
    expect(screen.getByText("Write Guardrails")).toBeInTheDocument();
    expect(screen.getByText("Dream Consolidation")).toBeInTheDocument();
  });

  it("toggles memory tools on", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <UserMemorySection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("User Memory"));
    await user.click(screen.getByText("Enable Memory Tools"));
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        agent: expect.objectContaining({ enableMemoryTools: true }),
      })
    );
  });

  it("shows On Cap Reached and Recall Order selects when memory enabled", () => {
    const agentWithMemory: Agent = {
      ...baseAgent,
      enableMemoryTools: true,
    };
    renderWithProviders(
      <UserMemorySection agent={agentWithMemory} agentId={agentId} version={version} />
    );
    expect(screen.getByText("On Cap Reached")).toBeInTheDocument();
    expect(screen.getByText("Recall Order")).toBeInTheDocument();
  });

  it("shows dream consolidation config when dream is enabled", () => {
    const agentWithDream: Agent = {
      ...baseAgent,
      enableMemoryTools: true,
      userMemoryConfig: {
        dream: {
          enabled: true,
          schedule: "0 3 * * *",
        },
      },
    };
    renderWithProviders(
      <UserMemorySection agent={agentWithDream} agentId={agentId} version={version} />
    );
    // Dream config fields should be visible
    expect(screen.getByText("Schedule (cron)")).toBeInTheDocument();
    expect(screen.getByText("LLM Provider")).toBeInTheDocument();
    expect(screen.getByText("LLM Model")).toBeInTheDocument();
    expect(screen.getByText("Max Cost/Run ($)")).toBeInTheDocument();
    expect(screen.getByText("Prune After (days)")).toBeInTheDocument();
    expect(screen.getByText("Batch Size")).toBeInTheDocument();
    expect(screen.getByText("Detect contradictions")).toBeInTheDocument();
    expect(screen.getByText("Summarize interactions")).toBeInTheDocument();
  });

  it("shows guardrails fields when memory enabled", () => {
    const agentWithMemory: Agent = {
      ...baseAgent,
      enableMemoryTools: true,
    };
    renderWithProviders(
      <UserMemorySection agent={agentWithMemory} agentId={agentId} version={version} />
    );
    expect(screen.getByText("Max Key Length")).toBeInTheDocument();
    expect(screen.getByText("Max Value Length")).toBeInTheDocument();
    expect(screen.getByText("Max Writes/Turn")).toBeInTheDocument();
  });
});

describe("MemoryPolicySection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders section label", () => {
    renderWithProviders(
      <MemoryPolicySection agent={baseAgent} agentId={agentId} version={version} />
    );
    expect(screen.getByText("Memory Policy")).toBeInTheDocument();
  });

  it("shows SWD enable checkbox when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <MemoryPolicySection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Memory Policy"));
    expect(screen.getByTestId("memory-policy-section")).toBeInTheDocument();
    expect(screen.getByTestId("swd-enable")).toBeInTheDocument();
  });

  it("shows on failure strategy when SWD enabled", async () => {
    const agentWithSwd: Agent = {
      ...baseAgent,
      memoryPolicy: {
        strictWriteDiscipline: {
          enabled: true,
          onFailure: "digest",
        },
      },
    };
    renderWithProviders(
      <MemoryPolicySection agent={agentWithSwd} agentId={agentId} version={version} />
    );
    // defaultOpen since enabled
    expect(screen.getByTestId("swd-on-failure")).toBeInTheDocument();
  });

  it("toggles SWD on", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <MemoryPolicySection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Memory Policy"));
    await user.click(screen.getByTestId("swd-enable"));
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        agent: expect.objectContaining({
          memoryPolicy: expect.objectContaining({
            strictWriteDiscipline: expect.objectContaining({ enabled: true }),
          }),
        }),
      })
    );
  });
});

describe("SessionManagementSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders section label", () => {
    renderWithProviders(
      <SessionManagementSection agent={baseAgent} agentId={agentId} version={version} />
    );
    expect(screen.getByText("Session Management")).toBeInTheDocument();
  });

  it("shows auto-snapshot checkbox when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SessionManagementSection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Session Management"));
    expect(screen.getByTestId("session-management-section")).toBeInTheDocument();
    expect(screen.getByTestId("auto-snapshot-enabled")).toBeInTheDocument();
  });

  it("shows forking disabled section", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SessionManagementSection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Session Management"));
    expect(screen.getByTestId("forking-enabled")).toBeDisabled();
    expect(screen.getByText("Session Forking")).toBeInTheDocument();
    expect(screen.getByText(/coming soon/)).toBeInTheDocument();
  });

  it("toggles auto-snapshot on", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SessionManagementSection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Session Management"));
    await user.click(screen.getByTestId("auto-snapshot-enabled"));
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        agent: expect.objectContaining({
          sessionManagement: expect.objectContaining({
            autoSnapshot: expect.objectContaining({ enabled: true }),
          }),
        }),
      })
    );
  });

  it("shows trigger on buttons when auto-snapshot is enabled", () => {
    const agentWithSnapshot: Agent = {
      ...baseAgent,
      sessionManagement: {
        autoSnapshot: {
          enabled: true,
          triggerOn: ["before_tool"],
        },
      },
    };
    renderWithProviders(
      <SessionManagementSection agent={agentWithSnapshot} agentId={agentId} version={version} />
    );
    // Section auto-opens since autoSnapshot.enabled = true
    expect(screen.getByText("Trigger On")).toBeInTheDocument();
    expect(screen.getByText("Before tool execution")).toBeInTheDocument();
    expect(screen.getByText("Max Checkpoints")).toBeInTheDocument();
  });
});

describe("ChannelsSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders section label", () => {
    renderWithProviders(
      <ChannelsSection agent={baseAgent} agentId={agentId} version={version} />
    );
    expect(screen.getByText("Channel Connectors")).toBeInTheDocument();
  });

  it("shows empty state when no channels configured", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ChannelsSection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Channel Connectors"));
    expect(screen.getByText("No channels configured")).toBeInTheDocument();
  });

  it("shows add slack channel button when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ChannelsSection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Channel Connectors"));
    expect(screen.getByTestId("add-slack-channel-btn")).toBeInTheDocument();
  });

  it("adds a slack channel when button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ChannelsSection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Channel Connectors"));
    await user.click(screen.getByTestId("add-slack-channel-btn"));
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        agent: expect.objectContaining({
          channels: [
            expect.objectContaining({
              type: "slack",
              config: expect.objectContaining({ channelId: "" }),
            }),
          ],
        }),
      })
    );
  });

  it("shows slack channel card when channels configured", () => {
    const agentWithChannels: Agent = {
      ...baseAgent,
      channels: [
        {
          type: "slack",
          config: {
            channelId: "C0123ABCDEF",
            botToken: "${vault:slack-bot-token}",
            signingSecret: "${vault:slack-signing-secret}",
          },
        },
      ],
    };
    renderWithProviders(
      <ChannelsSection agent={agentWithChannels} agentId={agentId} version={version} />
    );
    // Section auto-opens since hasChannels = true
    expect(screen.getByTestId("slack-channel-0")).toBeInTheDocument();
    expect(screen.getByText("Slack Channel")).toBeInTheDocument();
    expect(screen.getByTestId("channel-id-0")).toHaveValue("C0123ABCDEF");
  });

  it("shows setup guide button", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ChannelsSection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Channel Connectors"));
    expect(screen.getByText("Slack Setup Guide")).toBeInTheDocument();
  });

  it("opens setup guide when clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ChannelsSection agent={baseAgent} agentId={agentId} version={version} />
    );
    await user.click(screen.getByText("Channel Connectors"));
    await user.click(screen.getByText("Slack Setup Guide"));
    expect(screen.getByText("Webhook URL")).toBeInTheDocument();
    expect(screen.getByText("Required Bot Token Scopes")).toBeInTheDocument();
  });

  it("shows channel remove button for each channel", () => {
    const agentWithChannels: Agent = {
      ...baseAgent,
      channels: [
        {
          type: "slack",
          config: {
            channelId: "C0123ABCDEF",
            botToken: "",
            signingSecret: "",
          },
        },
      ],
    };
    renderWithProviders(
      <ChannelsSection agent={agentWithChannels} agentId={agentId} version={version} />
    );
    expect(screen.getByTestId("remove-channel-0")).toBeInTheDocument();
  });
});
