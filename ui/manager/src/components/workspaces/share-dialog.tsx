import { useCallback, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  AlertTriangle,
  Building2,
  Check,
  Copy,
  FolderInput,
  Globe,
  Loader2,
  Lock,
  Trash2,
  Users,
  UserPlus,
} from "lucide-react";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { ApiClientError, getErrorMessage } from "@/lib/api-client";
import { agentKeys } from "@/lib/query-keys";
import {
  ACCESS_LEVELS,
  getShareInfo,
  levelIncludes,
  moveToSpace,
  revokeShare,
  setResourceVisibility,
  shareResource,
  transferOwnership,
  type AccessLevel,
  type ResourceGrant,
  type ResourceVisibility,
  type ShareOptions,
  type ShareResult,
  type ShareTarget,
} from "@/lib/api/sharing";
import { publicChatLinkFor } from "@/lib/resource-links";
import { describeSpace, isUserSubject, parseSubjectInput } from "@/lib/spaces";
import { useHasRole } from "@/hooks/use-auth";
import { useSpaces } from "@/hooks/use-spaces";
import { ShareSubjectInput } from "@/components/workspaces/share-subject-input";

/** Which mutation produced a {@link ShareResult}, so the summary can name it. */
type ShareAction = "share" | "revoke" | "visibility" | "transfer" | "move";

/** A change that was previewed and is waiting for the user to confirm it. */
interface PendingChange {
  action: ShareAction;
  preview: ShareResult;
  apply: () => Promise<ShareResult>;
  successMessage: string;
}

interface ShareDialogProps {
  open: boolean;
  onClose: () => void;
  /** The resource being shared — an agent id, a workflow id, and so on. */
  resourceId: string;
  /** Shown in the title so the user knows what they are about to share. */
  resourceName?: string;
  /**
   * The chat address to offer for copying, for an agent. Absent for resources
   * nobody chats with.
   */
  chatLink?: string;
}

/**
 * Share one resource with people and teams.
 *
 * <h3>The consequence is shown before, not only after</h3> Sharing an agent
 * cascades through the workflows, rule sets, LLM configs and output sets it
 * references — and sharing a group reaches every agent in it. That is invisible
 * in the request, so any change that would touch more than the resource itself,
 * or leave something out, is previewed first: the dialog lists what it will
 * change and what it will skip, and waits for a confirmation.
 */
export function ShareDialog({ open, onClose, resourceId, resourceName, chatLink }: ShareDialogProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { spaces } = useSpaces();

  const [subjectInput, setSubjectInput] = useState("");
  const [level, setLevel] = useState<AccessLevel>("USE");
  const [busy, setBusy] = useState(false);
  /**
   * The subject an OWN grant has been confirmed for, or null — bound to what the
   * warning named, so retyping the name withdraws the confirmation.
   */
  const [confirmedSubject, setConfirmedSubject] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<{ result: ShareResult; action: ShareAction } | null>(null);
  const [pending, setPending] = useState<PendingChange | null>(null);

  const {
    data: info,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ["shares", resourceId],
    queryFn: () => getShareInfo(resourceId),
    enabled: open && !!resourceId,
  });

  const isOwner = levelIncludes(info?.callerLevel, "OWN");
  // Transfer is `@RolesAllowed("eddi-admin")` on the backend, and is the only
  // control here that a non-owner may legitimately use.
  const isAdmin = useHasRole("eddi-admin");

  const afterChange = useCallback(
    async (result: ShareResult, action: ShareAction) => {
      setLastResult({ result, action });
      await refetch();
      // Owner, visibility and space ride on the descriptor, so every listing and
      // detail view showing this resource is now stale.
      await queryClient.invalidateQueries({ queryKey: agentKeys.all });
      await queryClient.invalidateQueries({ queryKey: agentKeys.detail(resourceId) });
      await queryClient.invalidateQueries({ queryKey: agentKeys.descriptor(resourceId) });
      await queryClient.invalidateQueries({ queryKey: ["workflows"] });
      await queryClient.invalidateQueries({ queryKey: ["resources"] });
    },
    [refetch, queryClient, resourceId]
  );

  /**
   * Previews a change and applies it straight away when it touches only this
   * resource; otherwise holds it for confirmation.
   */
  const previewThenApply = useCallback(
    async (action: ShareAction, run: (options: ShareOptions) => Promise<ShareResult>, successMessage: string) => {
      setBusy(true);
      try {
        const preview = await run({ dryRun: true });
        const wide = (preview.updated?.length ?? 0) > 1 || (preview.skipped?.length ?? 0) > 0;
        if (wide) {
          setPending({ action, preview, apply: () => run({}), successMessage });
          return false;
        }
        const result = await run({});
        await afterChange(result, action);
        toast.success(successMessage);
        return true;
      } catch (e) {
        toast.error(getErrorMessage(e));
        return false;
      } finally {
        setBusy(false);
      }
    },
    [afterChange]
  );

  const confirmPending = useCallback(async () => {
    if (!pending) return;
    setBusy(true);
    try {
      const result = await pending.apply();
      await afterChange(result, pending.action);
      toast.success(pending.successMessage);
      if (pending.action === "share") {
        setSubjectInput("");
        setConfirmedSubject(null);
      }
      setPending(null);
    } catch (e) {
      toast.error(getErrorMessage(e));
    } finally {
      setBusy(false);
    }
  }, [pending, afterChange]);

  const handleShare = useCallback(async () => {
    const parsed = parseSubjectInput(subjectInput);
    if ("error" in parsed) {
      toast.error(
        parsed.error === "unknown-prefix"
          ? t("workspaces.share.unknownPrefix", "Use 'user:' or 'team:' — or just a name for a person.")
          : t("workspaces.share.subjectRequired", "Enter a person or team to share with.")
      );
      return;
    }
    // Handing someone OWN lets them delete the resource and re-share it to
    // anyone, so it gets a second look — bound to the subject it was shown for.
    if (level === "OWN" && confirmedSubject !== parsed.subject) {
      setConfirmedSubject(parsed.subject);
      return;
    }
    const applied = await previewThenApply(
      "share",
      (options) => shareResource(resourceId, parsed.subject, level, options),
      t("workspaces.share.shared", "Shared")
    );
    if (applied) {
      setSubjectInput("");
      setConfirmedSubject(null);
    }
  }, [subjectInput, level, confirmedSubject, resourceId, previewThenApply, t]);

  const handleRevoke = useCallback(
    async (subject: string) => {
      // Removing access only ever narrows, so it is applied without a preview.
      setBusy(true);
      try {
        const result = await revokeShare(resourceId, subject);
        await afterChange(result, "revoke");
        toast.success(t("workspaces.share.revoked", "Access removed"));
      } catch (e) {
        toast.error(getErrorMessage(e));
      } finally {
        setBusy(false);
      }
    },
    [resourceId, afterChange, t]
  );

  /** Resolves to null once applied, or to the error message — the confirm panel stays up on a failure. */
  const handleVisibility = useCallback(
    async (visibility: ResourceVisibility, cascade: boolean): Promise<string | null> => {
      setBusy(true);
      try {
        const result = await setResourceVisibility(resourceId, visibility, { cascade });
        await afterChange(result, "visibility");
        toast.success(t("workspaces.share.visibilityUpdated", "Visibility updated"));
        return null;
      } catch (e) {
        const message = getErrorMessage(e);
        toast.error(message);
        return message;
      } finally {
        setBusy(false);
      }
    },
    [resourceId, afterChange, t]
  );

  const handleMove = useCallback(
    (spaceId: string) =>
      previewThenApply(
        "move",
        (options) => moveToSpace(resourceId, spaceId, options),
        t("workspaces.move.done", "Moved")
      ),
    [resourceId, previewThenApply, t]
  );

  const title = resourceName
    ? t("workspaces.share.titleNamed", "Share “{{name}}”", { name: resourceName })
    : t("workspaces.share.title", "Share");

  const grants = useMemo(() => info?.grants ?? [], [info]);
  const teamSpaces = useMemo(
    () => spaces.filter((space) => space.kind === "team" && space.id !== info?.spaceId),
    [spaces, info?.spaceId]
  );

  /** Whether the button is currently asking rather than acting. */
  const awaitingOwnerConfirmation = level === "OWN" && confirmedSubject !== null;

  return (
    <AccessibleDialog open={open} onClose={onClose} title={title} maxWidth="max-w-lg" testId="share-dialog">
      <div className="p-5">
        {isLoading && (
          <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
            {t("common.loading", "Loading…")}
          </div>
        )}

        {error && (
          <p className="py-4 text-sm text-destructive" role="alert">
            {friendlyError(t, error)}
          </p>
        )}

        {/* `info` deliberately does not render alongside an error: TanStack keeps
            the last good data through a failed refetch, and live-looking controls
            under an error message invite clicks that will fail. */}
        {info && !error && (
          <div className="space-y-5">
            <OwnerLine owner={info.ownerLabel ?? info.ownerId ?? null} spaceId={info.spaceId ?? null} />

            {/* A published agent gets the public Chat UI address, which works
                without signing in; anything else needs the Manager's chat. */}
            {chatLink && (
              <CopyChatLink link={info.visibility === "published" ? publicChatLinkFor(resourceId) : chatLink} />
            )}

            {pending && (
              <PendingPreview
                pending={pending}
                busy={busy}
                onConfirm={() => void confirmPending()}
                onCancel={() => setPending(null)}
              />
            )}

            {!isOwner && (
              <p className="rounded-md border border-border bg-muted/40 p-3 text-sm text-muted-foreground">
                {t(
                  "workspaces.share.notOwner",
                  "Only the owner can change who has access. Ask them if you need this shared more widely."
                )}
              </p>
            )}

            {isAdmin && (
              <TransferOwnership
                resourceId={resourceId}
                currentOwner={info.ownerLabel ?? info.ownerId ?? null}
                busy={busy}
                onBusy={setBusy}
                onTransferred={afterChange}
              />
            )}

            {isOwner && !pending && (
              <>
                <VisibilityChooser
                  key={resourceId}
                  current={info.visibility}
                  busy={busy}
                  onChange={handleVisibility}
                />

                {teamSpaces.length > 0 && (
                  <MoveToTeam teams={teamSpaces} busy={busy} onMove={(spaceId) => void handleMove(spaceId)} />
                )}

                <section className="space-y-2">
                  <h3 className="text-sm font-medium">{t("workspaces.share.peopleAndTeams", "People and teams")}</h3>

                  <div className="flex flex-col gap-2 sm:flex-row">
                    <ShareSubjectInput
                      value={subjectInput}
                      disabled={busy}
                      onChange={(value) => {
                        setSubjectInput(value);
                        setConfirmedSubject(null);
                      }}
                      onPick={(match) => {
                        setSubjectInput(match.subject);
                        setConfirmedSubject(null);
                      }}
                      onSubmit={() => {
                        if (busy) return;
                        // Enter arms an ownership transfer but never completes one:
                        // two quick presses would otherwise sail through the
                        // confirmation the second press is meant to read.
                        if (level === "OWN" && confirmedSubject) return;
                        void handleShare();
                      }}
                    />
                    <select
                      value={level}
                      onChange={(e) => {
                        setLevel(e.target.value as AccessLevel);
                        setConfirmedSubject(null);
                      }}
                      aria-label={t("workspaces.share.levelLabel", "Access level")}
                      data-testid="share-level-select"
                      className="h-10 rounded-lg border border-border bg-background px-2 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    >
                      {ACCESS_LEVELS.map((l) => (
                        <option key={l} value={l}>
                          {levelLabel(t, l)}
                        </option>
                      ))}
                    </select>
                    <Button
                      onClick={() => void handleShare()}
                      disabled={busy}
                      variant={awaitingOwnerConfirmation ? "destructive" : "primary"}
                      data-testid="share-submit"
                    >
                      <UserPlus className="me-2 h-4 w-4" aria-hidden="true" />
                      {awaitingOwnerConfirmation
                        ? t("workspaces.share.confirmOwner", "Confirm transfer")
                        : t("workspaces.share.add", "Share")}
                    </Button>
                  </div>

                  <p className="text-xs text-muted-foreground">{levelHint(t, level)}</p>

                  {awaitingOwnerConfirmation && (
                    <p className="text-xs text-destructive" role="alert" data-testid="share-owner-warning">
                      {t(
                        "workspaces.share.ownerWarning",
                        "They will be able to delete this and share it with anyone. You cannot take that back on your own."
                      )}
                    </p>
                  )}

                  {grants.length === 0 ? (
                    <p className="py-2 text-sm text-muted-foreground">
                      {t("workspaces.share.noGrants", "Not shared with anyone yet.")}
                    </p>
                  ) : (
                    <ul className="divide-y divide-border rounded-md border border-border">
                      {grants.map((grant) => (
                        <GrantRow key={grant.subject} grant={grant} busy={busy} onRevoke={() => void handleRevoke(grant.subject)} />
                      ))}
                    </ul>
                  )}
                </section>
              </>
            )}

            {lastResult && <CascadeSummary result={lastResult.result} action={lastResult.action} />}
          </div>
        )}
      </div>
    </AccessibleDialog>
  );
}

function GrantRow({ grant, busy, onRevoke }: { grant: ResourceGrant; busy: boolean; onRevoke: () => void }) {
  const { t } = useTranslation();
  const isPerson = grant.kind ? grant.kind === "user" : isUserSubject(grant.subject);
  const name = grant.label || describeSpace(grant.subject) || grant.subject;
  const unknown = grant.known === false;
  return (
    <li className="flex items-center gap-3 px-3 py-2" data-testid={`share-grant-${grant.subject}`}>
      {isPerson ? (
        <UserPlus className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      ) : (
        <Users className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      )}
      {/* The person/team distinction is icon-only and the icon is aria-hidden,
          so a screen reader needs it said. */}
      <span className="sr-only">
        {isPerson ? t("workspaces.share.subjectIsPerson", "Person") : t("workspaces.share.subjectIsTeam", "Team")}
      </span>
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-sm">{name}</span>
        {grant.detail && <span className="truncate text-xs text-muted-foreground">{grant.detail}</span>}
        {unknown && (
          <span className="flex items-center gap-1 text-xs text-warning" data-testid="share-grant-unknown">
            <AlertTriangle className="h-3 w-3" aria-hidden="true" />
            {t("workspaces.share.unknownGrant", "No one who signs in matches this — it reaches nobody. Remove it and share again.")}
          </span>
        )}
      </span>
      <Badge variant="secondary">{levelLabel(t, grant.level)}</Badge>
      <Button
        variant="ghost"
        size="sm"
        disabled={busy}
        onClick={onRevoke}
        aria-label={t("workspaces.share.revokeFor", "Stop sharing with {{subject}}", { subject: name })}
      >
        <Trash2 className="h-4 w-4" aria-hidden="true" />
      </Button>
    </li>
  );
}

/**
 * The preview of a change that reaches beyond this resource, waiting for a yes.
 */
function PendingPreview({
  pending,
  busy,
  onConfirm,
  onCancel,
}: {
  pending: PendingChange;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const updated = pending.preview.updated ?? [];
  const skipped = pending.preview.skipped ?? [];
  return (
    <section className="space-y-3 rounded-md border border-primary/30 bg-primary/5 p-3" data-testid="share-preview" role="alert">
      <p className="text-sm font-medium">
        {t("workspaces.preview.title", "This change reaches {{count}} resource", { count: updated.length })}
      </p>
      <ul className="list-inside list-disc text-xs text-muted-foreground">
        {updated.slice(0, MAX_LISTED).map((target) => (
          <li key={target.id} className="truncate">
            <TargetLabel target={target} />
          </li>
        ))}
        {updated.length > MAX_LISTED && (
          <li className="list-none italic">
            {t("workspaces.share.andMore", "and {{count}} more", { count: updated.length - MAX_LISTED })}
          </li>
        )}
      </ul>
      {skipped.length > 0 && (
        <p className="text-xs text-warning">
          {t("workspaces.share.cascadeSkipped", "{{count}} resource left unchanged — you do not own it", {
            count: skipped.length,
          })}
        </p>
      )}
      <div className="flex gap-2">
        <Button size="sm" onClick={onConfirm} disabled={busy} data-testid="share-preview-confirm">
          <Check className="h-4 w-4" aria-hidden="true" />
          {t("workspaces.preview.confirm", "Apply to all of them")}
        </Button>
        <Button size="sm" variant="outline" onClick={onCancel} disabled={busy} data-testid="share-preview-cancel">
          {t("common.cancel", "Cancel")}
        </Button>
      </div>
    </section>
  );
}

/** File the resource under one of the caller's teams. */
function MoveToTeam({
  teams,
  busy,
  onMove,
}: {
  teams: { id: string; label: string }[];
  busy: boolean;
  onMove: (spaceId: string) => void;
}) {
  const { t } = useTranslation();
  const [target, setTarget] = useState(teams[0]?.id ?? "");
  return (
    <section className="space-y-2" data-testid="move-to-team">
      <h3 className="text-sm font-medium">{t("workspaces.move.title", "Move to a team")}</h3>
      <div className="flex flex-col gap-2 sm:flex-row">
        <select
          value={target}
          onChange={(e) => setTarget(e.target.value)}
          aria-label={t("workspaces.move.team", "Team")}
          className="h-10 flex-1 rounded-lg border border-border bg-background px-2 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          data-testid="move-team-select"
        >
          {teams.map((team) => (
            <option key={team.id} value={team.id}>
              {team.label}
            </option>
          ))}
        </select>
        <Button variant="outline" onClick={() => onMove(target)} disabled={busy || !target} data-testid="move-submit">
          <FolderInput className="h-4 w-4" aria-hidden="true" />
          {t("workspaces.move.action", "Move")}
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        {t(
          "workspaces.move.hint",
          "Everyone in the team can then find and edit it. You stay the owner — only you can delete it or share it further."
        )}
      </p>
    </section>
  );
}

/** A copyable address for chatting with an agent — what "share it with a colleague" usually means. */
function CopyChatLink({ link }: { link: string }) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(link);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error(t("workspaces.share.copyFailed", "Could not copy — select the address and copy it yourself."));
    }
  };
  return (
    <section className="space-y-1" data-testid="share-chat-link">
      <h3 className="text-sm font-medium">{t("workspaces.share.chatLink", "Chat link")}</h3>
      <div className="flex gap-2">
        <Input readOnly value={link} onFocus={(e) => e.currentTarget.select()} aria-label={t("workspaces.share.chatLink", "Chat link")} />
        <Button variant="outline" onClick={() => void copy()} data-testid="share-chat-link-copy">
          {copied ? <Check className="h-4 w-4" aria-hidden="true" /> : <Copy className="h-4 w-4" aria-hidden="true" />}
          {copied ? t("workspaces.share.copied", "Copied") : t("workspaces.share.copy", "Copy")}
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        {t("workspaces.share.chatLinkHint", "Works for anyone the agent is shared with at “Can chat” or above.")}
      </p>
    </section>
  );
}

/**
 * Reassign a resource's owner. Administrators only — for a resource whose owner
 * has left, which nobody who remains could otherwise recover. Confirmed before it
 * fires, bound to the subject it was shown for.
 */
function TransferOwnership({
  resourceId,
  currentOwner,
  busy,
  onBusy,
  onTransferred,
}: {
  resourceId: string;
  currentOwner: string | null;
  busy: boolean;
  onBusy: (busy: boolean) => void;
  onTransferred: (result: ShareResult, action: ShareAction) => Promise<void>;
}) {
  const { t } = useTranslation();
  const [input, setInput] = useState("");
  const [confirmed, setConfirmed] = useState<string | null>(null);

  const parsed = parseSubjectInput(input);
  const subject = "error" in parsed ? null : parsed.subject;
  const awaitingConfirmation = subject !== null && confirmed === subject;

  const handleTransfer = useCallback(async () => {
    const result = parseSubjectInput(input);
    if ("error" in result) {
      toast.error(t("workspaces.transfer.subjectRequired", "Enter the person to make owner."));
      return;
    }
    if (confirmed !== result.subject) {
      setConfirmed(result.subject);
      return;
    }
    onBusy(true);
    try {
      // The server resolves a name or verified email to the account, and derives
      // the new owner's personal space itself.
      const transferred = await transferOwnership(resourceId, input.trim().replace(/^user:/, ""));
      await onTransferred(transferred, "transfer");
      setInput("");
      setConfirmed(null);
      toast.success(t("workspaces.transfer.done", "Ownership transferred"));
    } catch (e) {
      toast.error(getErrorMessage(e));
    } finally {
      onBusy(false);
    }
  }, [input, confirmed, resourceId, onBusy, onTransferred, t]);

  return (
    <section className="space-y-2 rounded-md border border-warning/30 bg-warning/5 p-3" data-testid="transfer-ownership">
      <h3 className="text-sm font-medium">{t("workspaces.transfer.title", "Transfer ownership")}</h3>
      <p className="text-xs text-muted-foreground">
        {t(
          "workspaces.transfer.hint",
          "Administrators only. Use this when the current owner has left and nobody else can grant access."
        )}
      </p>
      <div className="flex flex-col gap-2 sm:flex-row">
        <Input
          value={input}
          onChange={(e) => {
            setInput(e.target.value);
            setConfirmed(null);
          }}
          placeholder={t("workspaces.transfer.placeholder", "user:alice")}
          disabled={busy}
          aria-label={t("workspaces.transfer.title", "Transfer ownership")}
          data-testid="transfer-subject-input"
        />
        <Button
          variant={awaitingConfirmation ? "destructive" : "outline"}
          onClick={handleTransfer}
          disabled={busy || !input.trim()}
          data-testid="transfer-submit"
        >
          {awaitingConfirmation
            ? t("workspaces.transfer.confirm", "Confirm transfer")
            : t("workspaces.transfer.action", "Transfer")}
        </Button>
      </div>
      {awaitingConfirmation && (
        <p className="text-xs text-destructive" role="alert" data-testid="transfer-warning">
          {t("workspaces.transfer.warning", {
            owner: currentOwner ?? t("workspaces.share.unowned", "No recorded owner"),
            defaultValue: "This replaces the current owner ({{owner}}). They lose control of this resource.",
          })}
        </p>
      )}
    </section>
  );
}

function OwnerLine({ owner, spaceId }: { owner: string | null; spaceId: string | null }) {
  const { t } = useTranslation();
  const space = describeSpace(spaceId);
  return (
    <p className="text-sm text-muted-foreground" data-testid="share-owner-line">
      {owner ? t("workspaces.share.ownedBy", "Owned by {{owner}}", { owner }) : t("workspaces.share.unowned", "No recorded owner")}
      {space ? ` · ${t("workspaces.share.inSpace", "in {{space}}", { space })}` : ""}
    </p>
  );
}

/**
 * Pick a visibility — and confirm it before anything is written.
 *
 * A visibility change is not one resource's setting: by default it cascades
 * through every workflow and configuration the resource references, and it
 * REPLACES each one's visibility rather than merging with it. Whatever those
 * documents were set to individually is gone, and nothing records it. The
 * buttons used to fire that on a single click. Now a click only proposes the
 * change; it says what will happen, lets the owner keep it to this resource
 * alone, and applies it on an explicit confirm.
 */
function VisibilityChooser({
  current,
  busy,
  onChange,
}: {
  current: ResourceVisibility;
  busy: boolean;
  onChange: (v: ResourceVisibility, cascade: boolean) => Promise<string | null>;
}) {
  const { t } = useTranslation();
  const [proposed, setProposed] = useState<ResourceVisibility | null>(null);
  const [cascade, setCascade] = useState(true);
  /** Why the last apply failed — shown in the still-open panel so a retry is one click. */
  const [applyError, setApplyError] = useState<string | null>(null);
  const options: { value: ResourceVisibility; icon: typeof Lock; label: string; hint: string }[] = [
    {
      value: "private",
      icon: Lock,
      label: t("workspaces.visibility.private", "Private"),
      hint: t("workspaces.visibility.privateHint", "Only you and people you share it with."),
    },
    {
      value: "space",
      icon: Users,
      label: t("workspaces.visibility.space", "Workspace"),
      hint: t("workspaces.visibility.spaceHint", "Everyone in this resource's workspace."),
    },
    {
      value: "internal",
      icon: Building2,
      label: t("workspaces.visibility.internal", "Everyone signed in"),
      hint: t(
        "workspaces.visibility.internalHint",
        "Anyone who signs in can chat with it. Its configuration stays private. Not reachable anonymously."
      ),
    },
    {
      value: "published",
      icon: Globe,
      label: t("workspaces.visibility.published", "Published"),
      hint: t(
        "workspaces.visibility.publishedHint",
        "Everyone with access to this deployment can read it — anonymous chat visitors included."
      ),
    },
  ];
  const proposedLabel = options.find((o) => o.value === proposed)?.label ?? "";

  return (
    <section className="space-y-2">
      <h3 className="text-sm font-medium">{t("workspaces.share.visibility", "Visibility")}</h3>
      <div className="grid gap-2 sm:grid-cols-2">
        {options.map((opt) => {
          const Icon = opt.icon;
          const selected = current === opt.value;
          const pending = proposed === opt.value;
          return (
            <button
              key={opt.value}
              type="button"
              disabled={busy}
              onClick={() => {
                // Choosing what is already set is not a change, and must not
                // re-cascade it over resources that differ on purpose.
                setProposed(selected ? null : opt.value);
                setCascade(true);
                setApplyError(null);
              }}
              aria-pressed={selected}
              data-testid={`visibility-${opt.value}`}
              className={[
                "flex flex-col gap-1 rounded-md border p-3 text-start transition-colors",
                "focus:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60",
                selected
                  ? "border-primary bg-primary/5"
                  : pending
                    ? "border-warning bg-warning/5"
                    : "border-border hover:bg-accent",
              ].join(" ")}
            >
              <span className="flex items-center gap-2 text-sm font-medium">
                <Icon className="h-4 w-4" aria-hidden="true" />
                {opt.label}
              </span>
              <span className="text-xs text-muted-foreground">{opt.hint}</span>
            </button>
          );
        })}
      </div>

      {proposed && (
        <div
          className="space-y-2 rounded-md border border-warning/40 bg-warning/5 p-3"
          role="alert"
          data-testid="visibility-confirm"
        >
          <p className="text-sm">
            {t("workspaces.visibility.confirmTitle", "Change visibility to {{visibility}}?", {
              visibility: proposedLabel,
            })}
          </p>
          <label className="flex items-start gap-2 text-xs">
            <input
              type="checkbox"
              checked={cascade}
              onChange={(e) => setCascade(e.target.checked)}
              className="mt-0.5"
              data-testid="visibility-cascade"
            />
            {t(
              "workspaces.visibility.cascadeLabel",
              "Also apply it to the workflows and configurations this references",
            )}
          </label>
          <p className="text-xs text-muted-foreground">
            {cascade
              ? t(
                  "workspaces.visibility.cascadeWarning",
                  "Each referenced resource you own gets this visibility, replacing whatever it was set to on its own. Their previous settings are not kept.",
                )
              : t(
                  "workspaces.visibility.noCascadeWarning",
                  "Only this resource changes. Anyone who can now see it may still be unable to open what it references.",
                )}
          </p>
          {applyError && (
            <p className="text-xs text-destructive" data-testid="visibility-apply-error">
              {applyError}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button
              variant="ghost"
              size="sm"
              disabled={busy}
              onClick={() => {
                setProposed(null);
                setApplyError(null);
              }}
              data-testid="visibility-cancel"
            >
              {t("common.cancel", "Cancel")}
            </Button>
            <Button
              size="sm"
              disabled={busy}
              onClick={async () => {
                const target = proposed;
                const error = await onChange(target, cascade);
                // Closed only once the change is applied: a failed PUT leaves
                // the proposal, and the reason, where the operator can retry.
                setApplyError(error);
                if (error === null) setProposed((open) => (open === target ? null : open));
              }}
              data-testid="visibility-apply"
            >
              {t("workspaces.visibility.apply", "Change visibility")}
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}

/**
 * What the last change actually did — and, when something was left alone
 * because it belongs to somebody else, what.
 */
function CascadeSummary({ result, action }: { result: ShareResult; action: ShareAction }) {
  const { t } = useTranslation();
  const skipped = result.skipped ?? [];
  const updated = result.updated ?? [];

  return (
    <div className="space-y-2 rounded-md border border-border bg-muted/30 p-3" data-testid="share-cascade-summary">
      <p className="text-sm">
        {/* Which verb matters: "Applied to 3 resources" after a revoke reads as
            though access had been granted. */}
        {action === "revoke"
          ? t("workspaces.share.cascadeRevoked", "Removed from {{count}} resource", { count: updated.length })
          : action === "transfer"
            ? t("workspaces.transfer.cascade", "Owner changed on {{count}} resource", { count: updated.length })
            : action === "move"
              ? t("workspaces.move.cascade", "Moved {{count}} resource", { count: updated.length })
              : t("workspaces.share.cascadeApplied", "Applied to {{count}} resource", { count: updated.length })}
      </p>
      {skipped.length > 0 && (
        <div className="space-y-1">
          <p className="text-sm text-warning">
            {t("workspaces.share.cascadeSkipped", "{{count}} resource left unchanged — you do not own it", {
              count: skipped.length,
            })}
          </p>
          <ul className="list-inside list-disc text-xs text-muted-foreground">
            {skipped.slice(0, MAX_LISTED).map((target) => (
              <li key={target.id} className="truncate">
                <TargetLabel target={target} />
              </li>
            ))}
            {skipped.length > MAX_LISTED && (
              <li className="list-none italic">
                {t("workspaces.share.andMore", "and {{count}} more", { count: skipped.length - MAX_LISTED })}
              </li>
            )}
          </ul>
        </div>
      )}
    </div>
  );
}

/**
 * The message for a failed read of the sharing state. A 403 is the USE/VIEW
 * split doing its job, so it is explained rather than reported as a fault.
 */
function friendlyError(t: (k: string, d: string) => string, error: unknown): string {
  if (error instanceof ApiClientError && error.status === 403) {
    return t(
      "workspaces.share.useOnly",
      "You can chat with this, but its configuration has not been shared with you — so there is nothing here to manage."
    );
  }
  return getErrorMessage(error);
}

/** How many resources to name before summarising the rest. */
const MAX_LISTED = 5;

/**
 * A resource in a preview or summary. Most workflows and configuration
 * resources beneath an agent carry no name — the server sends `""`, not null —
 * so a `name ?? id` fallback rendered an empty bullet for each of them: "this
 * change reaches 3 resources" followed by one name and two blanks.
 */
function TargetLabel({ target }: { target: ShareTarget }) {
  const name = target.name?.trim();
  return name ? <>{name}</> : <span className="font-mono">{target.id}</span>;
}

/**
 * The level as a human reads it. Falls through to the raw value for a level
 * this UI does not know yet, rather than rendering an empty badge.
 */
function levelLabel(t: (k: string, d: string) => string, level: AccessLevel): string {
  switch (level) {
    case "USE":
      return t("workspaces.level.use", "Can chat");
    case "VIEW":
      return t("workspaces.level.view", "Can view");
    case "EDIT":
      return t("workspaces.level.edit", "Can edit");
    case "OWN":
      return t("workspaces.level.own", "Owner");
    default:
      return String(level);
  }
}

function levelHint(t: (k: string, d: string) => string, level: AccessLevel): string {
  switch (level) {
    case "USE":
      return t(
        "workspaces.level.useHint",
        "They can talk to the agent, but cannot see how it is built — no prompts, tools or credentials."
      );
    case "VIEW":
      return t("workspaces.level.viewHint", "They can read the configuration and export a copy, but not change it.");
    case "EDIT":
      return t("workspaces.level.editHint", "They can change and deploy it, but not delete it or share it further.");
    case "OWN":
      return t("workspaces.level.ownHint", "Full control, including deleting it and sharing it with others.");
    default:
      return "";
  }
}
