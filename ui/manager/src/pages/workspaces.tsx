import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Building2, Copy, KeyRound, Lock, Plus, Save, Trash2, User, Users, Variable } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { ErrorState } from "@/components/shared/error-state";
import { EmptyState } from "@/components/shared/empty-state";
import { getErrorMessage } from "@/lib/api-client";
import { cn } from "@/lib/utils";
import { useSpaces } from "@/hooks/use-spaces";
import { useHasRole } from "@/hooks/use-auth";
import {
  useDeleteSpaceSecret,
  useDeleteSpaceVariable,
  useSpaceSecrets,
  useSpaceVariables,
  useStoreSpaceSecret,
  useStoreSpaceVariable,
  useUpdateWorkspaceSettings,
  useWorkspaceSettings,
} from "@/hooks/use-workspace-admin";
import type { SpaceInfo, WorkspaceSetting } from "@/lib/api/workspaces";

/**
 * Workspaces: which spaces you are in, the secrets and variables each one
 * holds, and — for an administrator — the settings that decide where new
 * resources land and who sees what predates ownership.
 *
 * Secrets and variables used to be deployment-wide and administrator-only (the
 * vault) or writable by everyone (variables). A space now keeps its own, managed
 * by its members, and agents refer to them with the reference each row shows.
 */
export function WorkspacesPage() {
  const { t } = useTranslation();
  const { enabled, spaces, isLoading } = useSpaces();
  const isAdmin = useHasRole("eddi-admin");
  const [selected, setSelected] = useState<string | null>(null);

  // Default to the personal space once the list arrives.
  useEffect(() => {
    if (!selected && spaces.length > 0) setSelected(spaces[0]!.id);
  }, [spaces, selected]);

  const selectedSpace = useMemo(() => spaces.find((s) => s.id === selected) ?? null, [spaces, selected]);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <Building2 className="h-8 w-8 text-primary" aria-hidden="true" />
          {t("workspacesPage.title", "Workspaces")}
        </h1>
        <p className="mt-1 text-muted-foreground">
          {t("workspacesPage.subtitle", "Your spaces, the secrets and variables they keep, and how new work is shared.")}
        </p>
      </div>

      {!enabled && !isLoading && (
        <div className="rounded-xl border border-primary/20 bg-primary/5 p-4 text-sm" data-testid="workspaces-not-enforced">
          {t(
            "workspacesPage.notEnforced",
            "Workspaces are not enforced on this deployment, so everybody sees everything. An operator turns them on with eddi.workspaces.enabled=true."
          )}
        </div>
      )}

      {isAdmin && <SettingsCard spaces={spaces} />}

      {enabled && (
        <Card>
          <CardHeader>
            <CardTitle>{t("workspacesPage.yourSpaces", "Your spaces")}</CardTitle>
            <CardDescription>
              {t(
                "workspacesPage.yourSpacesHint",
                "Each space keeps its own secrets and variables. Only its members can see or change them, and only a member can deploy an agent that uses them."
              )}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            {isLoading ? (
              <Skeleton className="h-10 w-full" />
            ) : (
              <div className="flex flex-wrap gap-2" role="tablist" aria-label={t("workspacesPage.yourSpaces", "Your spaces")}>
                {spaces.map((space) => (
                  <SpaceTab key={space.id} space={space} active={space.id === selected} onSelect={() => setSelected(space.id)} />
                ))}
              </div>
            )}
            {selectedSpace && (
              <div className="grid gap-5 lg:grid-cols-2">
                <SpaceSecrets space={selectedSpace} />
                <SpaceVariables space={selectedSpace} />
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function SpaceTab({ space, active, onSelect }: { space: SpaceInfo; active: boolean; onSelect: () => void }) {
  const { t } = useTranslation();
  const Icon = space.kind === "team" ? Users : User;
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      onClick={onSelect}
      data-testid={`workspace-tab-${space.id}`}
      className={cn(
        "flex items-center gap-2 rounded-lg border px-3 py-1.5 text-sm transition-colors",
        "focus:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        active ? "border-primary bg-primary/10 text-foreground" : "border-border hover:bg-accent"
      )}
    >
      <Icon className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
      {space.kind === "personal" ? t("workspaces.personalSpace", "My workspace") : space.label}
    </button>
  );
}

function CopyReference({ reference }: { reference: string }) {
  const { t } = useTranslation();
  return (
    <button
      type="button"
      onClick={() => {
        void navigator.clipboard
          .writeText(reference)
          .then(() => toast.success(t("workspacesPage.referenceCopied", "Reference copied")))
          .catch(() => toast.error(t("workspaces.share.copyFailed", "Could not copy — select the address and copy it yourself.")));
      }}
      className="flex max-w-full items-center gap-1 truncate rounded bg-muted px-1.5 py-0.5 font-mono text-xs text-muted-foreground hover:text-foreground"
      title={t("workspacesPage.copyReference", "Copy the reference to use in an agent")}
    >
      <Copy className="h-3 w-3 shrink-0" aria-hidden="true" />
      <span className="truncate">{reference}</span>
    </button>
  );
}

function SpaceSecrets({ space }: { space: SpaceInfo }) {
  const { t } = useTranslation();
  const { data, isLoading, isError, error, refetch } = useSpaceSecrets(space.id);
  const store = useStoreSpaceSecret(space.id);
  const remove = useDeleteSpaceSecret(space.id);
  const [keyName, setKeyName] = useState("");
  const [value, setValue] = useState("");
  const [deleting, setDeleting] = useState<string | null>(null);

  const save = () => {
    store.mutate(
      { keyName: keyName.trim(), value },
      {
        onSuccess: () => {
          toast.success(t("workspacesPage.secretStored", "Secret stored"));
          setKeyName("");
          setValue("");
        },
        onError: (e) => toast.error(getErrorMessage(e)),
      }
    );
  };

  return (
    <section className="space-y-3" data-testid="space-secrets">
      <h3 className="flex items-center gap-2 text-sm font-semibold">
        <KeyRound className="h-4 w-4 text-primary" aria-hidden="true" />
        {t("workspacesPage.secrets", "Secrets")}
      </h3>
      {isLoading && <Skeleton className="h-16 w-full" />}
      {isError && !data && (
        <ErrorState message={getErrorMessage(error)} onRetry={() => void refetch()} retryLabel={t("common.retry", "Retry")} />
      )}
      {data && data.length === 0 && (
        <EmptyState icon={Lock} title={t("workspacesPage.noSecrets", "No secrets in this space yet")} />
      )}
      {data && data.length > 0 && (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {data.map((secret) => (
            <li key={secret.keyName} className="flex items-center gap-2 px-3 py-2" data-testid={`space-secret-${secret.keyName}`}>
              <div className="min-w-0 flex-1 space-y-1">
                <p className="truncate text-sm font-medium">{secret.keyName}</p>
                <CopyReference reference={secret.reference} />
              </div>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setDeleting(secret.keyName)}
                aria-label={t("workspacesPage.deleteNamed", "Delete {{name}}", { name: secret.keyName })}
              >
                <Trash2 className="h-4 w-4" aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-col gap-2 sm:flex-row">
        <Input
          value={keyName}
          onChange={(e) => setKeyName(e.target.value)}
          placeholder={t("workspacesPage.secretName", "Name, e.g. openai-key")}
          aria-label={t("workspacesPage.secretName", "Name, e.g. openai-key")}
          data-testid="space-secret-name"
        />
        <Input
          type="password"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder={t("workspacesPage.secretValue", "Value — stored encrypted, never shown again")}
          aria-label={t("workspacesPage.secretValue", "Value — stored encrypted, never shown again")}
          autoComplete="new-password"
          data-testid="space-secret-value"
        />
        <Button onClick={save} disabled={!keyName.trim() || !value || store.isPending} data-testid="space-secret-save">
          <Plus className="h-4 w-4" aria-hidden="true" />
          {t("workspacesPage.add", "Add")}
        </Button>
      </div>
      <AlertDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("workspacesPage.deleteSecretTitle", "Delete this secret?")}
        description={t(
          "workspacesPage.deleteSecretDescription",
          "Agents that use it stop working until it is stored again. This cannot be undone."
        )}
        onConfirm={() =>
          deleting &&
          remove.mutate(deleting, {
            onSuccess: () => setDeleting(null),
            onError: (e) => toast.error(getErrorMessage(e)),
          })
        }
        confirmLabel={t("common.delete", "Delete")}
        cancelLabel={t("common.cancel", "Cancel")}
        isPending={remove.isPending}
        variant="destructive"
      />
    </section>
  );
}

function SpaceVariables({ space }: { space: SpaceInfo }) {
  const { t } = useTranslation();
  const { data, isLoading, isError, error, refetch } = useSpaceVariables(space.id);
  const store = useStoreSpaceVariable(space.id);
  const remove = useDeleteSpaceVariable(space.id);
  const [key, setKey] = useState("");
  const [value, setValue] = useState("");
  const [deleting, setDeleting] = useState<string | null>(null);

  const save = () => {
    store.mutate(
      { key: key.trim(), value },
      {
        onSuccess: () => {
          toast.success(t("workspacesPage.variableStored", "Variable saved"));
          setKey("");
          setValue("");
        },
        onError: (e) => toast.error(getErrorMessage(e)),
      }
    );
  };

  return (
    <section className="space-y-3" data-testid="space-variables">
      <h3 className="flex items-center gap-2 text-sm font-semibold">
        <Variable className="h-4 w-4 text-primary" aria-hidden="true" />
        {t("workspacesPage.variables", "Variables")}
      </h3>
      {isLoading && <Skeleton className="h-16 w-full" />}
      {isError && !data && (
        <ErrorState message={getErrorMessage(error)} onRetry={() => void refetch()} retryLabel={t("common.retry", "Retry")} />
      )}
      {data && data.length === 0 && (
        <EmptyState icon={Variable} title={t("workspacesPage.noVariables", "No variables in this space yet")} />
      )}
      {data && data.length > 0 && (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {data.map((variable) => (
            <li key={variable.key} className="flex items-center gap-2 px-3 py-2" data-testid={`space-variable-${variable.key}`}>
              <div className="min-w-0 flex-1 space-y-1">
                <p className="truncate text-sm">
                  <span className="font-medium">{variable.key}</span>
                  <span className="text-muted-foreground"> = {variable.value}</span>
                </p>
                <CopyReference reference={variable.reference} />
              </div>
              <Button
                variant="ghost"
                size="sm"
                disabled={remove.isPending}
                onClick={() => setDeleting(variable.key)}
                aria-label={t("workspacesPage.deleteNamed", "Delete {{name}}", { name: variable.key })}
              >
                <Trash2 className="h-4 w-4" aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-col gap-2 sm:flex-row">
        <Input
          value={key}
          onChange={(e) => setKey(e.target.value)}
          placeholder={t("workspacesPage.variableName", "Name, e.g. model")}
          aria-label={t("workspacesPage.variableName", "Name, e.g. model")}
          data-testid="space-variable-name"
        />
        <Input
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder={t("workspacesPage.variableValue", "Value")}
          aria-label={t("workspacesPage.variableValue", "Value")}
          data-testid="space-variable-value"
        />
        <Button onClick={save} disabled={!key.trim() || store.isPending} data-testid="space-variable-save">
          <Save className="h-4 w-4" aria-hidden="true" />
          {t("common.save", "Save")}
        </Button>
      </div>
      <AlertDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("workspacesPage.deleteVariableTitle", "Delete this variable?")}
        description={t(
          "workspacesPage.deleteVariableDescription",
          "Agents that refer to it can no longer resolve it. This cannot be undone."
        )}
        onConfirm={() =>
          deleting &&
          remove.mutate(deleting, {
            onSuccess: () => setDeleting(null),
            onError: (e) => toast.error(getErrorMessage(e)),
          })
        }
        confirmLabel={t("common.delete", "Delete")}
        cancelLabel={t("common.cancel", "Cancel")}
        isPending={remove.isPending}
        variant="destructive"
      />
    </section>
  );
}

/** Where a setting's value comes from, shown next to it. */
function SourceBadge({ setting }: { setting: WorkspaceSetting }) {
  const { t } = useTranslation();
  if (setting.source === "PINNED") {
    return (
      <Badge variant="warning" title={setting.property}>
        {t("workspacesPage.pinned", "Pinned by {{property}}", { property: setting.property })}
      </Badge>
    );
  }
  return (
    <Badge variant="secondary">
      {setting.source === "STORED" ? t("workspacesPage.stored", "Set here") : t("workspacesPage.default", "Default")}
    </Badge>
  );
}

function SettingsCard({ spaces }: { spaces: SpaceInfo[] }) {
  const { t } = useTranslation();
  const { data, isLoading, isError, error, refetch } = useWorkspaceSettings(true);
  const update = useUpdateWorkspaceSettings();
  const [defaultSpace, setDefaultSpace] = useState("");
  const [legacy, setLegacy] = useState<"shared" | "admin-only">("shared");

  useEffect(() => {
    if (!data) return;
    setDefaultSpace(data.defaultSpace.value ?? "");
    setLegacy(data.legacyVisibility.value === "admin-only" ? "admin-only" : "shared");
  }, [data]);

  const teams = spaces.filter((s) => s.kind === "team");

  return (
    <Card data-testid="workspace-settings">
      <CardHeader>
        <CardTitle>{t("workspacesPage.settings", "Settings")}</CardTitle>
        <CardDescription>
          {t(
            "workspacesPage.settingsHint",
            "Take effect at once, on every replica within five seconds. Whether workspaces are enforced, and which token claim carries teams, are startup settings and shown for reference only."
          )}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">
        {isLoading && <Skeleton className="h-24 w-full" />}
        {isError && !data && (
          <ErrorState message={getErrorMessage(error)} onRetry={() => void refetch()} retryLabel={t("common.retry", "Retry")} />
        )}
        {data && (
          <>
            <dl className="grid gap-2 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-muted-foreground">{t("workspacesPage.enforcing", "Enforced")}</dt>
                <dd>{data.enforcing ? t("workspacesPage.enforcedYes", "Yes") : t("workspacesPage.enforcedNo", "No")}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">{t("workspacesPage.groupsClaim", "Teams come from the token claim")}</dt>
                <dd className="font-mono">{data.groupsClaim}</dd>
              </div>
            </dl>

            <div className="space-y-2">
              <div className="flex items-center gap-2">
                <label htmlFor="default-space" className="text-sm font-medium">
                  {t("workspacesPage.defaultSpace", "Where new work lands by default")}
                </label>
                <SourceBadge setting={data.defaultSpace} />
              </div>
              <Input
                id="default-space"
                list="default-space-teams"
                value={defaultSpace}
                onChange={(e) => setDefaultSpace(e.target.value)}
                disabled={data.defaultSpace.source === "PINNED"}
                placeholder={t("workspacesPage.defaultSpacePlaceholder", "Empty: each person's own workspace — or a team name")}
                data-testid="default-space-input"
              />
              <datalist id="default-space-teams">
                {teams.map((team) => (
                  <option key={team.id} value={team.label} />
                ))}
              </datalist>
              <p className="text-xs text-muted-foreground">
                {t(
                  "workspacesPage.defaultSpaceHelp",
                  "A team name makes the deployment team-first: colleagues see each other's work unless it is made private. The workspace a person has selected always wins."
                )}
              </p>
            </div>

            <fieldset className="space-y-2">
              <legend className="flex items-center gap-2 text-sm font-medium">
                {t("workspacesPage.legacy", "Resources created before ownership was recorded")}
                <SourceBadge setting={data.legacyVisibility} />
              </legend>
              {(["shared", "admin-only"] as const).map((option) => (
                <label key={option} className="flex items-start gap-2 text-sm">
                  <input
                    type="radio"
                    name="legacy-visibility"
                    value={option}
                    checked={legacy === option}
                    disabled={data.legacyVisibility.source === "PINNED"}
                    onChange={() => setLegacy(option)}
                    className="mt-1"
                    data-testid={`legacy-${option}`}
                  />
                  <span>
                    {option === "shared"
                      ? t("workspacesPage.legacyShared", "Visible to everyone — nothing disappears after an upgrade")
                      : t("workspacesPage.legacyAdminOnly", "Visible to administrators only — once owners have been assigned")}
                  </span>
                </label>
              ))}
            </fieldset>

            {data.warnings.length > 0 && (
              <ul className="space-y-1 rounded-md border border-warning/30 bg-warning/5 p-3 text-xs" data-testid="workspace-settings-warnings">
                {data.warnings.map((warning) => (
                  <li key={warning}>{warning}</li>
                ))}
              </ul>
            )}

            <div className="flex items-center justify-between gap-2">
              <p className="text-xs text-muted-foreground">
                {data.updatedBy
                  ? t("workspacesPage.lastChanged", "Last changed by {{who}}", { who: data.updatedBy })
                  : t("workspacesPage.neverChanged", "Not changed here yet")}
              </p>
              <Button
                onClick={() =>
                  update.mutate(
                    {
                      defaultSpace: data.defaultSpace.source === "PINNED" ? null : defaultSpace.trim() || null,
                      legacyVisibility: data.legacyVisibility.source === "PINNED" ? null : legacy,
                    },
                    {
                      onSuccess: () => toast.success(t("workspacesPage.saved", "Settings saved")),
                      onError: (e) => toast.error(getErrorMessage(e)),
                    }
                  )
                }
                disabled={update.isPending}
                data-testid="workspace-settings-save"
              >
                <Save className="h-4 w-4" aria-hidden="true" />
                {t("common.save", "Save")}
              </Button>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
