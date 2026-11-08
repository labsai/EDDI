import { describe, it, expect, vi, beforeEach } from "vitest";
import { useState } from "react";
import { fireEvent, screen, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import {
  SecurityIdentitySection,
  CapabilitiesSection,
  UserMemorySection,
  MemoryPolicySection,
  LimitsSection,
  ContinuationSection,
  SessionManagementSection,
  ChannelsSection,
} from "@/components/editors/agent-config-sections";
import type { Agent } from "@/lib/api/agents";

const mockOnChange = vi.fn();

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


describe("SecurityIdentitySection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders section label", () => {
    renderWithProviders(
      <SecurityIdentitySection agent={baseAgent} onChange={mockOnChange} />
    );
    expect(screen.getByText("Security & Identity")).toBeInTheDocument();
  });

  it("shows identity section when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SecurityIdentitySection agent={baseAgent} onChange={mockOnChange} />
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
      <SecurityIdentitySection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Security & Identity"));
    expect(screen.getByTestId("security-toggles")).toBeInTheDocument();
    expect(screen.getByText("Sign group-conversation messages")).toBeInTheDocument();
    expect(screen.getByText("Require peer verification")).toBeInTheDocument();
    // The backend removed signMcpInvocations; a toggle for it saved nothing.
    expect(screen.queryByTestId("security-flag-signMcpInvocations")).not.toBeInTheDocument();
  });

  it("warns that a save will be refused when signing is on and the agent has no key", () => {
    const agentWithFlag: Agent = {
      ...baseAgent,
      security: { signInterAgentMessages: true },
    };
    renderWithProviders(
      <SecurityIdentitySection agent={agentWithFlag} onChange={mockOnChange} />
    );
    // Section auto-opens since defaultOpen depends on security flags being set
    expect(screen.getByTestId("security-flag-warning")).toHaveTextContent(/no signing key/);
  });

  it("does not warn when signing is on and the agent has a key", () => {
    const agentWithKey: Agent = {
      ...baseAgent,
      security: { signInterAgentMessages: true },
      identity: { keys: [{ version: 1, publicKeyB64: "MCowBQYDK2VwAyEAkey" }] },
    };
    renderWithProviders(
      <SecurityIdentitySection agent={agentWithKey} onChange={mockOnChange} />
    );
    expect(screen.getByTestId("security-toggles")).toBeInTheDocument();
    expect(screen.queryByTestId("security-flag-warning")).not.toBeInTheDocument();
  });

  it("offers no Generate button when the page passes no action (not an admin)", () => {
    renderWithProviders(
      <SecurityIdentitySection agent={{ ...baseAgent, security: { signInterAgentMessages: true } }} onChange={mockOnChange} />
    );
    expect(screen.queryByTestId("generate-signing-key")).not.toBeInTheDocument();
  });

  it("generates a key through the page's action", async () => {
    const user = userEvent.setup();
    const onGenerate = vi.fn();
    renderWithProviders(
      <SecurityIdentitySection
        agent={{ ...baseAgent, security: { signInterAgentMessages: true } }}
        onChange={mockOnChange}
        signingKeyAction={{ onGenerate, onRevoke: vi.fn(), pending: false }}
      />
    );
    await user.click(screen.getByTestId("generate-signing-key"));
    expect(onGenerate).toHaveBeenCalledTimes(1);
    // Generating saves on the server; it is not a draft edit.
    expect(mockOnChange).not.toHaveBeenCalled();
  });

  it("disables Generate and says why while the page cannot generate", () => {
    renderWithProviders(
      <SecurityIdentitySection
        agent={{ ...baseAgent, security: { signInterAgentMessages: true } }}
        onChange={mockOnChange}
        signingKeyAction={{ onGenerate: vi.fn(), onRevoke: vi.fn(), pending: false, disabledReason: "Save or discard your changes first" }}
      />
    );
    expect(screen.getByTestId("generate-signing-key")).toBeDisabled();
    expect(screen.getByTestId("generate-signing-key-hint")).toHaveTextContent("Save or discard your changes first");
  });

  const agentWithTwoKeys: Agent = {
    ...baseAgent,
    identity: {
      publicKey: "legacyPubKey123456789012345678",
      keys: [
        { version: 1, publicKeyB64: "ABCDEF1234567890ABCDEF1234567890", revoked: true },
        { version: 2, publicKeyB64: "GHIJKL1234567890ABCDEF1234567890" },
      ],
    },
  };

  it("revokes a key through the page's action, only after confirmation", async () => {
    const user = userEvent.setup();
    const onRevoke = vi.fn();
    renderWithProviders(
      <SecurityIdentitySection agent={agentWithTwoKeys} onChange={mockOnChange} signingKeyAction={{ onGenerate: vi.fn(), onRevoke, pending: false }} />
    );

    await user.click(screen.getByTestId("revoke-signing-key-2"));
    expect(onRevoke).not.toHaveBeenCalled();
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent(/Revoke signing key v2\?/);
    await user.click(within(dialog).getByRole("button", { name: "Revoke" }));

    expect(onRevoke).toHaveBeenCalledWith(2);
    // Revoking saves on the server; it is not a draft edit.
    expect(mockOnChange).not.toHaveBeenCalled();
  });

  it("marks a revoked key and offers no Revoke for it", () => {
    renderWithProviders(
      <SecurityIdentitySection agent={agentWithTwoKeys} onChange={mockOnChange} signingKeyAction={{ onGenerate: vi.fn(), onRevoke: vi.fn(), pending: false }} />
    );

    expect(screen.getByTestId("signing-key-revoked-1")).toHaveTextContent("Revoked");
    expect(screen.queryByTestId("revoke-signing-key-1")).not.toBeInTheDocument();
    expect(screen.getByTestId("revoke-signing-key-2")).toBeEnabled();
  });

  it("offers Revoke for the legacy key as version 0", async () => {
    const user = userEvent.setup();
    const onRevoke = vi.fn();
    renderWithProviders(
      <SecurityIdentitySection
        agent={{ ...baseAgent, identity: { publicKey: "legacyPubKey123456789012345678" } }}
        onChange={mockOnChange}
        signingKeyAction={{ onGenerate: vi.fn(), onRevoke, pending: false }}
      />
    );

    await user.click(screen.getByTestId("revoke-signing-key-0"));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Revoke" }));

    expect(onRevoke).toHaveBeenCalledWith(0);
  });

  it("disables Revoke while the page cannot change keys", () => {
    renderWithProviders(
      <SecurityIdentitySection
        agent={agentWithTwoKeys}
        onChange={mockOnChange}
        signingKeyAction={{ onGenerate: vi.fn(), onRevoke: vi.fn(), pending: false, disabledReason: "Save or discard your changes first" }}
      />
    );

    expect(screen.getByTestId("revoke-signing-key-2")).toBeDisabled();
  });

  it("shows the DID read-only: the identity is engine-owned, so a save would not keep an edit", () => {
    renderWithProviders(
      <SecurityIdentitySection agent={{ ...baseAgent, identity: { agentDid: "did:eddi:agent:123" } }} onChange={mockOnChange} />
    );

    expect(screen.getByTestId("identity-agent-did")).toHaveAttribute("readonly");
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
      <SecurityIdentitySection agent={agentWithKeys} onChange={mockOnChange} />
    );
    // Section auto-opens since agentDid is set
    expect(screen.getByText("Public Keys")).toBeInTheDocument();
    expect(screen.getByText(/Key v1/)).toBeInTheDocument();
  });

  it("shows legacy key when identity has publicKey but no keys array", () => {
    const agentWithLegacyKey: Agent = {
      ...baseAgent,
      identity: {
        publicKey: "legacyPubKey123456789012345678",
      },
    };
    renderWithProviders(
      <SecurityIdentitySection agent={agentWithLegacyKey} onChange={mockOnChange} />
    );
    // Section auto-opens: the agent has a key
    expect(screen.getByText(/Legacy key/)).toBeInTheDocument();
  });

  it("records a flag turned on in the draft, with no confirmation step", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SecurityIdentitySection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Security & Identity"));
    await user.click(screen.getByTestId("security-flag-signInterAgentMessages"));
    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
          security: expect.objectContaining({ signInterAgentMessages: true }),
        }));
  });

  it("can toggle off a security flag without confirmation", async () => {
    const user = userEvent.setup();
    const agentWithFlag: Agent = {
      ...baseAgent,
      security: { signInterAgentMessages: true },
    };
    renderWithProviders(
      <SecurityIdentitySection agent={agentWithFlag} onChange={mockOnChange} />
    );
    // Section auto-opens
    await user.click(screen.getByTestId("security-flag-signInterAgentMessages"));
    // Should call mutate directly without dialog
    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
          security: expect.objectContaining({ signInterAgentMessages: false }),
        }));
  });
});

describe("CapabilitiesSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders section label", () => {
    renderWithProviders(
      <CapabilitiesSection agent={baseAgent} onChange={mockOnChange} />
    );
    expect(screen.getByText("Capabilities")).toBeInTheDocument();
  });

  it("shows add capability button when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CapabilitiesSection agent={baseAgent} onChange={mockOnChange} />
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
      <CapabilitiesSection agent={agentWithCaps} onChange={mockOnChange} />
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
      <CapabilitiesSection agent={agentWithCaps} onChange={mockOnChange} />
    );
    expect(screen.getByTestId("confidence-select-0")).toHaveValue("medium");
  });

  it("shows autocomplete input", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CapabilitiesSection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Capabilities"));
    expect(screen.getByTestId("skill-autocomplete-input")).toBeInTheDocument();
  });

  it("shows autocomplete dropdown when typing", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CapabilitiesSection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Capabilities"));
    await user.type(screen.getByTestId("skill-autocomplete-input"), "sum");
    expect(screen.getByTestId("skill-autocomplete-dropdown")).toBeInTheDocument();
    expect(screen.getByText("summarization")).toBeInTheDocument();
  });

  it("adds a capability via autocomplete selection", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CapabilitiesSection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Capabilities"));
    // Type a skill name to enable the add button
    await user.type(screen.getByTestId("skill-autocomplete-input"), "custom-skill");
    await user.click(screen.getByTestId("add-capability-btn"));
    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
          capabilities: [
            expect.objectContaining({
              skill: "custom-skill",
              confidence: "medium",
            }),
          ],
        }));
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
      <CapabilitiesSection agent={agentWithCaps} onChange={mockOnChange} />
    );
    await user.selectOptions(screen.getByTestId("confidence-select-0"), "high");
    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
          capabilities: [
            expect.objectContaining({ confidence: "high" }),
          ],
        }));
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
      <CapabilitiesSection agent={agentWithCaps} onChange={mockOnChange} />
    );
    await user.click(screen.getByTestId("remove-capability-0"));
    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
          capabilities: [],
        }));
  });

  it("shows capability attributes for entries with attributes", () => {
    const agentWithAttrs: Agent = {
      ...baseAgent,
      capabilities: [
        { skill: "translation", confidence: "high", attributes: { lang: "en" } },
      ],
    };
    renderWithProviders(
      <CapabilitiesSection agent={agentWithAttrs} onChange={mockOnChange} />
    );
    // Capability entry is shown with attribute
    expect(screen.getByTestId("capability-entry-0")).toBeInTheDocument();
    expect(screen.getByText("translation")).toBeInTheDocument();
  });

  describe("attribute edits (review: never lose an edit, never resurrect a removed key)", () => {
    const withAttrs = (attributes: Record<string, string>): Agent => ({
      ...baseAgent,
      capabilities: [{ skill: "translation", confidence: "high", attributes }],
    });
    const sentAttributes = () =>
      mockOnChange.mock.calls.map(([arg]) => (arg as Agent).capabilities?.[0]?.attributes);

    /** Holds the draft the way the page does, so each edit renders back in. */
    function Harness({ initial }: { initial: Agent }) {
      const [agent, setAgent] = useState(initial);
      return (
        <CapabilitiesSection
          agent={agent}
          onChange={(next) => {
            mockOnChange(next);
            setAgent(next);
          }}
        />
      );
    }

    beforeEach(() => vi.clearAllMocks());

    it("keeps an edit when the editor is collapsed right after typing", async () => {
      const user = userEvent.setup();
      renderWithProviders(<Harness initial={withAttrs({ lang: "en" })} />);
      await user.click(screen.getByTestId("capability-attributes-toggle-0"));
      const input = screen.getByTestId("attribute-value-lang");
      await user.clear(input);
      await user.type(input, "de");
      await user.click(screen.getByTestId("capability-attributes-toggle-0"));
      const sent = sentAttributes();
      expect(sent[sent.length - 1]).toEqual({ lang: "de" });
    });

    it("does not write back an attribute removed right after it was edited", async () => {
      const user = userEvent.setup();
      renderWithProviders(<Harness initial={withAttrs({ lang: "en", tone: "dry" })} />);
      await user.click(screen.getByTestId("capability-attributes-toggle-0"));
      await user.type(screen.getByTestId("attribute-value-lang"), "x");
      await user.click(screen.getByTestId("attribute-remove-lang"));
      const sent = sentAttributes();
      expect(sent[sent.length - 1]).toEqual({ tone: "dry" });
    });
  });
});

describe("UserMemorySection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders section label", () => {
    renderWithProviders(
      <UserMemorySection agent={baseAgent} onChange={mockOnChange} />
    );
    expect(screen.getByText("User Memory")).toBeInTheDocument();
  });

  it("shows enable memory tools checkbox when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <UserMemorySection agent={baseAgent} onChange={mockOnChange} />
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
      <UserMemorySection agent={agentWithMemory} onChange={mockOnChange} />
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
      <UserMemorySection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("User Memory"));
    await user.click(screen.getByText("Enable Memory Tools"));
    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({ enableMemoryTools: true }));
  });

  it("shows On Cap Reached and Recall Order selects when memory enabled", () => {
    const agentWithMemory: Agent = {
      ...baseAgent,
      enableMemoryTools: true,
    };
    renderWithProviders(
      <UserMemorySection agent={agentWithMemory} onChange={mockOnChange} />
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
      <UserMemorySection agent={agentWithDream} onChange={mockOnChange} />
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

  it("commits a typed 0 as 0, not as the field's fallback", async () => {
    // "Prune After (days)" = 0 means "never prune"; it used to be saved as 90
    // (`parseFloat("0") || fallback`) while the box kept showing 0.
    const user = userEvent.setup();
    const onChange = vi.fn();
    const agentWithDream: Agent = {
      ...baseAgent,
      enableMemoryTools: true,
      userMemoryConfig: { dream: { enabled: true, schedule: "0 3 * * *", pruneStaleAfterDays: 45 } },
    };
    renderWithProviders(<UserMemorySection agent={agentWithDream} onChange={onChange} />);
    const input = screen.getByText("Prune After (days)").parentElement!.querySelector("input")!;
    await user.clear(input);
    onChange.mockClear();
    await user.type(input, "0");
    const last = onChange.mock.calls[onChange.mock.calls.length - 1]![0] as Agent;
    expect(last.userMemoryConfig?.dream?.pruneStaleAfterDays).toBe(0);
  });

  it("falls back only for an empty number field", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const agentWithDream: Agent = {
      ...baseAgent,
      enableMemoryTools: true,
      userMemoryConfig: { dream: { enabled: true, schedule: "0 3 * * *", pruneStaleAfterDays: 45 } },
    };
    renderWithProviders(<UserMemorySection agent={agentWithDream} onChange={onChange} />);
    const input = screen.getByText("Prune After (days)").parentElement!.querySelector("input")!;
    await user.clear(input);
    const last = onChange.mock.calls[onChange.mock.calls.length - 1]![0] as Agent;
    expect(last.userMemoryConfig?.dream?.pruneStaleAfterDays).toBe(90);
  });

  it("defaults Max Cost/Run to the backend's 0.50, not 5", () => {
    const agentWithDream: Agent = {
      ...baseAgent,
      enableMemoryTools: true,
      userMemoryConfig: { dream: { enabled: true, schedule: "0 3 * * *" } },
    };
    renderWithProviders(<UserMemorySection agent={agentWithDream} onChange={mockOnChange} />);
    const input = screen.getByText("Max Cost/Run ($)").parentElement!.querySelector("input")!;
    expect(input).toHaveValue(0.5);
  });

  it("shows guardrails fields when memory enabled", () => {
    const agentWithMemory: Agent = {
      ...baseAgent,
      enableMemoryTools: true,
    };
    renderWithProviders(
      <UserMemorySection agent={agentWithMemory} onChange={mockOnChange} />
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
      <MemoryPolicySection agent={baseAgent} onChange={mockOnChange} />
    );
    expect(screen.getByText("Memory Policy")).toBeInTheDocument();
  });

  it("shows SWD enable checkbox when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <MemoryPolicySection agent={baseAgent} onChange={mockOnChange} />
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
      <MemoryPolicySection agent={agentWithSwd} onChange={mockOnChange} />
    );
    // defaultOpen since enabled
    expect(screen.getByTestId("swd-on-failure")).toBeInTheDocument();
  });

  it("toggles SWD on", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <MemoryPolicySection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Memory Policy"));
    await user.click(screen.getByTestId("swd-enable"));
    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
          memoryPolicy: expect.objectContaining({
            strictWriteDiscipline: expect.objectContaining({ enabled: true }),
          }),
        }));
  });
});

describe("SessionManagementSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders section label", () => {
    renderWithProviders(
      <SessionManagementSection agent={baseAgent} onChange={mockOnChange} />
    );
    expect(screen.getByText("Session Management")).toBeInTheDocument();
  });

  it("shows auto-snapshot checkbox when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SessionManagementSection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Session Management"));
    expect(screen.getByTestId("session-management-section")).toBeInTheDocument();
    expect(screen.getByTestId("auto-snapshot-enabled")).toBeInTheDocument();
  });

  it("shows forking disabled section", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SessionManagementSection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Session Management"));
    expect(screen.getByTestId("forking-enabled")).toBeDisabled();
    expect(screen.getByText("Session Forking")).toBeInTheDocument();
    expect(screen.getByText(/coming soon/)).toBeInTheDocument();
  });

  it("toggles auto-snapshot on", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SessionManagementSection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Session Management"));
    await user.click(screen.getByTestId("auto-snapshot-enabled"));
    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
          sessionManagement: expect.objectContaining({
            autoSnapshot: expect.objectContaining({ enabled: true }),
          }),
        }));
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
      <SessionManagementSection agent={agentWithSnapshot} onChange={mockOnChange} />
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
      <ChannelsSection agent={baseAgent} onChange={mockOnChange} />
    );
    expect(screen.getByText("Channel Connectors")).toBeInTheDocument();
  });

  it("shows empty state when no channels configured", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ChannelsSection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Channel Connectors"));
    expect(screen.getByText("No channels configured")).toBeInTheDocument();
  });

  it("shows add slack channel button when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ChannelsSection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Channel Connectors"));
    expect(screen.getByTestId("add-slack-channel-btn")).toBeInTheDocument();
  });

  it("adds a slack channel when button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ChannelsSection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Channel Connectors"));
    await user.click(screen.getByTestId("add-slack-channel-btn"));
    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
          channels: [
            expect.objectContaining({
              type: "slack",
              config: expect.objectContaining({ channelId: "" }),
            }),
          ],
        }));
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
      <ChannelsSection agent={agentWithChannels} onChange={mockOnChange} />
    );
    // Section auto-opens since hasChannels = true
    expect(screen.getByTestId("slack-channel-0")).toBeInTheDocument();
    expect(screen.getByText("Slack Channel")).toBeInTheDocument();
    expect(screen.getByTestId("channel-id-0")).toHaveValue("C0123ABCDEF");
  });

  it("shows setup guide button", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ChannelsSection agent={baseAgent} onChange={mockOnChange} />
    );
    await user.click(screen.getByText("Channel Connectors"));
    expect(screen.getByText("Slack Setup Guide")).toBeInTheDocument();
  });

  it("opens setup guide when clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ChannelsSection agent={baseAgent} onChange={mockOnChange} />
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
      <ChannelsSection agent={agentWithChannels} onChange={mockOnChange} />
    );
    expect(screen.getByTestId("remove-channel-0")).toBeInTheDocument();
  });
});

describe("LimitsSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("is collapsed with an empty field when no ceiling is set", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LimitsSection agent={baseAgent} onChange={mockOnChange} />);
    await user.click(screen.getByText("Limits"));
    expect(screen.getByTestId("max-cost-per-conversation")).toHaveValue(null);
  });

  it("opens by default and shows the stored ceiling, 0 included", () => {
    renderWithProviders(
      <LimitsSection agent={{ ...baseAgent, maxCostPerConversationUsd: 0 }} onChange={mockOnChange} />,
    );
    expect(screen.getByTestId("max-cost-per-conversation")).toHaveValue(0);
  });

  it("writes the amount into the draft once one is typed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LimitsSection agent={baseAgent} onChange={mockOnChange} />);
    await user.click(screen.getByText("Limits"));
    await user.type(screen.getByTestId("max-cost-per-conversation"), "2.5");
    expect(mockOnChange).toHaveBeenLastCalledWith({ ...baseAgent, maxCostPerConversationUsd: 2.5 });
  });

  it("removes the field when cleared, rather than storing null or -1", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LimitsSection agent={{ ...baseAgent, maxCostPerConversationUsd: 5 }} onChange={mockOnChange} />,
    );
    await user.clear(screen.getByTestId("max-cost-per-conversation"));
    const sent = (mockOnChange.mock.lastCall as [Agent])[0];
    expect("maxCostPerConversationUsd" in sent).toBe(false);
  });

  it("rejects a negative amount and leaves the draft alone", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LimitsSection agent={baseAgent} onChange={mockOnChange} />);
    await user.click(screen.getByText("Limits"));
    await user.type(screen.getByTestId("max-cost-per-conversation"), "-3");
    expect(screen.getByTestId("max-cost-per-conversation-invalid")).toBeInTheDocument();
    expect(mockOnChange).not.toHaveBeenCalled();
  });

  it("says that unpriced models are not bounded", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LimitsSection agent={baseAgent} onChange={mockOnChange} />);
    await user.click(screen.getByText("Limits"));
    expect(screen.getByText(/Only priced models count/)).toBeInTheDocument();
  });

  it("reports an unusable amount to the page, and clears the report once it is fixed", async () => {
    const user = userEvent.setup();
    const onValidityChange = vi.fn();
    renderWithProviders(
      <LimitsSection agent={{ ...baseAgent, maxCostPerConversationUsd: 5 }} onChange={mockOnChange} onValidityChange={onValidityChange} />,
    );
    expect(onValidityChange).toHaveBeenLastCalledWith(false);
    const input = screen.getByTestId("max-cost-per-conversation");
    await user.clear(input);
    await user.type(input, "-3");
    expect(onValidityChange).toHaveBeenLastCalledWith(true);
    await user.clear(input);
    await user.type(input, "3");
    expect(onValidityChange).toHaveBeenLastCalledWith(false);
  });
});

describe("ContinuationSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("is collapsed and off when the agent sets nothing", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ContinuationSection agent={baseAgent} onChange={mockOnChange} />);
    await user.click(screen.getByText("Self-continuation"));
    expect(screen.getByTestId("max-continuations")).toHaveValue(null);
    expect(screen.getByTestId("continuation-input")).toHaveValue("");
  });

  it("opens by default and shows the stored count and input", () => {
    renderWithProviders(
      <ContinuationSection agent={{ ...baseAgent, maxContinuations: 20, continuationInput: "go on" }} onChange={mockOnChange} />,
    );
    expect(screen.getByTestId("max-continuations")).toHaveValue(20);
    expect(screen.getByTestId("continuation-input")).toHaveValue("go on");
  });

  it("writes a whole number into the draft", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ContinuationSection agent={baseAgent} onChange={mockOnChange} />);
    await user.click(screen.getByText("Self-continuation"));
    await user.type(screen.getByTestId("max-continuations"), "12");
    expect(mockOnChange).toHaveBeenLastCalledWith({ ...baseAgent, maxContinuations: 12 });
  });

  it("removes the field when cleared, rather than storing 0 or null", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ContinuationSection agent={{ ...baseAgent, maxContinuations: 5 }} onChange={mockOnChange} />);
    await user.clear(screen.getByTestId("max-continuations"));
    const sent = (mockOnChange.mock.lastCall as [Agent])[0];
    expect("maxContinuations" in sent).toBe(false);
  });

  it("refuses a count above the backend cap, leaves the draft alone and tells the page", async () => {
    const user = userEvent.setup();
    const onValidityChange = vi.fn();
    renderWithProviders(<ContinuationSection agent={baseAgent} onChange={mockOnChange} onValidityChange={onValidityChange} />);
    await user.click(screen.getByText("Self-continuation"));
    await user.type(screen.getByTestId("max-continuations"), "10001");
    expect(screen.getByTestId("max-continuations-invalid")).toBeInTheDocument();
    expect(onValidityChange).toHaveBeenLastCalledWith(true);
    expect(mockOnChange).not.toHaveBeenLastCalledWith(expect.objectContaining({ maxContinuations: 10001 }));
  });

  it("a half-typed number keeps the stored count and blocks Save, instead of switching continuation off", () => {
    const onValidityChange = vi.fn();
    renderWithProviders(
      <ContinuationSection agent={{ ...baseAgent, maxContinuations: 5 }} onChange={mockOnChange} onValidityChange={onValidityChange} />,
    );
    const input = screen.getByTestId("max-continuations");
    // What a browser reports for "5e" in a number input: an empty value, with badInput set.
    Object.defineProperty(input, "validity", { value: { badInput: true }, configurable: true });
    fireEvent.change(input, { target: { value: "" } });

    expect(mockOnChange).not.toHaveBeenCalled();
    expect(onValidityChange).toHaveBeenLastCalledWith(true);
    expect(screen.getByTestId("max-continuations-invalid")).toBeInTheDocument();
  });

  it("writes the input as a literal and drops it when emptied", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ContinuationSection agent={{ ...baseAgent, continuationInput: "x" }} onChange={mockOnChange} />);
    await user.click(screen.getByText("Self-continuation"));
    await user.clear(screen.getByTestId("continuation-input"));
    const sent = (mockOnChange.mock.lastCall as [Agent])[0];
    expect("continuationInput" in sent).toBe(false);
  });
});
