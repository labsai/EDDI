import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { LayoutTemplate, Users, ArrowLeft, Sparkles, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorState } from "@/components/shared/error-state";
import { BackLink } from "@/components/shared/back-link";
import { getErrorMessage } from "@/lib/api-client";
import { useAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import {
  useGroupTemplates,
  useGroupTemplate,
  useInstantiateGroupTemplate,
} from "@/hooks/use-group-templates";
import type { TemplateManifest } from "@/lib/api/group-templates";
import { parseGroupResourceUri } from "@/lib/api/groups";

/** Icon per packaged template — matched by id, falls back to a generic template icon. */
const TEMPLATE_ICONS: Record<string, string> = {
  "research-pod": "🔬",
  "editorial-team": "📝",
  "ops-task-force": "🎯",
  "decision-board": "⚖️",
  "negotiation-table": "🤝",
};

/**
 * Which of a template's declared roles resolve to a HUMAN member (their
 * assignment is a principal id, not an agent id) — inferred from the raw
 * packaged config's `members[].memberType`, matched back to the `$role`
 * placeholder in `agentId`/`moderatorAgentId`. Of the 5 packaged templates,
 * only decision-board's `humanDirector` is ever HUMAN today.
 */
function humanRoles(config: Record<string, unknown> | undefined): Set<string> {
  const roles = new Set<string>();
  if (!config) return roles;
  const members = Array.isArray(config.members) ? config.members : [];
  for (const m of members) {
    if (
      m &&
      typeof m === "object" &&
      (m as { memberType?: unknown }).memberType === "HUMAN" &&
      typeof (m as { agentId?: unknown }).agentId === "string" &&
      (m as { agentId: string }).agentId.startsWith("$")
    ) {
      roles.add((m as { agentId: string }).agentId.slice(1));
    }
  }
  return roles;
}

export function GroupTemplatesPage() {
  const { t } = useTranslation();
  const [selectedId, setSelectedId] = useState<string | null>(null);

  return selectedId ? (
    <TemplateInstantiateView templateId={selectedId} onBack={() => setSelectedId(null)} />
  ) : (
    <TemplateGalleryView onSelect={setSelectedId} t={t} />
  );
}

function TemplateGalleryView({
  onSelect,
  t,
}: {
  onSelect: (id: string) => void;
  t: ReturnType<typeof useTranslation>["t"];
}) {
  const { data: templates, isLoading, isError, refetch } = useGroupTemplates();

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <BackLink to="/manage/groups" label={t("groups.backToGroups", "Back to Groups")} />
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <LayoutTemplate className="h-8 w-8 text-primary" />
          {t("groupTemplates.title", "Group Templates")}
        </h1>
        <p className="text-muted-foreground">
          {t(
            "groupTemplates.subtitle",
            "Start from a packaged, pre-tuned discussion — just assign agents to its named roles.",
          )}
        </p>
      </div>

      {isLoading && (
        <div className="cq-card-grid">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="rounded-xl border border-border bg-card p-5 space-y-3">
              <Skeleton className="h-6 w-2/3" />
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-1/2" />
            </div>
          ))}
        </div>
      )}

      {isError && (
        <ErrorState message={t("common.error")} onRetry={() => refetch()} retryLabel={t("common.retry")} />
      )}

      {!isLoading && !isError && (
        <div className="cq-card-grid" data-testid="template-gallery">
          {(templates ?? []).map((tmpl: TemplateManifest) => (
            <button
              key={tmpl.templateId}
              type="button"
              onClick={() => onSelect(tmpl.templateId)}
              className="flex flex-col items-start gap-2 rounded-xl border border-border bg-card p-5 text-start transition-all hover:border-primary/40 hover:shadow-md"
              data-testid={`template-card-${tmpl.templateId}`}
            >
              <span className="text-3xl" aria-hidden="true">
                {TEMPLATE_ICONS[tmpl.templateId] ?? "🧩"}
              </span>
              <h3 className="text-base font-semibold text-foreground">{tmpl.title}</h3>
              <p className="text-sm text-muted-foreground line-clamp-3">{tmpl.description}</p>
              <span className="mt-auto flex items-center gap-1 pt-2 text-xs text-muted-foreground">
                <Users className="h-3 w-3" aria-hidden="true" />
                {t("groupTemplates.roleCount", "{{count}} role(s) to assign", {
                  count: tmpl.requiredRoles.length,
                })}
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function TemplateInstantiateView({ templateId, onBack }: { templateId: string; onBack: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { data: detail, isLoading, isError, refetch } = useGroupTemplate(templateId);
  const { data: agentDescriptors } = useAgentDescriptors(200);
  const agents = agentDescriptors ? groupAgentsByName(agentDescriptors) : [];
  const instantiate = useInstantiateGroupTemplate();

  const [name, setName] = useState("");
  const [assignments, setAssignments] = useState<Record<string, string>>({});
  const [submitError, setSubmitError] = useState<string | null>(null);

  const humanRoleSet = useMemo(() => humanRoles(detail?.config), [detail?.config]);

  if (isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-1/3" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  if (isError || !detail) {
    return (
      <div className="space-y-4">
        <BackLink to="/manage/groups/templates" label={t("common.back", "Back")} />
        <ErrorState message={t("common.error")} onRetry={() => refetch()} retryLabel={t("common.retry")} />
      </div>
    );
  }

  const { manifest } = detail;
  const allAssigned = manifest.requiredRoles.every((r) => !!assignments[r.role]?.trim());

  function handleCreate() {
    setSubmitError(null);
    instantiate.mutate(
      {
        templateId,
        request: {
          name: name.trim() || undefined,
          roleAssignments: assignments,
        },
      },
      {
        onSuccess: (data) => {
          toast.success(t("groupTemplates.created", "Group created from template"));
          try {
            const { id, version } = parseGroupResourceUri(data.location);
            navigate(`/manage/groups/${id}?version=${version}`);
          } catch {
            navigate("/manage/groups");
          }
        },
        onError: (err) => setSubmitError(getErrorMessage(err)),
      },
    );
  }

  return (
    <div className="space-y-6">
      <button
        type="button"
        onClick={onBack}
        className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
        data-testid="template-back"
      >
        <ArrowLeft className="h-4 w-4" />
        {t("groupTemplates.backToGallery", "Back to templates")}
      </button>

      <div className="flex items-center gap-3">
        <span className="text-3xl" aria-hidden="true">{TEMPLATE_ICONS[templateId] ?? "🧩"}</span>
        <div>
          <h1 className="text-2xl font-bold text-foreground">{manifest.title}</h1>
          <p className="text-sm text-muted-foreground">{manifest.description}</p>
        </div>
      </div>

      <div className="max-w-xl space-y-4 rounded-xl border border-border bg-card p-5">
        <div>
          <label htmlFor="template-group-name" className="mb-1 block text-xs font-medium text-muted-foreground">
            {t("groupTemplates.nameLabel", "Group name")}
          </label>
          <input
            id="template-group-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={manifest.title}
            className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="template-group-name"
          />
        </div>

        <div className="space-y-3">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("groupTemplates.assignRoles", "Assign roles")}
          </h3>
          {manifest.requiredRoles.map((role) => {
            const isHuman = humanRoleSet.has(role.role);
            return (
              <div key={role.role} className="space-y-1">
                <label
                  htmlFor={`template-role-${role.role}`}
                  className="flex items-center gap-1.5 text-xs font-medium text-foreground"
                >
                  {role.role}
                  {isHuman && (
                    <span className="rounded bg-primary/10 px-1 py-0 text-[9px] font-medium text-primary">
                      {t("groupWizard.memberTypeHuman", "Human")}
                    </span>
                  )}
                </label>
                <p className="text-[11px] text-muted-foreground">{role.description}</p>
                {isHuman ? (
                  <input
                    id={`template-role-${role.role}`}
                    value={assignments[role.role] ?? ""}
                    onChange={(e) => setAssignments((a) => ({ ...a, [role.role]: e.target.value }))}
                    placeholder={t("groupWizard.humanPrincipalIdPlaceholder", "The user's login/principal id")}
                    className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                    data-testid={`template-role-input-${role.role}`}
                  />
                ) : (
                  <select
                    id={`template-role-${role.role}`}
                    value={assignments[role.role] ?? ""}
                    onChange={(e) => setAssignments((a) => ({ ...a, [role.role]: e.target.value }))}
                    className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                    data-testid={`template-role-input-${role.role}`}
                  >
                    <option value="">{t("groupTemplates.selectAgent", "Select an agent…")}</option>
                    {agents.map((a) => (
                      <option key={a.id} value={a.id}>{a.name}</option>
                    ))}
                  </select>
                )}
              </div>
            );
          })}
        </div>

        {submitError && (
          <p className="text-xs text-destructive" data-testid="template-submit-error">{submitError}</p>
        )}

        <Button
          onClick={handleCreate}
          disabled={!allAssigned || instantiate.isPending}
          className="w-full"
          data-testid="template-create-button"
        >
          {instantiate.isPending ? (
            <RefreshCw className="h-4 w-4 animate-spin" />
          ) : (
            <Sparkles className="h-4 w-4" />
          )}
          {t("groupTemplates.createButton", "Create Group")}
        </Button>
      </div>
    </div>
  );
}
