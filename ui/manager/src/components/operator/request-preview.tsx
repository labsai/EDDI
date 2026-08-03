import { useTranslation } from "react-i18next";
import { ShieldAlert } from "lucide-react";
import { detectEscalationFlags } from "@/lib/operator/escalation-flags";
import type { ResolvedRequestPreview } from "@/lib/api/hitl";

/** Approver-facing text per escalating setting — see `escalation-flags.ts`. */
const ESCALATION_TEXT: Record<string, string> = {
  dynamicAgentCreation:
    "This group may create new agents while it runs. Those agents are not themselves approval-gated.",
  dynamicAgentRecruitment: "This group may pull other existing agents into its discussions.",
  autoApproveOnTimeout:
    "Approvals for this resource are granted automatically when they time out, with nobody watching.",
  agentCreatedWithoutGate:
    "This agent is being created with no approval gate. Every write it later makes will execute unsupervised.",
  agentCreatedWithBroadEndpoints:
    "This agent is being created with write access to its API — not limited to reads.",
  agentCreatedWithExternalTools:
    "This agent is being created with every tool an external MCP server offers. That server decides what those are, and can change them later.",
};

interface RequestPreviewProps {
  preview: ResolvedRequestPreview;
  /** Whether this preview is re-checked immediately before execution — see
   *  `PendingToolCallView.requestPinned`. Controls which badge is shown; the
   *  preview content itself renders identically either way. */
  pinned: boolean;
  callId: string;
}

/**
 * The approver's honest view of what a gated call actually resolves to.
 *
 * Backend-verified (`IApiCallExecutor#resolve`), as opposed to
 * `reconstructEndpoint`'s client-side `operationId` guess — used as this
 * component's fallback only when a call carries no preview at all (see
 * `OperatorPage.renderCallExtra`).
 */
export function RequestPreview({ preview, pinned, callId }: RequestPreviewProps) {
  const { t } = useTranslation();
  const queryEntries = Object.entries(preview.queryParams ?? {});
  const headerEntries = Object.entries(preview.headers ?? {});
  // Above the body, not inside it: the point is that an approver skimming JSON
  // misses exactly these lines.
  const escalations = detectEscalationFlags(preview.body);
  // A truncated body cannot be scanned to the end — and for THIS warning,
  // showing nothing would read as "nothing to worry about". A group config can
  // exceed the preview cap (up to 100 members), which would put a capability
  // grant past the cut and silently unflagged. Say so instead.
  const escalationCheckIncomplete = preview.bodyTruncated && escalations.length === 0;

  return (
    <div className="mb-1.5 space-y-1" data-testid={`request-preview-${callId}`}>
      <p className="flex flex-wrap items-center gap-1.5 font-mono text-[11px] text-foreground">
        <span
          className={
            pinned
              ? "rounded bg-emerald-500/10 px-1 py-0.5 font-sans text-[10px] font-medium text-emerald-600"
              : "rounded bg-amber-500/10 px-1 py-0.5 font-sans text-[10px] font-medium text-amber-600"
          }
          title={
            pinned
              ? t(
                  "operator.approval.verifiedTitle",
                  "Resolved from the actual API call config; re-checked immediately before execution — if the request changes before then, execution is refused.",
                )
              : t(
                  "operator.approval.previewOnlyTitle",
                  "This call runs additional setup steps before executing, so this is a best-effort preview — the actual request may differ and is not re-checked before execution.",
                )
          }
          data-testid={`request-preview-badge-${callId}`}
        >
          {pinned ? t("operator.approval.verified", "verified") : t("operator.approval.previewOnly", "preview")}
        </span>
        {preview.method} {preview.uri}
      </p>
      {queryEntries.length > 0 && (
        <p className="font-mono text-[11px] text-muted-foreground">
          {t("operator.approval.query", "Query")}: {queryEntries.map(([k, v]) => `${k}=${v}`).join(", ")}
        </p>
      )}
      {headerEntries.length > 0 && (
        <p className="font-mono text-[11px] text-muted-foreground">
          {t("operator.approval.headers", "Headers")}: {headerEntries.map(([k, v]) => `${k}: ${v}`).join(", ")}
        </p>
      )}
      {escalations.length > 0 && (
        <div
          className="rounded border border-destructive/40 bg-destructive/10 p-2"
          data-testid={`request-preview-escalations-${callId}`}
          role="alert"
        >
          <p className="flex items-center gap-1 text-[11px] font-medium text-destructive">
            <ShieldAlert className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            {t("operator.approval.escalation.heading", "This request grants further capability")}
          </p>
          <ul className="mt-1 space-y-0.5">
            {escalations.map((flag) => (
              <li key={flag.id} className="text-[11px] text-destructive">
                <span className="font-mono">{flag.path}</span>
                {" — "}
                {t(`operator.approval.escalation.${flag.id}`, ESCALATION_TEXT[flag.id] ?? flag.path)}
              </li>
            ))}
          </ul>
        </div>
      )}
      {escalationCheckIncomplete && (
        <p
          className="flex items-center gap-1 rounded border border-amber-500/40 bg-amber-500/10 p-2 text-[11px] text-amber-700 dark:text-amber-400"
          data-testid={`request-preview-escalation-unchecked-${callId}`}
          role="alert"
        >
          <ShieldAlert className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          {t(
            "operator.approval.escalation.unchecked",
            "The body was too long to scan for capability grants — read it in full before approving.",
          )}
        </p>
      )}
      {preview.body != null && preview.body !== "" && (
        <pre
          className="max-h-32 overflow-auto rounded bg-muted/60 p-2 text-[11px] leading-relaxed text-foreground"
          data-testid={`request-preview-body-${callId}`}
        >
          {preview.body}
        </pre>
      )}
      {preview.bodyTruncated && (
        <p className="text-[10px] text-muted-foreground">
          {t(
            "operator.approval.bodyTruncated",
            "Body shown truncated for display — approval still covers the full request.",
          )}
        </p>
      )}
    </div>
  );
}
