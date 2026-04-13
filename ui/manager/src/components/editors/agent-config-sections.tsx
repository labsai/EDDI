import { useState, useRef, useCallback, useEffect, memo } from "react";
import { useTranslation } from "react-i18next";
import {
  ShieldCheck,
  Fingerprint,
  Brain,
  Moon,
  Plus,
  X,
  Sparkles,
  ShieldBan,
  Cable,
} from "lucide-react";
import { useUpdateAgent } from "@/hooks/use-agents";
import type { Agent } from "@/lib/api/agents";
import { EditorSection } from "./editor-section";

// ─── Debounced input helpers ─────────────────────────────────────────────────

/** Text input that buffers locally and debounces the mutation to avoid per-keystroke PUTs */
function DebouncedInput({
  value,
  onCommit,
  delay = 600,
  ...rest
}: Omit<React.InputHTMLAttributes<HTMLInputElement>, "onChange"> & {
  value: string;
  onCommit: (v: string) => void;
  delay?: number;
}) {
  const [local, setLocal] = useState(value);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Sync from parent if the external value changes (version bump etc.)
  useEffect(() => setLocal(value), [value]);

  const commit = useCallback(
    (v: string) => {
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(() => onCommit(v), delay);
    },
    [onCommit, delay],
  );

  // Cleanup on unmount
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  return (
    <input
      {...rest}
      value={local}
      onChange={(e) => {
        setLocal(e.target.value);
        commit(e.target.value);
      }}
    />
  );
}

/** Number input that buffers locally and debounces the mutation */
function DebouncedNumberInput({
  value,
  onCommit,
  delay = 600,
  fallback = 0,
  ...rest
}: Omit<React.InputHTMLAttributes<HTMLInputElement>, "onChange" | "value"> & {
  value: number;
  onCommit: (v: number) => void;
  delay?: number;
  fallback?: number;
}) {
  const [local, setLocal] = useState(String(value));
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => setLocal(String(value)), [value]);

  const commit = useCallback(
    (raw: string) => {
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(() => {
        onCommit(parseInt(raw, 10) || fallback);
      }, delay);
    },
    [onCommit, delay, fallback],
  );

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  return (
    <input
      {...rest}
      type="number"
      value={local}
      onChange={(e) => {
        setLocal(e.target.value);
        commit(e.target.value);
      }}
    />
  );
}

// ─── Security & Identity ────────────────────────────────────────────────────

export const SecurityIdentitySection = memo(function SecurityIdentitySection({
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
    <EditorSection
      label={t("agentDetail.securityIdentity", "Security & Identity")}
      icon={ShieldCheck}
      accent="text-rose-500"
      variant="card"
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
            <DebouncedInput
              type="text"
              value={agent.identity?.agentDid ?? ""}
              onCommit={(v) =>
                updateAgent.mutate({
                  id: agentId,
                  version,
                  agent: {
                    ...agent,
                    identity: { ...agent.identity, agentDid: v || undefined },
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
    </EditorSection>
  );
});

// ─── Capabilities ───────────────────────────────────────────────────────────

export const CapabilitiesSection = memo(function CapabilitiesSection({
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
    <EditorSection
      label={t("agentDetail.capabilities", "Capabilities")}
      icon={Sparkles}
      accent="text-violet-500"
      variant="card"
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
                  <option value="low">{t("agentDetail.confidenceLow", "Low")}</option>
                  <option value="medium">{t("agentDetail.confidenceMedium", "Medium")}</option>
                  <option value="high">{t("agentDetail.confidenceHigh", "High")}</option>
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
    </EditorSection>
  );
});

// ─── User Memory ────────────────────────────────────────────────────────────

export const UserMemorySection = memo(function UserMemorySection({
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
    <EditorSection
      label={t("agentDetail.userMemory", "User Memory")}
      icon={Brain}
      accent="text-teal-500"
      variant="card"
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
                  <option value="self">{t("agentDetail.visibilitySelf", "self")}</option>
                  <option value="global">{t("agentDetail.visibilityGlobal", "global")}</option>
                </select>
              </div>
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">
                  {t("agentDetail.maxRecallEntries", "Max Recall")}
                </label>
                <DebouncedNumberInput
                  value={cfg.maxRecallEntries ?? 50}
                  onCommit={(v) => patchConfig({ maxRecallEntries: v })}
                  fallback={50}
                  className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              </div>
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">
                  {t("agentDetail.maxEntriesPerUser", "Max per User")}
                </label>
                <DebouncedNumberInput
                  value={cfg.maxEntriesPerUser ?? 500}
                  onCommit={(v) => patchConfig({ maxEntriesPerUser: v })}
                  fallback={500}
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
                  <option value="evict_oldest">{t("agentDetail.onCapEvict", "Evict Oldest")}</option>
                  <option value="reject">{t("agentDetail.onCapReject", "Reject")}</option>
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
                  <option value="most_recent">{t("agentDetail.recallMostRecent", "Most Recent")}</option>
                  <option value="most_relevant">{t("agentDetail.recallMostRelevant", "Most Relevant")}</option>
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
                  <DebouncedNumberInput value={guardrails.maxKeyLength ?? 100} onCommit={(v) => patchGuardrails({ maxKeyLength: v })} fallback={100} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.maxValueLength", "Max Value Length")}</label>
                  <DebouncedNumberInput value={guardrails.maxValueLength ?? 1000} onCommit={(v) => patchGuardrails({ maxValueLength: v })} fallback={1000} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.maxWritesPerTurn", "Max Writes/Turn")}</label>
                  <DebouncedNumberInput value={guardrails.maxWritesPerTurn ?? 10} onCommit={(v) => patchGuardrails({ maxWritesPerTurn: v })} fallback={10} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
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
                      <DebouncedInput type="text" value={dream.schedule ?? "0 3 * * *"} onCommit={(v) => patchDream({ schedule: v })} placeholder="0 3 * * *" className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground font-mono focus:outline-none focus:ring-1 focus:ring-ring" />
                    </div>
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.dreamLlmProvider", "LLM Provider")}</label>
                      <DebouncedInput type="text" value={dream.llmProvider ?? "anthropic"} onCommit={(v) => patchDream({ llmProvider: v })} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                    </div>
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.dreamLlmModel", "LLM Model")}</label>
                      <DebouncedInput type="text" value={dream.llmModel ?? "claude-sonnet-4-6"} onCommit={(v) => patchDream({ llmModel: v })} placeholder="claude-sonnet-4-6" className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground font-mono focus:outline-none focus:ring-1 focus:ring-ring" />
                    </div>
                  </div>
                  <div className="grid grid-cols-3 gap-3">
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.dreamMaxCost", "Max Cost/Run ($)")}</label>
                      <DebouncedNumberInput value={dream.maxCostPerRun ?? 5.00} onCommit={(v) => patchDream({ maxCostPerRun: v })} fallback={5} step={0.01} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                    </div>
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.dreamPruneDays", "Prune After (days)")}</label>
                      <DebouncedNumberInput value={dream.pruneStaleAfterDays ?? 90} onCommit={(v) => patchDream({ pruneStaleAfterDays: v })} fallback={90} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                    </div>
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">{t("agentDetail.dreamBatchSize", "Batch Size")}</label>
                      <DebouncedNumberInput value={dream.batchSize ?? 50} onCommit={(v) => patchDream({ batchSize: v })} fallback={50} className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
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
    </EditorSection>
  );
});

// ─── Memory Policy ──────────────────────────────────────────────────────────

export const MemoryPolicySection = memo(function MemoryPolicySection({
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

  const policy = agent.memoryPolicy ?? {};
  const swd = policy.strictWriteDiscipline ?? {};
  const enabled = swd.enabled ?? false;

  function patchSwd(updates: Record<string, unknown>) {
    updateAgent.mutate({
      id: agentId,
      version,
      agent: {
        ...agent,
        memoryPolicy: {
          ...policy,
          strictWriteDiscipline: { ...swd, ...updates },
        },
      },
    });
  }

  return (
    <EditorSection
      label={t("agentDetail.memoryPolicy", "Memory Policy")}
      icon={ShieldBan}
      accent="text-rose-500"
      variant="card"
      defaultOpen={enabled}
    >
      <div className="space-y-3" data-testid="memory-policy-section">
        <p className="text-[10px] text-muted-foreground leading-relaxed">
          {t("agentDetail.memoryPolicyDesc", "Strict Write Discipline governs what happens when property updates fail during a conversation step. Choose how the engine handles partially committed data.")}
        </p>

        <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
          <input
            type="checkbox"
            checked={enabled}
            onChange={() => patchSwd({ enabled: !enabled })}
            disabled={updateAgent.isPending}
            className="h-3.5 w-3.5 rounded border-input accent-primary"
            data-testid="swd-enable"
          />
          <ShieldBan className="h-3.5 w-3.5 text-rose-500" />
          {t("agentDetail.enableSwd", "Enable Strict Write Discipline")}
        </label>

        {enabled && (
          <div className="space-y-3 rounded-lg border border-rose-500/20 bg-rose-500/5 p-3">
            <div>
              <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                {t("agentDetail.onFailure", "On Failure Strategy")}
              </label>
              <select
                value={swd.onFailure ?? "digest"}
                onChange={(e) => patchSwd({ onFailure: e.target.value })}
                disabled={updateAgent.isPending}
                className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                data-testid="swd-on-failure"
              >
                <option value="digest">{t("agentDetail.onFailureDigest", "Digest — rollback changes, report summary to LLM")}</option>
                <option value="exclude_all">{t("agentDetail.onFailureExclude", "Exclude All — silently drop all property updates")}</option>
                <option value="keep_all">{t("agentDetail.onFailureKeep", "Keep All — keep changes and log warning")}</option>
              </select>
            </div>
          </div>
        )}
      </div>
    </EditorSection>
  );
});

// ─── Channel Connectors ────────────────────────────────────────────────────

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export const ChannelsSection = memo(function ChannelsSection(_props: {
  agent: Agent;
  agentId: string;
  version: number;
}) {
  const { t } = useTranslation();

  return (
    <EditorSection
      label={t("agentDetail.channels", "Channel Connectors")}
      icon={Cable}
      accent="text-indigo-500"
      variant="card"
      defaultOpen={false}
    >
      <div className="flex flex-col items-center justify-center py-10 text-center" data-testid="channels-section">
        <Cable className="h-10 w-10 text-muted-foreground/40" />
        <p className="mt-3 text-sm font-medium text-muted-foreground">
          {t("agentDetail.channelsComingSoon", "Coming Soon")}
        </p>
        <p className="mt-1 max-w-sm text-xs text-muted-foreground/70">
          {t("agentDetail.channelsComingSoonDesc", "Channel connectors (Slack, Teams, Webhooks) are being developed and will be available in an upcoming release.")}
        </p>
        <span className="mt-3 inline-flex items-center rounded-full bg-primary/10 px-3 py-1 text-xs font-semibold text-primary">
          {t("agentDetail.channelsComingSoonBadge", "In Development")}
        </span>
      </div>
    </EditorSection>
  );
});
