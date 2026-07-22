import { useMemo } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  Bot,
  UsersRound,
  MessageSquare,
  ArrowRight,
  Sparkles,
  Plus,
  Rocket,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { useAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import { getGroupTemplates, type GroupTemplate } from "@/lib/group-templates";
import { STYLE_INFO } from "@/lib/api/groups";

// ─── How It Works Step ───────────────────────────────────────────

function HowItWorksStep({
  step,
  icon,
  title,
  description,
  delay,
  highlight,
  action,
}: {
  step: number;
  icon: React.ReactNode;
  title: string;
  description: string;
  delay: string;
  highlight?: boolean;
  action?: React.ReactNode;
}) {
  return (
    <div
      className={cn(
        "relative flex flex-col items-center text-center p-6 rounded-2xl border transition-all duration-300",
        "br-section-enter",
        highlight
          ? "border-primary/40 bg-primary/5 shadow-[0_0_20px_-6px] shadow-primary/20"
          : "border-border bg-card hover:border-primary/20",
      )}
      style={{ "--enter-delay": delay } as React.CSSProperties}
    >
      {/* Step badge */}
      <div className="absolute -top-3 start-1/2 -translate-x-1/2">
        <span
          className={cn(
            "inline-flex h-6 w-6 items-center justify-center rounded-full text-xs font-bold",
            highlight
              ? "bg-primary text-primary-foreground"
              : "bg-muted text-muted-foreground",
          )}
        >
          {step}
        </span>
      </div>

      <div
        className={cn(
          "flex h-12 w-12 items-center justify-center rounded-xl mb-4 mt-2",
          highlight
            ? "bg-primary/10 text-primary"
            : "bg-muted text-muted-foreground",
        )}
      >
        {icon}
      </div>
      <h3 className="text-sm font-semibold text-foreground mb-1">{title}</h3>
      <p className="text-xs text-muted-foreground leading-relaxed">
        {description}
      </p>
      {action && <div className="mt-3">{action}</div>}
    </div>
  );
}

// ─── Template Card ───────────────────────────────────────────────

function TemplateCard({
  template,
  index,
  hasAgents,
}: {
  template: GroupTemplate;
  index: number;
  hasAgents: boolean;
}) {
  const { t } = useTranslation();
  const styleInfo = STYLE_INFO[template.style];

  return (
    <Link
      to={`/workforce/new?template=${template.key}`}
      className={cn(
        "group relative flex flex-col rounded-2xl border border-border bg-card overflow-hidden",
        "transition-all duration-200 hover:shadow-lg hover:-translate-y-1 hover:border-primary/30",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
        "br-card-enter",
        !hasAgents && "pointer-events-none opacity-50",
      )}
      style={{ "--enter-delay": `${index * 50}ms` } as React.CSSProperties}
      aria-disabled={!hasAgents}
      tabIndex={hasAgents ? 0 : -1}
      onClick={hasAgents ? undefined : (e) => e.preventDefault()}
    >
      {/* Top accent bar */}
      <div className="h-1 bg-gradient-to-r from-primary/60 via-primary to-primary/60" />

      <div className="p-5 flex flex-col flex-1">
        {/* Icon + badges row */}
        <div className="flex items-start justify-between mb-3">
          <span className="text-3xl" aria-hidden="true">
            {template.icon}
          </span>
          <Badge
            variant="secondary"
            className="text-[10px] shrink-0"
          >
            {styleInfo?.label ?? template.style}
          </Badge>
        </div>

        {/* Name */}
        <h3 className="text-base font-semibold text-foreground mb-1 group-hover:text-primary transition-colors">
          {template.name}
        </h3>

        {/* Description */}
        <p className="text-xs text-muted-foreground leading-relaxed flex-1 line-clamp-2 mb-4">
          {template.description}
        </p>

        {/* Footer */}
        <div className="flex items-center justify-between pt-3 border-t border-border/50">
          <span className="text-xs text-muted-foreground">
            {t("Workforce.onboarding.advisorSlots", "{{count}} advisor slots", {
              count: template.roles.length,
            })}
          </span>
          <span className="text-xs font-medium text-primary opacity-0 group-hover:opacity-100 transition-opacity flex items-center gap-1">
            {t("Workforce.onboarding.getStarted", "Get started")}
            <ArrowRight className="h-3 w-3" />
          </span>
        </div>
      </div>
    </Link>
  );
}

// ─── Custom Card ─────────────────────────────────────────────────

function CustomCard({ hasAgents }: { hasAgents: boolean }) {
  const { t } = useTranslation();

  return (
    <Link
      to="/workforce/new?template=custom"
      className={cn(
        "group flex flex-col items-center justify-center rounded-2xl border-2 border-dashed p-8",
        "text-muted-foreground transition-all duration-200",
        "hover:border-primary/50 hover:text-primary hover:bg-primary/5",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
        "br-card-enter min-h-[200px]",
        !hasAgents && "pointer-events-none opacity-50",
      )}
      style={{ "--enter-delay": "350ms" } as React.CSSProperties}
      aria-disabled={!hasAgents}
      tabIndex={hasAgents ? 0 : -1}
      onClick={hasAgents ? undefined : (e) => e.preventDefault()}
    >
      <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-muted group-hover:bg-primary/10 transition-colors mb-3">
        <Plus className="h-6 w-6" />
      </div>
      <span className="text-sm font-medium">
        {t("Workforce.onboarding.customFromScratch", "Build from Scratch")}
      </span>
      <span className="text-xs text-muted-foreground mt-1">
        {t(
          "Workforce.onboarding.customDesc",
          "Define your own team and rules",
        )}
      </span>
    </Link>
  );
}

// ─── Agent Deploy Banner ─────────────────────────────────────────

function DeployAgentBanner() {
  const { t } = useTranslation();

  return (
    <div
      className={cn(
        "flex flex-col sm:flex-row items-center gap-4 rounded-2xl border border-primary/30 bg-primary/5 p-5",
        "br-section-enter",
      )}
      style={{ "--enter-delay": "200ms" } as React.CSSProperties}
      role="alert"
    >
      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary">
        <Rocket className="h-5 w-5" />
      </div>
      <div className="flex-1 text-center sm:text-start">
        <p className="text-sm font-semibold text-foreground">
          {t(
            "Workforce.onboarding.deployFirst",
            "Deploy your first AI agent to get started",
          )}
        </p>
        <p className="text-xs text-muted-foreground mt-0.5">
          {t(
            "Workforce.onboarding.deployFirstDesc",
            "Agents are the experts that power your task forces. Create one to begin.",
          )}
        </p>
      </div>
      <Button asChild variant="primary" size="sm" className="shrink-0">
        <Link to="/workforce/new">
          <Bot className="h-4 w-4" />
          {t("Workforce.onboarding.deployAgent", "Deploy Agent")}
        </Link>
      </Button>
    </div>
  );
}

// ─── Main Onboarding Component ───────────────────────────────────

function OnboardingHero() {
  const { t } = useTranslation();
  const { data: agentsRaw, isLoading: agentsLoading } = useAgentDescriptors(50);

  const agents = useMemo(
    () => (agentsRaw ? groupAgentsByName(agentsRaw) : []),
    [agentsRaw],
  );
  // Treat loading as "has agents" to avoid flashing the deploy banner
  const hasAgents = agentsLoading || agents.length > 0;
  const templates = useMemo(() => getGroupTemplates(t), [t]);

  return (
    <div className="p-5 md:p-8 max-w-5xl ms-auto me-auto space-y-10">
      {/* ── Hero ────────────────────────────────────────────────── */}
      <section
        className="text-center space-y-4 pt-4 br-section-enter"
        style={{ "--enter-delay": "0ms" } as React.CSSProperties}
      >
        <div className="flex items-center justify-center">
          <div className="relative">
            <div className="h-16 w-16 rounded-2xl bg-gradient-to-br from-primary/20 to-primary/5 flex items-center justify-center">
              <img
                src="/eddi-icon.svg"
                alt=""
                aria-hidden="true"
                className="h-10 w-10 rounded-lg"
              />
            </div>
            <Sparkles className="absolute -top-1 -end-1 h-4 w-4 text-primary/60" />
          </div>
        </div>

        <h1 className="text-2xl md:text-3xl font-bold text-foreground tracking-tight">
          {t("Workforce.onboarding.welcome", "Welcome to the Workforce")}
        </h1>
        <p className="text-muted-foreground text-base md:text-lg max-w-xl ms-auto me-auto leading-relaxed">
          {t(
            "Workforce.onboarding.subtitle",
            "Assemble specialized AI agents into collaborative teams that debate, analyze, and solve complex challenges together.",
          )}
        </p>
      </section>

      {/* ── How It Works ────────────────────────────────────────── */}
      <section
        aria-label={t("Workforce.onboarding.howItWorks", "How it works")}
        className="space-y-4"
      >
        <h2
          className="text-xs font-semibold text-muted-foreground uppercase tracking-widest text-center br-section-enter"
          style={{ "--enter-delay": "100ms" } as React.CSSProperties}
        >
          {t("Workforce.onboarding.howItWorks", "How it works")}
        </h2>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          <HowItWorksStep
            step={1}
            icon={<Bot className="h-6 w-6" />}
            title={t("Workforce.onboarding.step1Title", "Deploy Agents")}
            description={t(
              "Workforce.onboarding.step1Desc",
              "Create AI experts with unique skills, knowledge, and personality.",
            )}
            delay="80ms"
            highlight={!hasAgents}
            action={
              !hasAgents ? (
                <Button
                  asChild
                  variant="primary"
                  size="sm"
                  className="text-xs"
                >
                  <Link to="/workforce/new">
                    {t(
                      "Workforce.onboarding.deployFirst2",
                      "Deploy your first →",
                    )}
                  </Link>
                </Button>
              ) : (
                <span className="inline-flex items-center gap-1.5 text-xs text-primary">
                  <span className="h-1.5 w-1.5 rounded-full bg-primary" />
                  {t("Workforce.onboarding.agentsReady", "{{count}} ready", {
                    count: agents.length,
                  })}
                </span>
              )
            }
          />
          <HowItWorksStep
            step={2}
            icon={<UsersRound className="h-6 w-6" />}
            title={t(
              "Workforce.onboarding.step2Title",
              "Assemble a Task Force",
            )}
            description={t(
              "Workforce.onboarding.step2Desc",
              "Pick a template, assign agents to roles, and choose a discussion style.",
            )}
            delay="120ms"
          />
          <HowItWorksStep
            step={3}
            icon={<MessageSquare className="h-6 w-6" />}
            title={t(
              "Workforce.onboarding.step3Title",
              "Ask & Watch",
            )}
            description={t(
              "Workforce.onboarding.step3Desc",
              "Pose a question and watch your agents debate, critique, and synthesize an answer.",
            )}
            delay="160ms"
          />
        </div>
      </section>

      {/* ── Deploy Banner (only when no agents) ──────────────── */}
      {!hasAgents && <DeployAgentBanner />}

      {/* ── Templates ───────────────────────────────────────────── */}
      <section
        aria-label={t(
          "Workforce.onboarding.pickTemplate",
          "Pick a template to get started",
        )}
        className="space-y-4"
      >
        <div className="text-center br-section-enter" style={{ "--enter-delay": "200ms" } as React.CSSProperties}>
          <h2 className="text-lg font-semibold text-foreground">
            {t("Workforce.onboarding.pickTemplate", "Pick a template to get started")}
          </h2>
          <p className="text-sm text-muted-foreground mt-1">
            {t(
              "Workforce.onboarding.pickTemplateDesc",
              "Each template configures a discussion style, roles, and rules — you just assign agents.",
            )}
          </p>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {templates.map((tpl, i) => (
            <TemplateCard
              key={tpl.key}
              template={tpl}
              index={i}
              hasAgents={hasAgents}
            />
          ))}
          <CustomCard hasAgents={hasAgents} />
        </div>
      </section>

      {/* ── Quick CTA (when agents exist) ───────────────────── */}
      {hasAgents && (
        <div
          className="flex justify-center br-section-enter"
          style={{ "--enter-delay": "350ms" } as React.CSSProperties}
        >
          <Button
            asChild
            variant="primary"
            size="lg"
            className="gap-2"
          >
            <Link to="/workforce/new">
              <UsersRound className="h-5 w-5" />
              {t(
                "Workforce.onboarding.assembleNow",
                "Assemble Your First Task Force",
              )}
            </Link>
          </Button>
        </div>
      )}
    </div>
  );
}

export { OnboardingHero };
