import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { ShieldAlert, Loader2 } from "lucide-react";
import { detectEscalationFlags } from "@/lib/operator/escalation-flags";
import { resolveConfigWriteTarget, bodyHasRedactions } from "@/lib/operator/config-write-target";
import { ResourceDiffViewer } from "@/components/agents/resource-diff-viewer";
import { getResource } from "@/lib/api/resources";
import { useAuth } from "@/hooks/use-auth";
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

  /**
   * Whole-document `PUT`s get a diff against what is currently stored.
   *
   * NOT offered for a truncated body: diffing a body that was cut mid-document
   * reports every line after the cut as deleted. That is not a degraded diff,
   * it is a wrong one, and it points the wrong way — toward "this write removes
   * most of the config".
   */
  const writeTarget = preview.bodyTruncated ? null : resolveConfigWriteTarget(preview);
  // Reading the stored document needs eddi-admin/eddi-editor, and this surface
  // is used by eddi-approver — whose entire job is approving. Gate the fetch
  // rather than firing a 403 on every pause, and say plainly that the
  // comparison is unavailable instead of showing a broken one.
  const { method: authMethod, roles } = useAuth();
  const canReadStoredDocument =
    authMethod === "none" || roles.includes("eddi-admin") || roles.includes("eddi-editor");

  const currentDocument = useQuery({
    queryKey: [
      "operator-config-diff",
      writeTarget?.resourceType.slug,
      writeTarget?.id,
      writeTarget?.version,
    ],
    queryFn: () => getResource(writeTarget!.resourceType, writeTarget!.id, writeTarget!.version),
    enabled: Boolean(writeTarget) && canReadStoredDocument,
    staleTime: Infinity, // The base version is immutable; EDDI writes version+1.
    retry: false,
  });

  const diffUnavailable = Boolean(writeTarget) && (!canReadStoredDocument || currentDocument.isError);
  const showDiff = Boolean(writeTarget) && currentDocument.isSuccess;
  const [showFullBody, setShowFullBody] = useState(false);

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
      {showDiff && (
        <div data-testid={`request-preview-diff-${callId}`}>
          <p className="mb-1 text-[10px] font-medium text-muted-foreground">
            {t("operator.approval.diffHeading", "Changes against the stored version {{version}}", {
              version: writeTarget!.version,
            })}
          </p>
          <ResourceDiffViewer
            sourceContent={preview.body ?? ""}
            targetContent={JSON.stringify(currentDocument.data)}
          />
          {bodyHasRedactions(preview.body) && (
            <p
              className="mt-1 text-[10px] text-amber-700 dark:text-amber-400"
              data-testid={`request-preview-diff-redaction-note-${callId}`}
            >
              {t(
                "operator.approval.diffRedactionNote",
                "Credential values are redacted in the proposed version, so they appear as changes here even when unchanged.",
              )}
            </p>
          )}
        </div>
      )}
      {currentDocument.isLoading && writeTarget && (
        <p className="flex items-center gap-1 text-[10px] text-muted-foreground">
          <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />
          {t("operator.approval.diffLoading", "Loading the stored version to compare against…")}
        </p>
      )}
      {diffUnavailable && (
        <p className="text-[10px] text-muted-foreground" data-testid={`request-preview-diff-unavailable-${callId}`}>
          {canReadStoredDocument
            ? t(
                "operator.approval.diffFailed",
                "Couldn't load the stored version to compare against — the full proposed document is below.",
              )
            : t(
                "operator.approval.diffForbidden",
                "Comparing against the stored version needs editor access — the full proposed document is below.",
              )}
        </p>
      )}

      {preview.body != null && preview.body !== "" && (
        <>
          {/* Always reachable, never replaced by the diff: the diff is a
              reading aid, but approval covers the whole document, and the old
              fixed 128px box made "read it in full before approving" advice
              the UI could not actually support. */}
          {showDiff && (
            <button
              type="button"
              onClick={() => setShowFullBody((open) => !open)}
              className="text-[10px] font-medium text-primary underline hover:no-underline"
              aria-expanded={showFullBody}
              data-testid={`request-preview-body-toggle-${callId}`}
            >
              {showFullBody
                ? t("operator.approval.hideFullRequest", "Hide the full proposed document")
                : t("operator.approval.showFullRequest", "Show the full proposed document")}
            </button>
          )}
          {(!showDiff || showFullBody) && (
            <>
              <pre
                className={`${showFullBody ? "max-h-[32rem]" : "max-h-32"} overflow-auto rounded bg-muted/60 p-2 text-[11px] leading-relaxed text-foreground`}
                data-testid={`request-preview-body-${callId}`}
              >
                {preview.body}
              </pre>
              {!showDiff && !showFullBody && (
                <button
                  type="button"
                  onClick={() => setShowFullBody(true)}
                  className="text-[10px] font-medium text-primary underline hover:no-underline"
                  data-testid={`request-preview-body-expand-${callId}`}
                >
                  {t("operator.approval.expandBody", "Expand")}
                </button>
              )}
            </>
          )}
        </>
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
