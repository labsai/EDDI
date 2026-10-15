import { useTranslation } from "react-i18next";
import { parseCascadePreview } from "@/lib/operator/cascade-preview";
import type { ResolvedRequestPreview } from "@/lib/api/hitl";

/**
 * The readable form of a gated `POST /agentstore/agents/{id}/cascade`: one line
 * per resource that moves (`llm 6a1b… v3 → v4`) and the consequences the mappings
 * do not show — whether it redeploys and where, and what the new version does to
 * conversations already running.
 *
 * Renders nothing for any other request, and for a cascade it cannot read in full
 * (see `parseCascadePreview`) — the raw body below it is always still shown, so
 * this never replaces what the approver is approving, it only makes it checkable.
 */
export function CascadeSummary({ preview, callId }: { preview: ResolvedRequestPreview; callId: string }) {
  const { t } = useTranslation();
  const cascade = parseCascadePreview(preview);
  if (!cascade) return null;

  return (
    <div
      className="rounded border border-border bg-muted/40 p-2"
      data-testid={`cascade-summary-${callId}`}
    >
      <p className="text-[11px] font-medium text-foreground">
        {t("operator.approval.cascade.heading", "Resource changes wired into agent {{agentId}} (from version {{version}})", {
          agentId: cascade.agentId,
          version: cascade.agentVersion ?? "?",
        })}
      </p>
      <ul className="mt-1 space-y-0.5" data-testid={`cascade-mappings-${callId}`}>
        {cascade.mappings.map((mapping, index) => (
          <li
            key={`${mapping.oldUri}-${index}`}
            className="flex flex-wrap items-baseline gap-x-1.5 font-mono text-[11px] text-foreground"
            title={`${mapping.oldUri} → ${mapping.newUri}`}
          >
            <span className="rounded bg-muted px-1 font-sans text-[10px] text-muted-foreground">{mapping.kind}</span>
            <span className="break-all">{mapping.id}</span>
            <span>
              {mapping.fromVersion != null ? `v${mapping.fromVersion}` : mapping.oldUri}
              {" → "}
              {mapping.newId != null ? `${mapping.newId} ` : ""}
              {mapping.toVersion != null ? `v${mapping.toVersion}` : mapping.newUri}
            </span>
          </li>
        ))}
      </ul>
      <p className="mt-1 text-[11px] text-foreground" data-testid={`cascade-redeploy-${callId}`}>
        {cascade.redeploy
          ? t(
              "operator.approval.cascade.redeploy",
              "Will redeploy to {{environment}} and wait for the result. The version it replaces stays deployed.",
              { environment: cascade.environment },
            )
          : t(
              "operator.approval.cascade.noRedeploy",
              "Will not redeploy — the new agent version is written but not live until it is deployed.",
            )}
      </p>
      <p className="text-[11px] text-muted-foreground" data-testid={`cascade-compatible-${callId}`}>
        {cascade.compatible
          ? t(
              "operator.approval.cascade.compatible",
              "Marked compatible: conversations already running switch to the new version on their next turn after it is deployed successfully.",
            )
          : t(
              "operator.approval.cascade.breaking",
              "Marked as a breaking change: conversations already running stay on their current version.",
            )}
      </p>
    </div>
  );
}
