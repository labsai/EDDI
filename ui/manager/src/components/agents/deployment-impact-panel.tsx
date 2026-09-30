import { useTranslation } from "react-i18next";
import { ArrowRight, Pin } from "lucide-react";
import { useDeploymentImpact } from "@/hooks/use-agents";
import type { DeployedVersionImpact, DeploymentImpact } from "@/lib/api/agents";

export interface DeploymentImpactPanelProps {
  agentId: string;
  /** The version whose deployment is previewed. */
  version: number;
  environment: string;
  /** Translated environment name for the heading. */
  environmentLabel: string;
}

/**
 * What deploying `version` does to the conversations running on the agent's
 * other deployed versions in one environment: FOLLOW rows move to it on their
 * next turn, STAY rows keep their version.
 *
 * Purely informational, so it is quiet: nothing while loading, on an error
 * (e.g. an older backend without the endpoint), or when no other version is
 * deployed. A deploy never waits on it.
 */
export function DeploymentImpactPanel({ agentId, version, environment, environmentLabel }: DeploymentImpactPanelProps) {
  const { t } = useTranslation();
  const { data } = useDeploymentImpact(agentId, version, environment);
  const rows = data?.deployedVersions ?? [];
  if (!data || rows.length === 0) return null;

  return (
    <div className="px-5 py-3" data-testid={`deployment-impact-${environment}`}>
      <p className="mb-1.5 text-xs font-medium text-foreground">
        {t("agentVersioning.impactTitle", "Deploying v{{version}} to {{environment}} — running conversations", {
          version: data.version,
          environment: environmentLabel,
        })}
      </p>
      <ul className="space-y-1">
        {rows.map((row) => (
          <ImpactRow key={row.version} row={row} impact={data} />
        ))}
      </ul>
    </div>
  );
}

function ImpactRow({ row, impact }: { row: DeployedVersionImpact; impact: DeploymentImpact }) {
  const { t } = useTranslation();
  if (row.outcome === "FOLLOW") {
    return (
      <li
        className="flex items-center gap-1.5 text-xs text-emerald-600 dark:text-emerald-400"
        data-testid={`impact-row-${row.version}`}
        data-outcome="FOLLOW"
      >
        <ArrowRight className="h-3.5 w-3.5 shrink-0 rtl:rotate-180" aria-hidden="true" />
        {t("agentVersioning.impactFollow", {
          count: row.activeConversations,
          from: row.version,
          to: impact.version,
          defaultValue: "{{count}} active conversation on v{{from}} will continue on v{{to}}",
          defaultValue_other: "{{count}} active conversations on v{{from}} will continue on v{{to}}",
        })}
      </li>
    );
  }
  const reason =
    row.version > impact.version
      ? t("agentVersioning.impactReasonNewer", "newer version")
      : row.compatibilityGeneration == null || impact.compatibilityGeneration == null
        ? t("agentVersioning.impactReasonLegacy", "predates version following")
        : t("agentVersioning.impactReasonBreaking", "breaking change");
  return (
    <li
      className="flex items-center gap-1.5 text-xs text-muted-foreground"
      data-testid={`impact-row-${row.version}`}
      data-outcome="STAY"
    >
      <Pin className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      <span>
        {t("agentVersioning.impactStay", {
          count: row.activeConversations,
          from: row.version,
          defaultValue: "{{count}} active conversation on v{{from}} will stay on v{{from}}",
          defaultValue_other: "{{count}} active conversations on v{{from}} will stay on v{{from}}",
        })}{" "}
        ({reason})
      </span>
    </li>
  );
}
