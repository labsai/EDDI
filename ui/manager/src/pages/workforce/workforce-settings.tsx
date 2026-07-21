import { useState, useEffect, useCallback, useMemo } from "react";
import { useParams, useSearchParams, useNavigate, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  ArrowLeft,
  Plus,
  X,
  Settings,
  Trash2,
  Bookmark,
} from "lucide-react";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import {
  useGroup,
  useUpdateGroup,
  useDeleteGroup,
  useDeleteGroupWithMembers,
} from "@/hooks/use-groups";
import {
  STYLE_INFO,
  DISCUSSION_STYLES,
  type DiscussionStyle,
  type GroupMember,
} from "@/lib/api/groups";
import { AdvisorAvatar } from "@/components/workforce/advisor-avatar";
import { AgentPicker } from "@/components/shared/agent-picker";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { useTemplates } from "@/hooks/use-templates";

// ─── Section Header ──────────────────────────────────────────────

function SectionHeader({
  icon,
  title,
  description,
  id,
}: {
  icon: React.ReactNode;
  title: string;
  description: string;
  id?: string;
}) {
  return (
    <div className="mb-5">
      <div className="flex items-center gap-2.5 mb-1">
        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10 text-primary">
          {icon}
        </div>
        <h2 id={id} className="text-base font-semibold text-foreground">{title}</h2>
      </div>
      <p className="text-sm text-muted-foreground ps-[42px]">{description}</p>
    </div>
  );
}

// ─── Form Field ──────────────────────────────────────────────────

function FormField({
  label,
  htmlFor,
  children,
}: {
  label: string;
  htmlFor: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <label
        htmlFor={htmlFor}
        className="block text-sm font-medium text-foreground"
      >
        {label}
      </label>
      {children}
    </div>
  );
}

// ─── Add Member Form ─────────────────────────────────────────────

function AddMemberForm({
  onAdd,
  onCancel,
}: {
  onAdd: (member: GroupMember) => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const [agentId, setAgentId] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [role, setRole] = useState("");

  const handleSubmit = useCallback(() => {
    if (!agentId.trim()) return;
    onAdd({
      agentId: agentId.trim(),
      displayName: displayName.trim() || agentId.trim(),
      role: role.trim() || null,
      speakingOrder: null,
      memberType: "AGENT",
    });
    setAgentId("");
    setDisplayName("");
    setRole("");
  }, [agentId, displayName, role, onAdd]);

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        handleSubmit();
      }}
      className="rounded-xl border border-primary/20 bg-primary/5 p-4 space-y-3 animate-in fade-in-0 slide-in-from-top-2 duration-200"
    >
      <p className="text-sm font-medium text-foreground">
        {t("Workforce.settings.addMemberTitle", "Add Team Member")}
      </p>

      <div className="space-y-1.5">
        <label htmlFor="add-member-agent" className="block text-xs font-medium text-muted-foreground">
          {t("Workforce.settings.selectAgent", "Agent")}
        </label>
        <AgentPicker
          value={agentId}
          onChange={setAgentId}
          placeholder={t(
            "Workforce.settings.pickAgent",
            "Search or select an agent…"
          )}
        />
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1.5">
          <label htmlFor="add-member-name" className="block text-xs font-medium text-muted-foreground">
            {t("Workforce.settings.displayName", "Display Name")}
          </label>
          <input
            id="add-member-name"
            type="text"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder={t("Workforce.settings.displayNameHint", "e.g. Analyst")}
            className="h-9 w-full rounded-lg border border-input bg-background ps-3 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
          />
        </div>
        <div className="space-y-1.5">
          <label htmlFor="add-member-role" className="block text-xs font-medium text-muted-foreground">
            {t("Workforce.settings.role", "Role")}
          </label>
          <input
            id="add-member-role"
            type="text"
            value={role}
            onChange={(e) => setRole(e.target.value)}
            placeholder={t("Workforce.settings.roleHint", "e.g. Reviewer")}
            className="h-9 w-full rounded-lg border border-input bg-background ps-3 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
          />
        </div>
      </div>

      <div className="flex justify-end gap-2 pt-1">
        <Button variant="ghost" size="sm" type="button" onClick={onCancel}>
          {t("common.cancel", "Cancel")}
        </Button>
        <Button
          variant="primary"
          size="sm"
          type="submit"
          disabled={!agentId.trim()}
        >
          <Plus className="h-3.5 w-3.5" />
          {t("Workforce.settings.addMember", "Add Member")}
        </Button>
      </div>
    </form>
  );
}

// ─── Main Component ──────────────────────────────────────────────

function WorkforceSettings() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { boardId } = useParams<{ boardId: string }>();
  const [searchParams] = useSearchParams();
  const version = Number(searchParams.get("version")) || 1;

  // ─── Data hooks ──────────────────────────────────────────────────
  const { data: config, isLoading, isError } = useGroup(boardId ?? "", version);
  const { mutateAsync: updateGroupAsync, isPending: isUpdatePending } = useUpdateGroup();
  const deleteMutation = useDeleteGroup();
  const deleteWithMembersMutation = useDeleteGroupWithMembers();
  const { saveTemplate } = useTemplates();

  // ─── Form state ──────────────────────────────────────────────────
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [style, setStyle] = useState<DiscussionStyle>("ROUND_TABLE");
  const [maxRounds, setMaxRounds] = useState(3);
  const [moderatorAgentId, setModeratorAgentId] = useState<string | null>(null);
  const [members, setMembers] = useState<GroupMember[]>([]);

  // ─── UI state ────────────────────────────────────────────────────
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [deleteMode, setDeleteMode] = useState<"group" | "all">("group");
  const [showAddMember, setShowAddMember] = useState(false);

  // ─── Initialize form from config ────────────────────────────────
  useEffect(() => {
    if (!config) return;
    setName(config.name ?? "");
    setDescription(config.description ?? "");
    setStyle(config.style ?? "ROUND_TABLE");
    setMaxRounds(config.maxRounds ?? 3);
    setModeratorAgentId(config.moderatorAgentId ?? null);
    setMembers(config.members ?? []);
  }, [config]);

  // ─── Dirty tracking ─────────────────────────────────────────────
  const isDirty = useMemo(() => {
    if (!config) return false;
    if (name !== (config.name ?? "")) return true;
    if (description !== (config.description ?? "")) return true;
    if (style !== (config.style ?? "ROUND_TABLE")) return true;
    if (maxRounds !== (config.maxRounds ?? 3)) return true;
    if (moderatorAgentId !== (config.moderatorAgentId ?? null)) return true;
    if (JSON.stringify(members) !== JSON.stringify(config.members ?? []))
      return true;
    return false;
  }, [config, name, description, style, maxRounds, moderatorAgentId, members]);

  // ─── Beforeunload guard ─────────────────────────────────────────
  useEffect(() => {
    if (!isDirty) return;
    const h = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener('beforeunload', h);
    return () => window.removeEventListener('beforeunload', h);
  }, [isDirty]);

  // ─── Save handler ───────────────────────────────────────────────
  const handleSave = useCallback(async () => {
    if (!boardId || !config) return;
    if (!name.trim()) {
      toast.error(t("Workforce.settings.nameRequired", "Task force name is required"));
      return;
    }
    const updatedConfig = {
      ...config,
      name,
      description,
      style,
      maxRounds,
      moderatorAgentId,
      members,
    };
    try {
      await updateGroupAsync(
        { id: boardId, version, config: updatedConfig },
      );
      toast.success(
        t("Workforce.settings.saveSuccess", "Task force settings saved")
      );
    } catch {
      toast.error(
        t("Workforce.settings.saveError", "Failed to save settings")
      );
    }
  }, [
    boardId,
    config,
    name,
    description,
    style,
    maxRounds,
    moderatorAgentId,
    members,
    version,
    updateGroupAsync,
    t,
  ]);

  // ─── Delete handler ─────────────────────────────────────────────
  const handleDelete = useCallback(() => {
    if (!boardId || !config) return;

    if (deleteMode === "all") {
      deleteWithMembersMutation.mutate(
        { groupId: boardId, version, config },
        {
          onSuccess: () => {
            toast.success(
              t(
                "Workforce.settings.deleteAllSuccess",
                "Task force and all agents deleted"
              )
            );
            navigate("/workforce");
          },
          onError: () => {
            toast.error(
              t("Workforce.settings.deleteError", "Failed to delete board")
            );
          },
        }
      );
    } else {
      deleteMutation.mutate(
        { id: boardId, version, permanent: true },
        {
          onSuccess: () => {
            toast.success(
              t("Workforce.settings.deleteSuccess", "Task force deleted")
            );
            navigate("/workforce");
          },
          onError: () => {
            toast.error(
              t("Workforce.settings.deleteError", "Failed to delete board")
            );
          },
        }
      );
    }
  }, [
    boardId,
    config,
    deleteMode,
    version,
    deleteMutation,
    deleteWithMembersMutation,
    navigate,
    t,
  ]);

  // ─── Member helpers ─────────────────────────────────────────────
  const updateMember = useCallback(
    (index: number, patch: Partial<GroupMember>) => {
      setMembers((prev) =>
        prev.map((m, i) => (i === index ? { ...m, ...patch } : m))
      );
    },
    []
  );

  const removeMember = useCallback((index: number) => {
    setMembers((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const addMember = useCallback(
    (member: GroupMember) => {
      if (members.some((m) => m.agentId === member.agentId)) {
        toast.warning(
          t("Workforce.settings.duplicateMember", "This agent is already a member")
        );
        return;
      }
      setMembers((prev) => [...prev, member]);
      setShowAddMember(false);
    },
    [members, t]
  );

  // ─── Loading state ──────────────────────────────────────────────
  if (isLoading || !boardId) {
    return (
      <div className="max-w-3xl ms-auto me-auto p-5 sm:p-8 space-y-6">
        <Skeleton className="h-6 w-32" />
        <Skeleton className="h-10 w-2/3" />
        <Skeleton className="h-4 w-1/2" />
        <div className="space-y-4 pt-4">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-1/3" />
        </div>
      </div>
    );
  }

  // ─── Error state ───────────────────────────────────────────────
  if (isError || (!isLoading && !config)) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-4 p-8">
        <p>{t("Workforce.settings.loadError", "Failed to load settings")}</p>
        <Button onClick={() => navigate('/workforce')}>
          {t('Workforce.back', 'Back')}
        </Button>
      </div>
    );
  }

  const isDeleting =
    deleteMutation.isPending || deleteWithMembersMutation.isPending;

  return (
    <div className="flex-1 overflow-auto max-w-3xl ms-auto me-auto p-5 sm:p-8 pb-24">
      {/* ── Back link ───────────────────────────────────────────── */}
      <Link
        to={`/workforce/${boardId}?version=${version}`}
        className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors mb-6 group"
      >
        <ArrowLeft className="h-4 w-4" />
        {t("Workforce.settings.backToBoard", "Back to Task Force")}
      </Link>

      {/* ── Page header ─────────────────────────────────────────── */}
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-foreground tracking-tight">
          {name || t("Workforce.settings.untitled", "Untitled Task Force")}
        </h1>
        <p className="text-sm text-muted-foreground mt-1">
          {t(
            "Workforce.settings.subtitle",
            "Configure your task force settings and workforce"
          )}
        </p>
      </div>

      {/* ═══════════════════════════════════════════════════════════
          SECTION 1: General Settings
          ═══════════════════════════════════════════════════════════ */}
      <section className="mb-10" aria-labelledby="section-general">
        <SectionHeader
          icon={<Settings className="h-4 w-4" />}
          title={t("Workforce.settings.general", "General Settings")}
          id="section-general"
          description={t(
            "Workforce.settings.generalDesc",
            "Basic configuration for your task force"
          )}
        />

        <div className="space-y-5 rounded-xl border border-border bg-card p-5 shadow-sm">
          {/* Board Name */}
          <FormField
            label={t("Workforce.settings.boardName", "Task Force Name")}
            htmlFor="settings-name"
          >
            <input
              id="settings-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t(
                "Workforce.settings.boardNameHint",
                "Enter task force name…"
              )}
              className="h-10 w-full rounded-lg border border-input bg-background ps-3 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            />
          </FormField>

          {/* Description */}
          <FormField
            label={t("Workforce.settings.description", "Description")}
            htmlFor="settings-description"
          >
            <textarea
              id="settings-description"
              rows={3}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={t(
                "Workforce.settings.descriptionHint",
                "What is this task force about?"
              )}
              className="w-full rounded-lg border border-input bg-background ps-3 pe-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow resize-none"
            />
          </FormField>

          {/* Discussion Style */}
          <FormField
            label={t("Workforce.settings.discussionStyle", "Collaboration Framework")}
            htmlFor="settings-style"
          >
            <select
              id="settings-style"
              value={style}
              onChange={(e) => setStyle(e.target.value as DiscussionStyle)}
              className="h-10 w-full rounded-lg border border-input bg-background ps-3 pe-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow appearance-none cursor-pointer"
            >
              {DISCUSSION_STYLES.map((s) => (
                <option key={s} value={s}>
                  {STYLE_INFO[s].icon} {STYLE_INFO[s].label}
                </option>
              ))}
            </select>
            <p className="text-xs text-muted-foreground mt-1">
              {STYLE_INFO[style].flow}
            </p>
          </FormField>

          {/* Max Rounds + Moderator row */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
            <FormField
              label={t("Workforce.settings.maxRounds", "Max Rounds")}
              htmlFor="settings-max-rounds"
            >
              <input
                id="settings-max-rounds"
                type="number"
                min={1}
                max={20}
                step={1}
                value={maxRounds}
                onChange={(e) => {
                  const val = Number(e.target.value);
                  if (isNaN(val)) return;
                  setMaxRounds(
                    Math.max(1, Math.min(20, val))
                  );
                }}
                className="h-10 w-full rounded-lg border border-input bg-background ps-3 pe-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
              />
            </FormField>

            <FormField
              label={t("Workforce.settings.moderator", "Moderator")}
              htmlFor="settings-moderator"
            >
              <select
                id="settings-moderator"
                value={moderatorAgentId ?? ""}
                onChange={(e) =>
                  setModeratorAgentId(e.target.value || null)
                }
                className="h-10 w-full rounded-lg border border-input bg-background ps-3 pe-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow appearance-none cursor-pointer"
              >
                <option value="">
                  {t("Workforce.settings.noModerator", "None")}
                </option>
                {members.map((m) => (
                  <option key={m.agentId} value={m.agentId}>
                    {m.displayName || m.agentId}
                  </option>
                ))}
              </select>
            </FormField>
          </div>
        </div>
      </section>

      {/* ═══════════════════════════════════════════════════════════
          SECTION 2: Team Management
          ═══════════════════════════════════════════════════════════ */}
      <section className="mb-10" aria-labelledby="section-team">
        <SectionHeader
          icon={
            <svg
              xmlns="http://www.w3.org/2000/svg"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4"
            >
              <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
              <circle cx="9" cy="7" r="4" />
              <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
              <path d="M16 3.13a4 4 0 0 1 0 7.75" />
            </svg>
          }
          title={t("Workforce.settings.team", "Workforce")}
          id="section-team"
          description={t(
            "Workforce.settings.teamDesc",
            "Manage the agents participating in your task force"
          )}
        />

        <div className="space-y-3">
          {members.map((member, index) => (
            <div
              key={`${member.agentId}-${index}`}
              className={cn(
                "group relative flex items-start gap-4 rounded-xl border border-border bg-card p-4 shadow-sm",
                "transition-all duration-200 hover:shadow-md hover:border-border/80"
              )}
            >
              {/* Avatar */}
              <div className="shrink-0 pt-0.5">
                <AdvisorAvatar
                  name={member.displayName}
                  agentId={member.agentId}
                  size="sm"
                />
              </div>

              {/* Editable fields */}
              <div className="flex-1 min-w-0 space-y-2">
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  <div className="space-y-1">
                    <label htmlFor={`member-name-${index}`} className="block text-[11px] font-medium text-muted-foreground uppercase tracking-wide">
                      {t("Workforce.settings.displayName", "Display Name")}
                    </label>
                    <input
                      id={`member-name-${index}`}
                      type="text"
                      value={member.displayName}
                      onChange={(e) =>
                        updateMember(index, { displayName: e.target.value })
                      }
                      className="h-8 w-full rounded-md border border-input bg-background ps-2.5 pe-2.5 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-ring transition-shadow"
                    />
                  </div>
                  <div className="space-y-1">
                    <label htmlFor={`member-role-${index}`} className="block text-[11px] font-medium text-muted-foreground uppercase tracking-wide">
                      {t("Workforce.settings.role", "Role")}
                    </label>
                    <input
                      id={`member-role-${index}`}
                      type="text"
                      value={member.role ?? ""}
                      onChange={(e) =>
                        updateMember(index, {
                          role: e.target.value || null,
                        })
                      }
                      placeholder={t(
                        "Workforce.settings.roleOptional",
                        "Optional"
                      )}
                      className="h-8 w-full rounded-md border border-input bg-background ps-2.5 pe-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring transition-shadow"
                    />
                  </div>
                </div>

                {/* Bottom row: badge + link */}
                <div className="flex items-center gap-2 flex-wrap">
                  <Badge
                    variant={
                      member.memberType === "AGENT" ? "secondary" : "default"
                    }
                    className="text-[10px]"
                  >
                    {member.memberType === "AGENT"
                      ? t("Workforce.settings.typeAgent", "Agent")
                      : t("Workforce.settings.typeModerator", "Moderator")}
                  </Badge>
                </div>
              </div>

              {/* Remove button */}
              <button
                type="button"
                onClick={() => removeMember(index)}
                disabled={members.length <= 2}
                className={cn(
                  "shrink-0 rounded-md p-1.5 transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                  members.length <= 2
                    ? "text-muted-foreground/30 cursor-not-allowed"
                    : "text-muted-foreground opacity-0 group-hover:opacity-100 focus-visible:opacity-100 hover:bg-destructive/10 hover:text-destructive"
                )}
                aria-label={t(
                  "Workforce.settings.removeMember",
                  "Remove member"
                )}
                title={
                  members.length <= 2
                    ? t(
                        "Workforce.settings.minMembers",
                        "Minimum 2 members required"
                      )
                    : t("Workforce.settings.removeMember", "Remove member")
                }
              >
                <X className="h-4 w-4" />
              </button>
            </div>
          ))}

          {/* Add member */}
          {showAddMember ? (
            <AddMemberForm
              onAdd={addMember}
              onCancel={() => setShowAddMember(false)}
            />
          ) : (
            <button
              type="button"
              onClick={() => setShowAddMember(true)}
              className="flex w-full items-center justify-center gap-2 rounded-xl border-2 border-dashed border-border py-3 text-sm font-medium text-muted-foreground transition-all hover:border-primary/40 hover:text-primary hover:bg-primary/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <Plus className="h-4 w-4" />
              {t("Workforce.settings.addMember", "Add Member")}
            </button>
          )}
        </div>
      </section>

      {/* ═══════════════════════════════════════════════════════════
          SECTION 3: Danger Zone
          ═══════════════════════════════════════════════════════════ */}
      <section className="mb-10" aria-labelledby="section-danger">
        <SectionHeader
          icon={<Trash2 className="h-4 w-4" />}
          title={t("Workforce.settings.dangerZone", "Danger Zone")}
          id="section-danger"
          description={t(
            "Workforce.settings.dangerDesc",
            "Irreversible actions — proceed with caution"
          )}
        />

        <div className="rounded-xl border border-destructive/30 bg-destructive/5 p-5 space-y-4">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div>
              <p className="text-sm font-medium text-foreground">
                {t("Workforce.settings.deleteBoard", "Dissolve Task Force")}
              </p>
              <p className="text-xs text-muted-foreground mt-0.5">
                {t(
                  "Workforce.settings.deleteBoardDesc",
                  "Remove this task force configuration. Agents will remain."
                )}
              </p>
            </div>
            <Button
              variant="destructive"
              size="sm"
              onClick={() => {
                setDeleteMode("group");
                setShowDeleteDialog(true);
              }}
              className="shrink-0"
            >
              <Trash2 className="h-3.5 w-3.5" />
              {t("Workforce.settings.deleteBoard", "Dissolve Task Force")}
            </Button>
          </div>

          <hr className="border-destructive/20" />

          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div>
              <p className="text-sm font-medium text-foreground">
                {t(
                  "Workforce.settings.deleteBoardAll",
                  "Dissolve Task Force + All Agents"
                )}
              </p>
              <p className="text-xs text-muted-foreground mt-0.5">
                {t(
                  "Workforce.settings.deleteBoardAllDesc",
                  "Remove this task force and all member agents permanently."
                )}
              </p>
            </div>
            <Button
              variant="destructive"
              size="sm"
              onClick={() => {
                setDeleteMode("all");
                setShowDeleteDialog(true);
              }}
              className="shrink-0"
            >
              <Trash2 className="h-3.5 w-3.5" />
              {t(
                "Workforce.settings.deleteBoardAll",
                "Dissolve Task Force + All Agents"
              )}
            </Button>
          </div>
        </div>
      </section>

      {/* ═══════════════════════════════════════════════════════════
          Save Button — sticky bottom
          ═══════════════════════════════════════════════════════════ */}
      <div className="fixed inset-x-0 bottom-0 z-40 border-t border-border bg-background/80 backdrop-blur-lg">
        <div className="max-w-3xl ms-auto me-auto flex items-center justify-between ps-5 pe-5 sm:ps-8 sm:pe-8 py-3">
          <p
            className={cn(
              "text-xs transition-opacity duration-200",
              isDirty
                ? "text-amber-500 opacity-100"
                : "text-muted-foreground opacity-0"
            )}
          >
            {t("Workforce.settings.unsavedChanges", "You have unsaved changes")}
          </p>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              saveTemplate({
                name,
                description,
                style,
                members: members.map((m) => ({
                  displayName: m.displayName,
                  role: m.role ?? "",
                  agentId: m.agentId,
                })),
                maxRounds,
              });
              toast.success(t("Workforce.settings.templateSaved", "Saved as template"));
            }}
          >
            <Bookmark className="h-4 w-4" />
            {t("Workforce.settings.saveAsTemplate", "Save as Template")}
          </Button>
          <Button
            variant="primary"
            size="md"
            onClick={handleSave}
            disabled={!isDirty || isUpdatePending}
          >
            {isUpdatePending
              ? t("Workforce.settings.saving", "Saving…")
              : t("Workforce.settings.saveChanges", "Save Changes")}
          </Button>
        </div>
      </div>

      {/* ═══════════════════════════════════════════════════════════
          Delete Confirmation Dialog
          ═══════════════════════════════════════════════════════════ */}
      <AlertDialog
        open={showDeleteDialog}
        onOpenChange={setShowDeleteDialog}
        title={
          deleteMode === "all"
            ? t(
                "Workforce.settings.deleteAllTitle",
                "Dissolve Task Force & All Agents?"
              )
            : t("Workforce.settings.deleteTitle", "Dissolve Task Force?")
        }
        description={
          deleteMode === "all"
            ? t(
                "Workforce.settings.deleteAllConfirm",
                "This will permanently dissolve this task force and remove all its member agents. This action cannot be undone."
              )
            : t(
                "Workforce.settings.deleteConfirm",
                "This will permanently dissolve this task force configuration. Member agents will not be affected."
              )
        }
        confirmLabel={t("common.delete", "Delete")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={handleDelete}
        variant="destructive"
        isPending={isDeleting}
      />
    </div>
  );
}

export { WorkforceSettings };
