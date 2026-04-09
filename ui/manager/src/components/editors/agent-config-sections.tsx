import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  ShieldCheck,
  Fingerprint,
  Brain,
  Moon,
  ChevronDown,
  ChevronRight,
  Plus,
  X,
  Sparkles,
} from "lucide-react";
import { useUpdateAgent } from "@/hooks/use-agents";
import type { Agent } from "@/lib/api/agents";

// ─── Collapsible Section wrapper ─────────────────────────────────────────────

function Section({
  label,
  icon: Icon,
  accent,
  defaultOpen = false,
  children,
}: {
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  accent?: string;
  defaultOpen?: boolean;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <section className="rounded-xl border bg-card shadow-sm">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="flex w-full items-center gap-2 border-b border-border p-5 text-start"
      >
        {open ? <ChevronDown className="h-4 w-4 text-muted-foreground" /> : <ChevronRight className="h-4 w-4 text-muted-foreground" />}
        <Icon className={`h-5 w-5 ${accent ?? "text-primary"}`} />
        <h2 className="text-lg font-semibold text-foreground">{label}</h2>
      </button>
      {open && <div className="p-5 space-y-4">{children}</div>}
    </section>
  );
}

// ─── Security & Identity ────────────────────────────────────────────────────

export function SecurityIdentitySection({
  agent,
  agentId,
  version,
}: {
  agent: Agent;
  agentId: string;
  version: number;
}) {
  const { t } = useTranslation();
  const updateAgent = useUpdateAgent();

  function toggle(field: "signInterAgentMessages" | "signMcpInvocations" | "requirePeerVerification") {
    const current = agent.security?.[field] ?? false;
    updateAgent.mutate({
      id: agentId,
      version,
      agent: {
        ...agent,
        security: { ...agent.security, [field]: !current },
      },
    });
  }

  return (
    <Section
      label={t("agentDetail.securityIdentity", "Security & Identity")}
      icon={ShieldCheck}
      accent="text-rose-500"
      defaultOpen={!!(agent.security?.signInterAgentMessages || agent.security?.signMcpInvocations || agent.identity?.agentDid)}
    >
      {/* Identity */}
      <div className="space-y-3" data-testid="identity-section">
        <h3 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          <Fingerprint className="h-3.5 w-3.5" />
          {t("agentDetail.identity", "Cryptographic Identity")}
        </h3>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1 block text-xs text-muted-foreground">
              {t("agentDetail.agentDid", "Agent DID")}
            </label>
            <input
              type="text"
              value={agent.identity?.agentDid ?? ""}
              onChange={(e) =>
                updateAgent.mutate({
                  id: agentId,
                  version,
                  agent: {
                    ...agent,
                    identity: { ...agent.identity, agentDid: e.target.value || undefined },
                  },
                })
              }
              placeholder="did:eddi:agent:..."
              className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground font-mono focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs text-muted-foreground">
              {t("agentDetail.publicKey", "Public Key")}
            </label>
            <input
              type="text"
              value={agent.identity?.publicKey ?? ""}
              readOnly
              placeholder={t("agentDetail.publicKeyHint", "Auto-generated on first signing")}
              className="h-8 w-full rounded-md border border-input bg-muted/50 px-2 text-xs text-muted-foreground font-mono"
            />
          </div>
        </div>
      </div>

      {/* Security toggles */}
      <div className="space-y-2 border-t border-border pt-3" data-testid="security-toggles">
        <h3 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          <ShieldCheck className="h-3.5 w-3.5" />
          {t("agentDetail.securityFlags", "Signing & Verification")}
        </h3>

        {([
          ["signInterAgentMessages", t("agentDetail.signA2A", "Sign inter-agent (A2A) messages"), t("agentDetail.signA2ADesc", "Cryptographically sign outbound A2A messages for tamper-proofing")],
          ["signMcpInvocations", t("agentDetail.signMcp", "Sign MCP invocations"), t("agentDetail.signMcpDesc", "Attach signatures to MCP tool calls for secure server verification")],
          ["requirePeerVerification", t("agentDetail.requirePeer", "Require peer verification"), t("agentDetail.requirePeerDesc", "Reject inbound A2A tasks from agents that can't prove their identity")],
        ] as const).map(([field, label, desc]) => (
          <label key={field} className="flex items-start gap-2.5 py-1">
            <input
              type="checkbox"
              checked={agent.security?.[field] ?? false}
              onChange={() => toggle(field)}
              disabled={updateAgent.isPending}
              className="mt-0.5 h-3.5 w-3.5 rounded border-input accent-primary"
            />
            <div>
              <span className="text-xs font-medium text-foreground">{label}</span>
              <p className="text-[10px] text-muted-foreground">{desc}</p>
            </div>
          </label>
        ))}
      </div>
    </Section>
  );
}

// ─── Capabilities ───────────────────────────────────────────────────────────

export function CapabilitiesSection({
  agent,
  agentId,
  version,
}: {
  agent: Agent;
  agentId: string;
  version: number;
}) {
  const { t } = useTranslation();
  const updateAgent = useUpdateAgent();
  const [newSkill, setNewSkill] = useState("");

  const caps = agent.capabilities ?? [];

  function addCapability() {
    if (!newSkill.trim()) return;
    const updated = [...caps, { skill: newSkill.trim(), confidence: "medium" }];
    updateAgent.mutate({ id: agentId, version, agent: { ...agent, capabilities: updated } });
    setNewSkill("");
  }

  function removeCapability(idx: number) {
    const updated = caps.filter((_, i) => i !== idx);
    updateAgent.mutate({ id: agentId, version, agent: { ...agent, capabilities: updated } });
  }

  function updateConfidence(idx: number, confidence: string) {
    const updated = caps.map((c, i) => (i === idx ? { ...c, confidence } : c));
    updateAgent.mutate({ id: agentId, version, agent: { ...agent, capabilities: updated } });
  }

  return (
    <Section
      label={t("agentDetail.capabilities", "Capabilities")}
      icon={Sparkles}
      accent="text-violet-500"
      defaultOpen={caps.length > 0}
    >
      <div className="space-y-3" data-testid="capabilities-section">
        <p className="text-[10px] text-muted-foreground">
          {t("agentDetail.capabilitiesDesc", "Declared skills for A2A agent card and capability registry. Other agents discover these when choosing delegation targets.")}
        </p>

        {caps.length > 0 && (
          <div className="space-y-1.5">
            {caps.map((cap, i) => (
              <div key={i} className="flex items-center gap-2 rounded-lg border border-border bg-background p-2">
                <span className="flex-1 text-xs font-medium text-foreground">{cap.skill}</span>
                <select
                  value={cap.confidence ?? "medium"}
                  onChange={(e) => updateConfidence(i, e.target.value)}
                  className="h-7 rounded border border-input bg-background px-1.5 text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                >
                  <option value="low">Low</option>
                  <option value="medium">Medium</option>
                  <option value="high">High</option>
                </select>
                <button
                  type="button"
                  onClick={() => removeCapability(i)}
                  disabled={updateAgent.isPending}
                  className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              </div>
            ))}
          </div>
        )}

        <div className="flex gap-1.5">
          <input
            type="text"
            value={newSkill}
            onChange={(e) => setNewSkill(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addCapability(); } }}
            placeholder={t("agentDetail.capabilityPlaceholder", "e.g. customer-support, code-review")}
            className="h-8 flex-1 rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
          <button
            type="button"
            onClick={addCapability}
            disabled={!newSkill.trim() || updateAgent.isPending}
            className="inline-flex h-8 items-center gap-1 rounded-md border border-input px-2 text-xs font-medium text-foreground transition-colors hover:bg-secondary disabled:opacity-50"
          >
            <Plus className="h-3 w-3" />
          </button>
        </div>
      </div>
    </Section>
  );
}

// ─── User Memory ────────────────────────────────────────────────────────────

export function UserMemorySection({
  agent,
  agentId,
  version,
}: {
  agent: Agent;
  agentId: string;
  version: number;
}) {
  const { t } = useTranslation();
  const updateAgent = useUpdateAgent();

  const enabled = agent.enableMemoryTools ?? false;
  const cfg = agent.userMemoryConfig ?? {};
  const dream = cfg.dream ?? {};
  const guardrails = cfg.guardrails ?? {};

  function patch(updates: Partial<Agent>) {
    updateAgent.mutate({ id: agentId, version, agent: { ...agent, ...updates } });
  }

  function patchConfig(updates: Record<string, unknown>) {
    patch({ userMemoryConfig: { ...cfg, ...updates } });
  }

  function patchDream(updates: Record<string, unknown>) {
    patchConfig({ dream: { ...dream, ...updates } });
  }

  function patchGuardrails(updates: Record<string, unknown>) {
    patchConfig({ guardrails: { ...guardrails, ...updates } });
  }

  return (
    <Section
      label={t("agentDetail.userMemory", "User Memory")}
      icon={Brain}
      accent="text-teal-500"
      defaultOpen={enabled}
    >
      <div className="space-y-4" data-testid="user-memory-section">
        <p className="text-[10px] text-muted-foreground leading-relaxed">
          {t("agentDetail.userMemoryDesc", "Persistent per-user memory with LLM tools (remember/recall/forget). Basic longTerm property persistence always works regardless of this toggle.")}
        </p>

        <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
          <input
            type="checkbox"
            checked={enabled}
            onChange={() => patch({ enableMemoryTools: !enabled })}
            disabled={updateAgent.isPending}
            className="h-3.5 w-3.5 rounded border-input accent-primary"
          />
          <Brain className="h-3.5 w-3.5 text-teal-500" />
          {t("agentDetail.enableMemoryTools", "Enable Memory Tools")}
        </label>

        {enabled && (
          <div className="space-y-4 rounded-lg border border-teal-500/20 bg-teal-500/5 p-4">
            {/* Core settings */}
            <div className="grid grid-cols-3 gap-3">
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">
                  {t("agentDetail.defaultVisibility", "Default Visibility")}
                </label>
                <select
                  value={cfg.defaultVisibility ?? "self"}
                  onChange={(e) => patchConfig({ defaultVisibility: e.target.value })}
                  className="h-7 w-full rounded border border-input bg-background px-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                >
                  <option value="self">self</option>
                  <option value="global">global</option>
                </select>
              </div>
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">
                  {t("agentDetail.maxRecallEntries", "Max Recall")}
                </label>
                <input
                  type="number"
                  value={cfg.maxRecallEntries ?? 50}
                  onChange={(e) => patchConfig({ maxRecallEntries: parseInt(e.target.value, 10) || 50 })}
                  className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              </div>
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">
                  {t("agentDetail.maxEntriesPerUser", "Max per User")}
                </label>
                <input
                  type="number"
                  value={cfg.maxEntriesPerUser ?? 500}
                  onChange={(e) => patchConfig({ maxEntriesPerUser: parseInt(e.target.value, 10) || 500 })}
                  className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">
                  {t("agentDetail.onCapReached", "On Cap Reached")}
                </label>
                <select
                  value={cfg.onCapReached ?? "evict_oldest"}
                  onChange={(e) => patchConfig({ onCapReached: e.target.value })}
                  className="h-7 w-full rounded border border-input bg-background px-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                >
                  <option value="evict_oldest">Evict Oldest</option>
                  <option value="reject">Reject</option>
                </select>
              </div>
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">
                  {t("agentDetail.recallOrder", "Recall Order")}
                </label>
                <select
                  value={cfg.recallOrder ?? "most_recent"}
                  onChange={(e) => patchConfig({ recallOrder: e.target.value })}
                  className="h-7 w-full rounded border border-input bg-background px-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                >
                  <option value="most_recent">Most Recent</option>
                  <option value="most_relevant">Most Relevant</option>
                </select>
              </div>
            </div>

            {/* Guardrails */}
            <div className="space-y-2 border-t border-teal-500/20 pt-3">
              <h4 className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                {t("agentDetail.guardrails", "Write Guardrails")}
              </h4>
              <div className="grid grid-cols-3 gap-3">
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.maxKeyLength", "Max Key Length")}</label>
                  <input type="number" value={guardrails.maxKeyLength ?? 100} onChange={(e) => patchGuardrails({ maxKeyLength: parseInt(e.target.value, 10) || 100 })} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.maxValueLength", "Max Value Length")}</label>
                  <input type="number" value={guardrails.maxValueLength ?? 1000} onChange={(e) => patchGuardrails({ maxValueLength: parseInt(e.target.value, 10) || 1000 })} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.maxWritesPerTurn", "Max Writes/Turn")}</label>
                  <input type="number" value={guardrails.maxWritesPerTurn ?? 10} onChange={(e) => patchGuardrails({ maxWritesPerTurn: parseInt(e.target.value, 10) || 10 })} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                </div>
              </div>
            </div>

            {/* Dream Consolidation */}
            <div className="space-y-2 border-t border-teal-500/20 pt-3">
              <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
                <input
                  type="checkbox"
                  checked={dream.enabled ?? false}
                  onChange={() => patchDream({ enabled: !(dream.enabled ?? false) })}
                  disabled={updateAgent.isPending}
                  className="h-3.5 w-3.5 rounded border-input accent-primary"
                />
                <Moon className="h-3.5 w-3.5 text-indigo-400" />
                {t("agentDetail.dreamConsolidation", "Dream Consolidation")}
              </label>
              <p className="text-[10px] text-muted-foreground ps-5">
                {t("agentDetail.dreamDesc", "Background LLM job that resolves contradictions, prunes stale entries, and optionally summarizes interactions.")}
              </p>

              {dream.enabled && (
                <div className="space-y-3 ps-5">
                  <div className="grid grid-cols-3 gap-3">
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.dreamSchedule", "Schedule (cron)")}</label>
                      <input type="text" value={dream.schedule ?? "0 3 * * *"} onChange={(e) => patchDream({ schedule: e.target.value })} placeholder="0 3 * * *" className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground font-mono focus:outline-none focus:ring-1 focus:ring-ring" />
                    </div>
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.dreamLlmProvider", "LLM Provider")}</label>
                      <input type="text" value={dream.llmProvider ?? "anthropic"} onChange={(e) => patchDream({ llmProvider: e.target.value })} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                    </div>
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.dreamLlmModel", "LLM Model")}</label>
                      <input type="text" value={dream.llmModel ?? "claude-sonnet-4-6"} onChange={(e) => patchDream({ llmModel: e.target.value })} placeholder="claude-sonnet-4-6" className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                    </div>
                  </div>
                  <div className="grid grid-cols-3 gap-3">
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.dreamMaxCost", "Max Cost/Run ($)")}</label>
                      <input type="number" step="0.01" value={dream.maxCostPerRun ?? 5.00} onChange={(e) => patchDream({ maxCostPerRun: parseFloat(e.target.value) || 5.00 })} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                    </div>
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.dreamPruneDays", "Prune After (days)")}</label>
                      <input type="number" value={dream.pruneStaleAfterDays ?? 90} onChange={(e) => patchDream({ pruneStaleAfterDays: parseInt(e.target.value, 10) || 90 })} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                    </div>
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.dreamBatchSize", "Batch Size")}</label>
                      <input type="number" value={dream.batchSize ?? 50} onChange={(e) => patchDream({ batchSize: parseInt(e.target.value, 10) || 50 })} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                    </div>
                  </div>
                  <div className="flex gap-4">
                    <label className="inline-flex items-center gap-1.5 text-xs text-foreground">
                      <input type="checkbox" checked={dream.detectContradictions ?? true} onChange={(e) => patchDream({ detectContradictions: e.target.checked })} className="h-3.5 w-3.5 rounded border-input accent-primary" />
                      {t("agentDetail.detectContradictions", "Detect contradictions")}
                    </label>
                    <label className="inline-flex items-center gap-1.5 text-xs text-foreground">
                      <input type="checkbox" checked={dream.summarizeInteractions ?? false} onChange={(e) => patchDream({ summarizeInteractions: e.target.checked })} className="h-3.5 w-3.5 rounded border-input accent-primary" />
                      {t("agentDetail.summarizeInteractions", "Summarize interactions")}
                    </label>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </Section>
  );
}
