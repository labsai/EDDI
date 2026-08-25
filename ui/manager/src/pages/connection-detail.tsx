import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  ChevronDown,
  ChevronUp,
  Globe,
  Info,
  Plug,
  Plus,
  Save,
  ShieldAlert,
  Trash2,
  X,
} from "lucide-react";
import { BackLink } from "@/components/shared/back-link";
import { ChipInput } from "@/components/shared/chip-input";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";
import { ErrorState } from "@/components/shared/error-state";
import { RefetchErrorNotice } from "@/components/shared/refetch-error-notice";
import { ConnectionCredentialFields } from "@/components/connections/connection-credential-fields";
import { OriginAllowlistField } from "@/components/connections/origin-allowlist-field";
import { ValidationMessage } from "@/components/connections/validation-message";
import { AuthTypeBadge } from "@/components/connections/connection-badges";
import {
  useConnection,
  useUpdateConnection,
  useDeleteConnection,
} from "@/hooks/use-connections";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";
import { getErrorMessage } from "@/lib/api-client";
import { commitPending } from "@/lib/chip-values";
import { authTypeLabel } from "@/lib/connection-labels";
import {
  AUTH_TYPES,
  CLIENT_AUTH_METHODS,
  emptyConnection,
  parseConnectionResourceUri,
  toStoredConnection,
  type AuthType,
  type ConnectionConfiguration,
  type OAuthConfig,
  type StaticAuth,
} from "@/lib/api/connections";
import {
  bindingFor,
  isOAuthType,
  validateConnection,
  type ValidationCode,
} from "@/lib/connection-validation";

/**
 * The connection editor.
 *
 * Hand-written rather than generated from `/jsonSchema`, like every other form
 * in this app — the schema is used to feed Monaco's validation, never to build
 * fields. Three of this document's rules are ones a generated form could not
 * express anyway:
 *
 *  - **`binding` is derived, not chosen.** The backend couples it to `authType`
 *    in both directions, which leaves exactly one legal value per type. Offering
 *    it as a select would offer three broken combinations and one working one.
 *  - **`name` is immutable.** Every grant is filed under `(tenant, name)`, so a
 *    rename orphans them — and the next connection created under the old name
 *    inherits them. The backend refuses; the input is disabled and says why.
 *  - **Secrets are references.** `clientSecret` and `passwordRef` take a
 *    `${vault:…}` pointer, never a value, so they use the picker's
 *    reference-only mode rather than a password box that would accept a paste
 *    and fail on save.
 *
 * There is deliberately **no Connect button here.** Linking navigates the whole
 * page to the provider, which would discard whatever draft is on screen. It
 * belongs on the surfaces that have nothing unsaved — the linked-accounts panel.
 */
export function ConnectionDetailPage() {
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();

  const parsedVersion = Number(searchParams.get("version") ?? "1");
  const version = Number.isFinite(parsedVersion) ? parsedVersion : 1;

  const { data: config, isLoading, isError, refetch } = useConnection(id!, version);
  const updateMutation = useUpdateConnection();
  const deleteMutation = useDeleteConnection();

  const [draft, setDraft] = useState<ConnectionConfiguration | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [unverifiedConfirmOpen, setUnverifiedConfirmOpen] = useState(false);
  const [rawOpen, setRawOpen] = useState(false);
  const [showErrors, setShowErrors] = useState(false);
  /** Chip text typed but not committed — held here so a save can fold it in. */
  const [pendingScope, setPendingScope] = useState("");
  const [pendingOrigin, setPendingOrigin] = useState("");
  /** Set when an in-app navigation was blocked by unsaved edits. */
  const [pendingExit, setPendingExit] = useState<string | null>(null);

  /**
   * The document the draft was seeded from, serialised.
   *
   * A ref, not state: it is only ever read to answer "has the user changed
   * anything", which is recomputed whenever the draft renders anyway. As state
   * it would feed its own seeding effect and loop.
   */
  const baselineRef = useRef<string | null>(null);
  const draftRef = useRef<ConnectionConfiguration | null>(null);
  draftRef.current = draft;

  /**
   * Reset the form wholesale when the route points at a different document.
   *
   * **Declared before the seeding effect, and that order is load-bearing.**
   * React runs a pass's effects in declaration order, so when the document in
   * the cache is already there on the first render for this `[id, version]` —
   * the list seeds the detail key from its enrichment, and a save seeds the
   * version it just created — both effects fire in the same pass. Seed-then-
   * reset ends with `draft` null, and `draft` null is the loading skeleton.
   *
   * That is not a flash. The seeding effect only re-runs on a new `config`
   * *identity*, and TanStack's structural sharing returns the very same object
   * when a refetch deep-equals what is already cached — which is the normal
   * case for a document store echoing back what it just stored. So nothing
   * would wake the form up again: opening a connection from the list, or
   * saving one, would leave a skeleton on screen until a manual reload.
   *
   * Reset-then-seed ends the same pass with the draft seeded. It also stays
   * correct for the ordinary mount (no cache, `config` undefined, the seeding
   * effect no-ops and runs later when the fetch lands), and it cannot resurrect
   * the clobbering bug below, because this effect's deps are only `[id,
   * version]` — a background refetch never runs it.
   */
  useEffect(() => {
    baselineRef.current = null;
    setDraft(null);
    setShowErrors(false);
    setPendingScope("");
    setPendingOrigin("");
  }, [id, version]);

  /**
   * Seed the draft — and re-seed only when it is safe to.
   *
   * This effect used to run on every new `config` identity, which meant a
   * *successful* background refetch (window focus past the staleTime, or any
   * `["connections"]` invalidation) replaced an in-progress form with the server
   * copy: no warning, no undo, ten minutes of transcribed OAuth settings gone.
   *
   * Now a refetch only lands when the user has nothing to lose. If they are
   * mid-edit their draft stands, and the server's newer copy is picked up by
   * the next load — or refused by the version check on save, which is the
   * conflict signal that actually belongs to the user.
   *
   * When it runs in the same pass as the reset above, the dirty check is what
   * makes the pairing safe: `baselineRef` has just been nulled, so the draft
   * left over from the previous document cannot be mistaken for unsaved work
   * on this one.
   */
  useEffect(() => {
    if (!config) return;
    const current = draftRef.current;
    const dirty =
      current !== null &&
      baselineRef.current !== null &&
      JSON.stringify(current) !== baselineRef.current;
    if (dirty) return;
    baselineRef.current = JSON.stringify(config);
    setDraft({ ...config });
  }, [config]);

  /**
   * What a save would actually send: the draft with any uncommitted chip text
   * folded in, and only the fields this auth type uses.
   */
  const outgoing = useMemo(() => {
    if (!draft) return null;
    return toStoredConnection({
      ...draft,
      baseUrlAllowlist: commitPending(draft.baseUrlAllowlist, pendingOrigin),
      oauth: draft.oauth
        ? {
            ...draft.oauth,
            scopes: commitPending(draft.oauth.scopes ?? [], pendingScope, /[\s,]+/),
          }
        : draft.oauth,
    });
  }, [draft, pendingOrigin, pendingScope]);

  // Validated against what will be SENT, not against what is on screen — the
  // two differ by exactly the text a user typed and did not commit.
  const errors = useMemo(
    () => (outgoing ? validateConnection(outgoing) : {}),
    [outgoing],
  );

  const isDirty =
    (draft !== null &&
      baselineRef.current !== null &&
      JSON.stringify(draft) !== baselineRef.current) ||
    pendingScope.trim() !== "" ||
    pendingOrigin.trim() !== "";

  // Covers tab close and reload. In-app navigation is guarded explicitly below
  // — on this page's own two exits, the back link and the linked-accounts link
  // — because the app uses <BrowserRouter> and React Router's blocker needs the
  // data router. Leaving by the sidebar or the command palette is not guarded,
  // here or anywhere else in the app; that is a gap in the router setup rather
  // than in this page.
  useUnsavedChangesGuard(isDirty);

  /** Leave for `to`, asking first when there are unsaved edits. */
  const leaveFor = useCallback(
    (to: string) => {
      if (isDirty) {
        setPendingExit(to);
        return;
      }
      navigate(to);
    },
    [isDirty, navigate],
  );

  /**
   * Switching auth type keeps both blocks in the draft.
   *
   * Only the relevant one is sent (see `handleSave`), so a mis-click on the
   * type select does not silently destroy an OAuth block the author spent ten
   * minutes on — switching back restores it. What is *stored* stays clean.
   */
  const changeAuthType = useCallback((authType: AuthType) => {
    setDraft((prev) => {
      if (!prev) return prev;
      const blank = emptyConnection(authType);
      return {
        ...prev,
        authType,
        binding: bindingFor(authType),
        // The flag is only legal on a per-user binding, and the backend refuses
        // it elsewhere rather than ignoring it — a relaxation sitting on a
        // document where it does nothing reads as a decision already in force.
        allowUnverifiedPrincipal:
          authType === "OAUTH2_AUTHORIZATION_CODE"
            ? prev.allowUnverifiedPrincipal
            : false,
        staticAuth: prev.staticAuth ?? blank.staticAuth,
        oauth: prev.oauth ?? blank.oauth,
      };
    });
  }, []);

  const handleSave = async () => {
    if (!outgoing || !id) return;
    setShowErrors(true);
    if (Object.keys(errors).length > 0) {
      toast.error(
        t("connections.fixFieldsFirst", "Some fields still need attention."),
      );
      return;
    }
    // `outgoing` already folds in any uncommitted chip text and drops the
    // fields this auth type does not use — see `toStoredConnection`.
    try {
      const result = await updateMutation.mutateAsync({ id, version, config: outgoing });

      // Only now. Committing the baseline before the request resolved made a
      // FAILED save look clean: `isDirty` went false, so the unsaved-changes
      // guard stopped protecting edits the backend had just refused, and the
      // next background refetch replaced them with the server copy. The whole
      // point of the guard is the case where the save did not happen.
      setDraft(outgoing);
      setPendingScope("");
      setPendingOrigin("");
      baselineRef.current = JSON.stringify(outgoing);

      const location = (result as { location?: string })?.location;
      if (location) {
        // Follow the new version, or the next save conflicts with itself.
        // The mutation has already seeded this version's cache with what we
        // sent, so the switch re-seeds the form instead of showing a skeleton.
        const { version: newVersion } = parseConnectionResourceUri(location);
        setSearchParams({ version: String(newVersion) }, { replace: true });
      }
      toast.success(t("connections.saved", "Connection saved"));
    } catch (err) {
      // A 400 here names the field and the fix — a duplicate name, a token URL
      // the operator has not allowlisted, PER_USER on a deployment without
      // OIDC, an OAuth connection with no vault. None of those are knowable
      // from the browser, so the backend's sentence is the useful one.
      toast.error(getErrorMessage(err));
    }
  };

  const handleDelete = async () => {
    if (!id) return;
    try {
      await deleteMutation.mutateAsync({ id, version });
      // Deliberately `navigate`, not `leaveFor`: the document is gone, so there
      // is nothing left for an "unsaved changes" prompt to protect.
      navigate("/manage/connections");
    } catch (err) {
      toast.error(getErrorMessage(err));
    }
  };

  const patchStatic = useCallback(
    (patch: Partial<StaticAuth>) =>
      setDraft((prev) =>
        prev
          ? {
              ...prev,
              // No `headerName: "Authorization"` default here. Injecting one
              // would silently write a header nobody chose onto a document that
              // was stored without one, the moment an unrelated field is edited
              // — and it would do so *before* validation, so the empty-field
              // rule that exists to catch it could never fire.
              staticAuth: { headerName: "", ...prev.staticAuth, ...patch },
            }
          : prev,
      ),
    [],
  );

  const patchOAuth = useCallback(
    (patch: Partial<OAuthConfig>) =>
      setDraft((prev) =>
        prev ? { ...prev, oauth: { ...prev.oauth, ...patch } } : prev,
      ),
    [],
  );

  // Only a failed INITIAL load replaces the page. A background refetch failure
  // must not throw away a form the user is part way through editing.
  if (isError && !draft) {
    return (
      <div data-testid="connection-detail-error">
        <ErrorState
          message={t("common.error", "Something went wrong")}
          onRetry={() => void refetch()}
          retryLabel={t("common.retry", "Retry")}
        />
      </div>
    );
  }

  if (isLoading || !draft) {
    return (
      <div className="space-y-6" data-testid="connection-detail-loading">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-64 w-full rounded-xl" />
      </div>
    );
  }

  const perUser = draft.authType === "OAUTH2_AUTHORIZATION_CODE";
  const fieldError = (field: keyof typeof errors) =>
    showErrors ? errors[field] : undefined;

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      {isError && (
        <div data-testid="connection-detail-refetch-error">
          <RefetchErrorNotice onRetry={() => void refetch()} />
        </div>
      )}

      {/* Header */}
      <BackLink
        to="/manage/connections"
        label={t("connections.backToList", "Back to Connections")}
        onNavigate={leaveFor}
      />
      <div className="flex items-center gap-3">
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-xl font-bold tracking-tight">
            {draft.name || t("connections.unnamed", "Unnamed connection")}
          </h1>
          <p className="text-xs text-muted-foreground">
            {t("common.versionShort", "v{{version}}", { version })}
          </p>
        </div>
        <Button
          variant="outline"
          size="sm"
          className="text-destructive"
          onClick={() => setDeleteOpen(true)}
          data-testid="delete-connection-btn"
        >
          <Trash2 className="h-4 w-4" aria-hidden="true" />
          {t("common.delete", "Delete")}
        </Button>
        <Button
          size="sm"
          onClick={() => void handleSave()}
          disabled={updateMutation.isPending}
          data-testid="save-connection-btn"
        >
          <Save className="h-4 w-4" aria-hidden="true" />
          {updateMutation.isPending
            ? t("common.saving", "Saving…")
            : t("common.save", "Save")}
        </Button>
      </div>

      {/* General */}
      <section className="space-y-4 rounded-xl border border-border/50 p-5">
        <h2 className="flex items-center gap-2 text-sm font-semibold">
          <Plug className="h-4 w-4 text-primary" aria-hidden="true" />
          {t("connections.general", "General")}
        </h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1">
            <label className="text-xs font-medium" htmlFor="connection-name">
              {t("connections.name", "Name")}
            </label>
            <Input
              id="connection-name"
              data-testid="connection-name-input"
              value={draft.name}
              disabled
              readOnly
            />
            <p className="text-[11px] text-muted-foreground">
              {t(
                "connections.nameImmutable",
                "Fixed after creation: every linked account is filed under this name, so renaming would strand them. Create a new connection instead.",
              )}
            </p>
          </div>
          <div className="space-y-1">
            <label className="text-xs font-medium" htmlFor="connection-description">
              {t("connections.description", "Description")}
            </label>
            <Input
              id="connection-description"
              data-testid="connection-description-input"
              value={draft.description ?? ""}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
            />
          </div>
        </div>
      </section>

      {/* Authentication */}
      <section className="space-y-4 rounded-xl border border-border/50 p-5">
        <h2 className="flex items-center gap-2 text-sm font-semibold">
          <AuthTypeBadge authType={draft.authType} />
          {t("connections.authentication", "Authentication")}
        </h2>

        <div className="space-y-1">
          <label className="text-xs font-medium" htmlFor="connection-auth-type">
            {t("connections.authTypeCol", "Authentication")}
          </label>
          <select
            id="connection-auth-type"
            data-testid="connection-auth-type-select"
            className="flex h-9 w-full rounded-lg border border-border bg-background px-3 text-sm"
            value={draft.authType}
            onChange={(e) => changeAuthType(e.target.value as AuthType)}
          >
            {AUTH_TYPES.map((type) => (
              <option key={type} value={type}>
                {authTypeLabel(t, type)}
              </option>
            ))}
          </select>
        </div>

        {/* Binding is shown, not chosen — see the file comment. */}
        <div className="flex items-start gap-2 rounded-lg border border-border bg-muted/30 p-3">
          <Info className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <div className="space-y-1 text-xs">
            <p className="font-medium text-foreground">
              {perUser
                ? t("connections.bindingPerUserTitle", "Resolves as each end user")
                : t("connections.bindingServiceTitle", "Resolves as one shared account")}
            </p>
            <p className="text-muted-foreground">
              {perUser
                ? t(
                    "connections.bindingPerUserBody",
                    "Everyone links their own account and the agent acts as them. This follows from the authentication type — a user login is the only flow that produces a grant per person.",
                  )
                : t(
                    "connections.bindingServiceBody",
                    "One credential, the same for everybody. This follows from the authentication type — a fixed key is the same key for everyone however it is bound.",
                  )}
            </p>
            {perUser && (
              <p className="text-muted-foreground">
                {t(
                  "connections.bindingPerUserWhere",
                  "People connect their own accounts from",
                )}{" "}
                <button
                  type="button"
                  onClick={() => leaveFor("/manage/linked-accounts")}
                  className="text-primary hover:underline"
                  data-testid="connection-linked-accounts-link"
                >
                  {t("pages.linkedAccounts.title", "Linked accounts")}
                </button>
                .
              </p>
            )}
          </div>
        </div>

        {perUser && (
          <div className="space-y-2 rounded-lg border border-amber-500/30 bg-amber-500/5 p-3">
            <label className="flex items-start gap-2 text-xs">
              <input
                type="checkbox"
                className="mt-0.5"
                checked={draft.allowUnverifiedPrincipal}
                data-testid="allow-unverified-principal"
                onChange={(e) => {
                  if (e.target.checked) {
                    // Turning this ON is the decision worth interrupting: it
                    // lets anything that can assert a user id spend that user's
                    // stored credentials. Turning it off needs no ceremony.
                    setUnverifiedConfirmOpen(true);
                  } else {
                    setDraft({ ...draft, allowUnverifiedPrincipal: false });
                  }
                }}
              />
              <span>
                <span className="block font-medium text-foreground">
                  {t(
                    "connections.allowUnverified",
                    "Trust user identities asserted by a front proxy",
                  )}
                </span>
                <span className="block text-muted-foreground">
                  {t(
                    "connections.allowUnverifiedBody",
                    "Only for a deployment that authenticates its users upstream. With this on, anything that can name a user to your proxy can spend that user's stored credentials — nothing checks the claim afterwards.",
                  )}
                </span>
              </span>
            </label>
          </div>
        )}

        <ConnectionCredentialFields
          draft={draft}
          onPatchStatic={patchStatic}
          onPatchOAuth={patchOAuth}
          errors={showErrors ? errors : {}}
          idPrefix="connection"
          dense
        />

        {isOAuthType(draft.authType) && (
          <div className="space-y-4">
            <ScopesField
              value={draft.oauth?.scopes ?? []}
              onChange={(scopes) => patchOAuth({ scopes })}
              pending={pendingScope}
              onPendingChange={setPendingScope}
            />

            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1">
                <label className="text-xs font-medium" htmlFor="connection-client-auth-method">
                  {t("connections.clientAuthMethod", "Client authentication")}
                </label>
                <select
                  id="connection-client-auth-method"
                  data-testid="connection-client-auth-method"
                  className="flex h-9 w-full rounded-lg border border-border bg-background px-3 text-sm"
                  value={draft.oauth?.clientAuthMethod ?? "client_secret_basic"}
                  onChange={(e) => patchOAuth({ clientAuthMethod: e.target.value })}
                >
                  {CLIENT_AUTH_METHODS.map((method) => (
                    <option key={method} value={method}>
                      {method}
                    </option>
                  ))}
                </select>
                <p className="text-[11px] text-muted-foreground">
                  {t(
                    "connections.clientAuthMethodHint",
                    "How the client secret reaches the token endpoint. Guessing it wrong looks exactly like a wrong secret, so it is a setting rather than a fallback.",
                  )}
                </p>
              </div>
              <div className="space-y-1">
                <label className="text-xs font-medium" htmlFor="connection-discovery-url">
                  {t("connections.discoveryUrl", "Discovery URL (optional)")}
                </label>
                <Input
                  id="connection-discovery-url"
                  data-testid="connection-discovery-url"
                  className="font-mono text-xs"
                  dir="ltr"
                  value={draft.oauth?.discoveryUrl ?? ""}
                  onChange={(e) =>
                    patchOAuth({ discoveryUrl: e.target.value || null })
                  }
                  placeholder="https://auth.example.com/.well-known/openid-configuration"
                  aria-invalid={fieldError("oauth.discoveryUrl") !== undefined || undefined}
                />
                <ValidationMessage code={fieldError("oauth.discoveryUrl")} />
              </div>
            </div>

            <ExtraParamsField
              value={draft.oauth?.extraAuthParams ?? {}}
              onChange={(extraAuthParams) => patchOAuth({ extraAuthParams })}
              error={fieldError("oauth.extraAuthParams")}
            />

            {perUser && (
              <p className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
                <ShieldAlert className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                {t(
                  "connections.pkceAlwaysOn",
                  "PKCE (S256) is always applied to a user login and cannot be switched off — the redirect target is a public path.",
                )}
              </p>
            )}
          </div>
        )}
      </section>

      {/* Allowed origins */}
      <section className="space-y-3 rounded-xl border border-border/50 p-5">
        <h2 className="flex items-center gap-2 text-sm font-semibold">
          <Globe className="h-4 w-4 text-primary" aria-hidden="true" />
          {t("connections.allowedOrigins", "Where the credential may be sent")}
        </h2>
        <p className="text-xs text-muted-foreground">
          {t(
            "connections.allowedOriginsHint",
            "List every origin this credential is allowed to reach. Anything not listed is refused, so a later config edit cannot redirect it somewhere else.",
          )}
        </p>
        <OriginAllowlistField
          value={draft.baseUrlAllowlist}
          onChange={(baseUrlAllowlist) => setDraft({ ...draft, baseUrlAllowlist })}
          pending={pendingOrigin}
          onPendingChange={setPendingOrigin}
          error={fieldError("baseUrlAllowlist")}
          testId="connection-origins"
        />
      </section>

      {/* Advanced */}
      <section className="space-y-3 rounded-xl border border-border/50 p-5">
        <h2 className="text-sm font-semibold">
          {t("connections.advanced", "Advanced")}
        </h2>
        <div className="space-y-1 sm:max-w-xs">
          <label className="text-xs font-medium" htmlFor="connection-timeout">
            {t("connections.timeoutMs", "Token endpoint timeout (ms)")}
          </label>
          <Input
            id="connection-timeout"
            data-testid="connection-timeout"
            type="number"
            min={0}
            value={draft.timeoutMs ?? ""}
            onChange={(e) =>
              setDraft({
                ...draft,
                // Empty means "use the resolver's default", which is null and
                // not 0 — a zero-millisecond timeout would fail every call.
                timeoutMs: e.target.value === "" ? null : Number(e.target.value),
              })
            }
            placeholder={t("connections.timeoutDefault", "Default")}
          />
        </div>
      </section>

      {/* Raw */}
      <section className="overflow-hidden rounded-xl border border-border/50">
        <button
          type="button"
          className="flex w-full items-center justify-between px-5 py-3 text-sm font-medium transition-colors hover:bg-muted/30"
          onClick={() => setRawOpen(!rawOpen)}
          aria-expanded={rawOpen}
        >
          {t("connections.rawConfig", "Raw configuration")}
          {rawOpen ? (
            <ChevronUp className="h-4 w-4" aria-hidden="true" />
          ) : (
            <ChevronDown className="h-4 w-4" aria-hidden="true" />
          )}
        </button>
        {rawOpen && (
          <div className="border-t border-border/30 p-4">
            <pre
              className="max-h-96 overflow-auto rounded-lg bg-muted/30 p-4 font-mono text-xs"
              dir="ltr"
            >
              {JSON.stringify(draft, null, 2)}
            </pre>
          </div>
        )}
      </section>

      <AlertDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title={t("connections.confirmDelete", "Delete this connection?")}
        description={t(
          "connections.confirmDeleteDesc",
          "Every account linked through it is unlinked at the same time — tokens must not outlive the connection that produced them. Agents referring to it by name will stop being able to authenticate.",
        )}
        onConfirm={() => void handleDelete()}
        confirmLabel={t("common.delete", "Delete")}
        cancelLabel={t("common.cancel", "Cancel")}
        isPending={deleteMutation.isPending}
      />

      <UnsavedChangesDialog
        open={pendingExit !== null}
        onConfirm={() => {
          const to = pendingExit;
          setPendingExit(null);
          if (to) navigate(to);
        }}
        onCancel={() => setPendingExit(null)}
      />

      <AlertDialog
        open={unverifiedConfirmOpen}
        onOpenChange={setUnverifiedConfirmOpen}
        variant="warning"
        title={t(
          "connections.confirmUnverified",
          "Accept identities this deployment did not verify?",
        )}
        description={t(
          "connections.confirmUnverifiedDesc",
          "Anyone who can assert a user id to your front proxy will be able to spend that user's stored credentials for this connection. Nothing checks the claim afterwards — the id is the whole authority for choosing whose token to use. Only turn this on if your proxy genuinely authenticates every request it forwards.",
        )}
        onConfirm={() => {
          setDraft((prev) =>
            prev ? { ...prev, allowUnverifiedPrincipal: true } : prev,
          );
          setUnverifiedConfirmOpen(false);
        }}
        confirmLabel={t("connections.confirmUnverifiedAccept", "I understand, turn it on")}
        cancelLabel={t("common.cancel", "Cancel")}
      />
    </div>
  );
}

/* ─── Scopes ───────────────────────────────────────────────────── */

function ScopesField({
  value,
  onChange,
  pending,
  onPendingChange,
}: {
  value: string[];
  onChange: (scopes: string[]) => void;
  pending: string;
  onPendingChange: (pending: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <div className="space-y-2">
      <label className="text-xs font-medium" htmlFor="connection-scopes-input">
        {t("connections.scopes", "Scopes")}
      </label>
      <ChipInput
        values={value}
        onChange={onChange}
        pending={pending}
        onPendingChange={onPendingChange}
        // Providers document scopes space-delimited and that is how people
        // paste them; splitting here saves a row-at-a-time transcription that
        // is easy to get subtly wrong.
        splitOn={/[\s,]+/}
        placeholder="read:jira-work offline_access"
        inputLabel={t("connections.scopes", "Scopes")}
        testId="connection-scopes"
        ltr
      />
    </div>
  );
}

/* ─── Extra authorization parameters ───────────────────────────── */

function ExtraParamsField({
  value,
  onChange,
  error,
}: {
  value: Record<string, string>;
  onChange: (params: Record<string, string>) => void;
  error?: ValidationCode;
}) {
  const { t } = useTranslation();

  /**
   * The rows are a list, even though the field is a map.
   *
   * Deriving them from `Object.entries(value)` on every render meant a rename
   * passing through a name already in the map destroyed a row: `fromEntries`
   * merges the collision, the entry count drops by one, and the value that had
   * been beside the other name is gone — no warning, no undo, and the row
   * vanishes under the cursor mid-word. Keeping the list here lets both rows
   * exist while one of them is being typed, which is the only way the user can
   * see the clash and fix it.
   */
  const [rows, setRows] = useState<[string, string][]>(() => Object.entries(value));

  /** The last map this field emitted; anything else in `value` came from outside. */
  const emitted = useRef(value);

  useEffect(() => {
    if (value === emitted.current) return;
    emitted.current = value;
    setRows(Object.entries(value));
  }, [value]);

  const emit = (next: [string, string][]) => {
    setRows(next);
    // A later row wins the collision, matching what the wire format can hold.
    // Empty rows are dropped on the way out rather than being refused, so an
    // "Add parameter" the author thought better of costs them nothing.
    const map = Object.fromEntries(next.filter(([key]) => key.trim() !== ""));
    emitted.current = map;
    onChange(map);
  };

  const replace = (index: number, entry: [string, string]) =>
    emit(rows.map((current, i) => (i === index ? entry : current)));

  /** Names typed more than once — marked, because only one of them survives a save. */
  const duplicated = new Set(
    rows
      .map(([key]) => key)
      .filter((key, i, all) => key.trim() !== "" && all.indexOf(key) !== i),
  );

  return (
    <div className="space-y-2">
      <label className="text-xs font-medium">
        {t("connections.extraAuthParams", "Extra authorization parameters")}
      </label>
      <p className="text-[11px] text-muted-foreground">
        {t(
          "connections.extraAuthParamsHint",
          "Non-secret protocol parameters the provider expects — prompt, audience, access_type. Never a key or a token: this map is stored in the connection document in plain text.",
        )}
      </p>
      {rows.map(([key, val], index) => (
        <div key={index} className="space-y-1">
          <div className="flex gap-2">
            <Input
              className="h-8 font-mono text-xs"
              dir="ltr"
              value={key}
              onChange={(e) => replace(index, [e.target.value, val])}
              placeholder="prompt"
              aria-label={t("connections.paramName", "Parameter name")}
              aria-invalid={duplicated.has(key) || undefined}
              data-testid={`connection-param-name-${index}`}
            />
            <Input
              className="h-8 font-mono text-xs"
              dir="ltr"
              value={val}
              onChange={(e) => replace(index, [key, e.target.value])}
              placeholder="consent"
              aria-label={t("connections.paramValue", "Parameter value")}
              data-testid={`connection-param-value-${index}`}
            />
            <Button
              type="button"
              size="icon"
              variant="ghost"
              className="h-8 w-8 text-destructive hover:text-destructive"
              onClick={() => emit(rows.filter((_, i) => i !== index))}
              aria-label={t("connections.removeParam", "Remove parameter")}
              data-testid={`connection-remove-param-${index}`}
            >
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>
          {duplicated.has(key) && (
            <p
              className="text-[10px] text-amber-600 dark:text-amber-400"
              data-testid={`connection-param-duplicate-${index}`}
            >
              {t(
                "connections.paramDuplicate",
                "Another parameter already uses this name. Only the last one is saved.",
              )}
            </p>
          )}
        </div>
      ))}
      <Button
        type="button"
        size="sm"
        variant="outline"
        onClick={() => emit([...rows, ["", ""]])}
        data-testid="connection-add-param"
        disabled={rows.some(([key]) => key.trim() === "")}
      >
        <Plus className="h-3.5 w-3.5" aria-hidden="true" />
        {t("connections.addParam", "Add parameter")}
      </Button>
      <ValidationMessage code={error} />
    </div>
  );
}
