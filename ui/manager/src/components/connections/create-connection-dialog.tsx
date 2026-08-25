import { useCallback, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  ChevronLeft,
  ChevronRight,
  KeyRound,
  Lock,
  Server,
  UserCheck,
  type LucideIcon,
} from "lucide-react";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { StepDots } from "@/components/shared/step-dots";
import { ConnectionCredentialFields } from "@/components/connections/connection-credential-fields";
import { OriginAllowlistField } from "@/components/connections/origin-allowlist-field";
import { ValidationMessage } from "@/components/connections/validation-message";
import { useCreateConnection } from "@/hooks/use-connections";
import { getErrorMessage } from "@/lib/api-client";
import { commitPending } from "@/lib/chip-values";
import { authTypeDescription, authTypeLabel } from "@/lib/connection-labels";
import {
  AUTH_TYPES,
  emptyConnection,
  parseConnectionResourceUri,
  type AuthType,
  type ConnectionConfiguration,
  type OAuthConfig,
  type StaticAuth,
} from "@/lib/api/connections";
import { validateConnection } from "@/lib/connection-validation";

interface CreateConnectionDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated?: (id: string, version: number) => void;
}

type Step = "basics" | "credentials" | "origins";
const STEPS: Step[] = ["basics", "credentials", "origins"];

/**
 * Create a connection in three steps, then hand over to the editor.
 *
 * The steps are not decoration. `baseUrlAllowlist` is required by the backend,
 * and the auth block required for the chosen type is too, so a "quick create"
 * that asked only for a name would produce a 400 every time. Everything
 * optional — scopes, extra parameters, timeouts, the discovery URL — is left to
 * the editor, which is where a config is refined anyway.
 *
 * The auth type is offered as four described choices rather than an enum
 * select, because it is the one decision on this screen with consequences the
 * author cannot undo later: it fixes whether the connection resolves one shared
 * credential or one per person.
 */
export function CreateConnectionDialog({
  open,
  onOpenChange,
  onCreated,
}: CreateConnectionDialogProps) {
  const { t } = useTranslation();
  const createMutation = useCreateConnection();

  const [step, setStep] = useState<Step>("basics");
  const [draft, setDraft] = useState<ConnectionConfiguration>(() =>
    emptyConnection("STATIC"),
  );
  /**
   * The allowlist text that has been typed but not committed to a chip.
   *
   * Held here rather than inside the field so `handleCreate` can fold it in.
   * Without that, a user who typed an origin and clicked "Create connection"
   * was refused with "add at least one origin" directly underneath an input
   * that visibly contained one.
   */
  const [pendingOrigin, setPendingOrigin] = useState("");
  /** Errors are shown once a step has been left, not while it is being typed into. */
  const [touched, setTouched] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);

  /** What will actually be sent — the draft with any uncommitted text folded in. */
  const effectiveDraft = useMemo<ConnectionConfiguration>(
    () => ({
      ...draft,
      baseUrlAllowlist: commitPending(draft.baseUrlAllowlist, pendingOrigin),
    }),
    [draft, pendingOrigin],
  );

  const errors = useMemo(
    () => validateConnection(effectiveDraft),
    [effectiveDraft],
  );

  const isDirty =
    draft.name.trim() !== "" ||
    (draft.description ?? "").trim() !== "" ||
    draft.baseUrlAllowlist.length > 0 ||
    pendingOrigin.trim() !== "" ||
    JSON.stringify(draft.staticAuth) !==
      JSON.stringify(emptyConnection(draft.authType).staticAuth) ||
    JSON.stringify(draft.oauth) !==
      JSON.stringify(emptyConnection(draft.authType).oauth);

  const reset = useCallback(() => {
    setStep("basics");
    setDraft(emptyConnection("STATIC"));
    setPendingOrigin("");
    setTouched(false);
    setConfirmDiscard(false);
  }, []);

  const close = useCallback(() => {
    reset();
    onOpenChange(false);
  }, [reset, onOpenChange]);

  /**
   * Escape and the backdrop reach this. Three steps of transcribed OAuth
   * settings are too much to throw away on a stray keypress, so a dialog with
   * anything in it asks first.
   */
  const requestClose = useCallback(() => {
    if (isDirty) {
      setConfirmDiscard(true);
      return;
    }
    close();
  }, [isDirty, close]);

  /**
   * Switching type replaces the auth block wholesale rather than merging.
   *
   * Carrying a half-filled OAuth block onto a STATIC connection would send the
   * backend fields it validates against the wrong rules, and — more to the
   * point — it silently keeps a client secret reference on a connection that no
   * longer has an OAuth flow.
   */
  const changeAuthType = (authType: AuthType) => {
    setDraft((prev) => ({
      ...emptyConnection(authType),
      name: prev.name,
      description: prev.description,
      baseUrlAllowlist: prev.baseUrlAllowlist,
    }));
  };

  const patchStatic = (patch: Partial<StaticAuth>) =>
    setDraft((prev) => ({
      ...prev,
      staticAuth: { headerName: "", ...prev.staticAuth, ...patch },
    }));

  const patchOAuth = (patch: Partial<OAuthConfig>) =>
    setDraft((prev) => ({ ...prev, oauth: { ...prev.oauth, ...patch } }));

  const stepIndex = STEPS.indexOf(step);
  const isFirst = stepIndex === 0;
  const isLast = stepIndex === STEPS.length - 1;

  /** Which errors belong to which step, so Next only blocks on this step's fields. */
  const stepErrors = useMemo(() => {
    switch (step) {
      case "basics":
        return errors.name ? { name: errors.name } : {};
      case "credentials":
        // Everything that is not the name or the allowlist — those belong to
        // the steps either side, and blocking Next on a field the user has not
        // reached yet is the classic way a wizard becomes unusable.
        return Object.fromEntries(
          Object.entries(errors).filter(
            ([field]) => field !== "name" && field !== "baseUrlAllowlist",
          ),
        );
      case "origins":
        return errors.baseUrlAllowlist
          ? { baseUrlAllowlist: errors.baseUrlAllowlist }
          : {};
    }
  }, [errors, step]);

  const stepIsValid = Object.keys(stepErrors).length === 0;

  const handleNext = () => {
    setTouched(true);
    if (!stepIsValid) return;
    setTouched(false);
    setStep(STEPS[stepIndex + 1]!);
  };

  const handleBack = () => {
    setTouched(false);
    setStep(STEPS[stepIndex - 1]!);
  };

  const handleCreate = async () => {
    setTouched(true);
    if (Object.keys(errors).length > 0) return;
    try {
      const result = await createMutation.mutateAsync(effectiveDraft);
      const location = (result as { location?: string })?.location;
      toast.success(
        t("connections.created", {
          name: effectiveDraft.name,
          defaultValue: '"{{name}}" created.',
        }),
      );
      // Only follow a Location the backend actually sent. A proxy that strips
      // the header (a CORS `Access-Control-Expose-Headers` omission is the
      // common cause) would otherwise parse `""` into an empty id and navigate
      // to `/manage/connections/?version=1`, bouncing the user back to the list
      // right after being told it worked.
      if (location) {
        const { id, version } = parseConnectionResourceUri(location);
        if (id) onCreated?.(id, version);
      }
      close();
    } catch (err) {
      // The backend names the field it refused and why — a duplicate name, a
      // token URL outside the operator's allowlist, PER_USER without OIDC. None
      // of those can be predicted from here, so the message is the answer.
      toast.error(getErrorMessage(err));
    }
  };

  return (
    <>
      <AccessibleDialog
        open={open}
        onClose={requestClose}
        title={t("connections.createTitle", "New connection")}
        testId="create-connection-dialog"
        maxWidth="max-w-xl"
      >
        <StepDots total={STEPS.length} current={stepIndex} />

        <div className="min-h-[18rem] space-y-4">
          {step === "basics" && (
            <>
              <div className="space-y-1.5">
                <label
                  className="text-sm font-medium text-foreground"
                  htmlFor="create-connection-name"
                >
                  {t("connections.name", "Name")}
                </label>
                <Input
                  id="create-connection-name"
                  data-testid="create-connection-name"
                  value={draft.name}
                  // Functional, like every other update here: a non-functional spread
                  // captures the draft from its render, so two changes landing in one
                  // batch (a type click and a keystroke) lose the first.
                  onChange={(e) =>
                    setDraft((prev) => ({ ...prev, name: e.target.value }))
                  }
                  placeholder="jira"
                  autoComplete="off"
                  aria-invalid={(touched && errors.name !== undefined) || undefined}
                  aria-describedby="create-connection-name-error"
                />
                <p className="text-xs text-muted-foreground">
                  {t(
                    "connections.nameHint",
                    "Agents refer to this connection by name — ${connection:jira}. It cannot be changed later.",
                  )}
                </p>
                {touched && (
                  <ValidationMessage
                    code={errors.name}
                    id="create-connection-name-error"
                  />
                )}
              </div>

              <div className="space-y-1.5">
                <label
                  className="text-sm font-medium text-foreground"
                  htmlFor="create-connection-description"
                >
                  {t("connections.description", "Description")}
                </label>
                <Input
                  id="create-connection-description"
                  data-testid="create-connection-description"
                  value={draft.description ?? ""}
                  onChange={(e) =>
                    setDraft((prev) => ({ ...prev, description: e.target.value }))
                  }
                  placeholder={t(
                    "connections.descriptionPlaceholder",
                    "What this connects to, for whoever reads the list",
                  )}
                />
              </div>

              <AuthTypeChooser
                value={draft.authType}
                onChange={changeAuthType}
              />
            </>
          )}

          {step === "credentials" && (
            <ConnectionCredentialFields
              draft={draft}
              onPatchStatic={patchStatic}
              onPatchOAuth={patchOAuth}
              errors={touched ? errors : {}}
              idPrefix="create-connection"
            />
          )}

          {step === "origins" && (
            <div className="space-y-2">
              <label className="text-sm font-medium text-foreground">
                {t("connections.allowedOrigins", "Where the credential may be sent")}
              </label>
              <p className="text-xs text-muted-foreground">
                {t(
                  "connections.allowedOriginsHint",
                  "List every origin this credential is allowed to reach. Anything not listed is refused, so a later config edit cannot redirect it somewhere else.",
                )}
              </p>
              <OriginAllowlistField
                value={draft.baseUrlAllowlist}
                onChange={(baseUrlAllowlist) =>
                  setDraft((prev) => ({ ...prev, baseUrlAllowlist }))
                }
                pending={pendingOrigin}
                onPendingChange={setPendingOrigin}
                error={touched ? errors.baseUrlAllowlist : undefined}
                testId="create-connection-origins"
              />
            </div>
          )}
        </div>

        <div className="mt-6 flex justify-between">
          <Button
            variant="outline"
            onClick={handleBack}
            disabled={isFirst}
            className={isFirst ? "invisible" : ""}
          >
            <ChevronLeft className="h-4 w-4" aria-hidden="true" />
            {t("common.back", "Back")}
          </Button>
          {isLast ? (
            <Button
              onClick={() => void handleCreate()}
              disabled={createMutation.isPending}
              data-testid="create-connection-submit"
            >
              {createMutation.isPending
                ? t("common.saving", "Saving…")
                : t("connections.createSubmit", "Create connection")}
            </Button>
          ) : (
            <Button onClick={handleNext} data-testid="create-connection-next">
              {t("common.next", "Next")}
              <ChevronRight className="h-4 w-4" aria-hidden="true" />
            </Button>
          )}
        </div>
      </AccessibleDialog>

      <UnsavedChangesDialog
        open={confirmDiscard}
        onConfirm={close}
        onCancel={() => setConfirmDiscard(false)}
        title={t("connections.discardNewTitle", "Discard this connection?")}
        message={t(
          "connections.discardNewBody",
          "What you have filled in so far will be lost. Nothing has been created yet.",
        )}
      />
    </>
  );
}

/* ─── Auth type chooser ────────────────────────────────────────── */

const AUTH_TYPE_ICONS: Record<AuthType, LucideIcon> = {
  STATIC: KeyRound,
  BASIC: Lock,
  OAUTH2_CLIENT_CREDENTIALS: Server,
  OAUTH2_AUTHORIZATION_CODE: UserCheck,
};

/**
 * The four auth types as a real radio group.
 *
 * `role="radio"` without an owning `radiogroup` is invalid ARIA: the options
 * are announced as four unrelated toggles with no group name and no "1 of 4",
 * and the arrow keys every screen-reader user expects do nothing. The roving
 * `tabIndex` and key handling follow `ViewToggle`, which is the house pattern
 * the screens skill points at.
 */
function AuthTypeChooser({
  value,
  onChange,
}: {
  value: AuthType;
  onChange: (authType: AuthType) => void;
}) {
  const { t } = useTranslation();
  const groupRef = useRef<HTMLDivElement>(null);

  const move = (delta: number) => {
    const index = AUTH_TYPES.indexOf(value);
    const next = AUTH_TYPES[(index + delta + AUTH_TYPES.length) % AUTH_TYPES.length]!;
    // Focus the target synchronously — it is already in the DOM, only its
    // `tabIndex` is about to change. Deferring this to a rAF left a timer that
    // outlives the dialog and calls `.focus()` on a detached node, which drops
    // focus to `body` at whatever moment it happens to fire.
    groupRef.current
      ?.querySelector<HTMLElement>(`[data-testid="auth-type-choice-${next}"]`)
      ?.focus();
    onChange(next);
  };

  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium text-foreground">
        {t("connections.howItAuthenticates", "How it authenticates")}
      </legend>
      <div
        ref={groupRef}
        role="radiogroup"
        aria-label={t("connections.howItAuthenticates", "How it authenticates")}
        className="grid gap-2"
        onKeyDown={(e) => {
          if (e.key === "ArrowDown" || e.key === "ArrowRight") {
            e.preventDefault();
            move(1);
          } else if (e.key === "ArrowUp" || e.key === "ArrowLeft") {
            e.preventDefault();
            move(-1);
          }
        }}
      >
        {AUTH_TYPES.map((authType) => {
          const Icon = AUTH_TYPE_ICONS[authType];
          const selected = value === authType;
          return (
            <button
              key={authType}
              type="button"
              role="radio"
              aria-checked={selected}
              // Roving: the group is one tab stop, and the arrows move within it.
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(authType)}
              data-testid={`auth-type-choice-${authType}`}
              className={`flex items-start gap-3 rounded-lg border p-3 text-start transition-colors ${
                selected
                  ? "border-primary bg-primary/5"
                  : "border-border hover:border-primary/40 hover:bg-secondary/50"
              }`}
            >
              <Icon
                className={`mt-0.5 h-4 w-4 shrink-0 ${selected ? "text-primary" : "text-muted-foreground"}`}
                aria-hidden="true"
              />
              <span className="min-w-0">
                <span className="block text-sm font-medium text-foreground">
                  {authTypeLabel(t, authType)}
                </span>
                <span className="block text-xs text-muted-foreground">
                  {authTypeDescription(t, authType)}
                </span>
              </span>
            </button>
          );
        })}
      </div>
    </fieldset>
  );
}
