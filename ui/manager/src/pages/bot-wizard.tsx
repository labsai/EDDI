import { useState, useRef } from "react";
import { useTranslation } from "react-i18next";
import {
  Wand2,
  ArrowLeft,
  ArrowRight,
  Check,
  LayoutTemplate,
  FileText,
  Package,
  Rocket,
  Bot,
  MessageSquare,
  CloudSun,
  RefreshCw,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useCreateBot, useDeployBot } from "@/hooks/use-bots";
import { useNavigate, Link } from "react-router-dom";

// Wizard step definitions
const STEPS = [
  { id: "template", icon: LayoutTemplate },
  { id: "info", icon: FileText },
  { id: "packages", icon: Package },
  { id: "review", icon: Rocket },
] as const;

interface BotTemplate {
  id: string;
  icon: typeof Bot;
  gradient: string;
}

const TEMPLATES: BotTemplate[] = [
  {
    id: "blank",
    icon: Bot,
    gradient: "from-slate-500 to-slate-700",
  },
  {
    id: "qa",
    icon: MessageSquare,
    gradient: "from-blue-500 to-indigo-600",
  },
  {
    id: "weather",
    icon: CloudSun,
    gradient: "from-amber-400 to-orange-500",
  },
];

interface WizardState {
  template: string;
  name: string;
  description: string;
  createPackage: boolean;
  autoDeploy: boolean;
}

export function BotWizardPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [currentStep, setCurrentStep] = useState(0);
  const [state, setState] = useState<WizardState>({
    template: "",
    name: "",
    description: "",
    createPackage: true,
    autoDeploy: false,
  });
  const [isCreating, setIsCreating] = useState(false);

  const createBot = useCreateBot();
  const deployBot = useDeployBot();

  const step = STEPS[currentStep]!;

  function canProceed(): boolean {
    switch (step.id) {
      case "template":
        return state.template !== "";
      case "info":
        return state.name.trim().length > 0;
      case "packages":
        return true;
      case "review":
        return true;
      default:
        return false;
    }
  }

  function handleNext() {
    if (currentStep < STEPS.length - 1) {
      setCurrentStep(currentStep + 1);
    }
  }

  function handleBack() {
    if (currentStep > 0) {
      setCurrentStep(currentStep - 1);
    }
  }

  async function handleCreate(deploy: boolean) {
    setIsCreating(true);
    try {
      const result = await createBot.mutateAsync({
        packages: [],
        channels: [],
      });

      if (deploy && result.location) {
        const parts = result.location.split("/");
        const idPart = parts[parts.length - 1];
        const botId = idPart?.split("?")[0];
        if (botId) {
          deployBot.mutate({ botId, version: 1 });
        }
      }

      // Navigate to bot detail
      if (result.location) {
        const parts = result.location.split("/");
        const idPart = parts[parts.length - 1];
        const botId = idPart?.split("?")[0];
        if (botId) {
          navigate(`/manage/botview/${botId}`);
          return;
        }
      }
      navigate("/manage/bots");
    } catch {
      setIsCreating(false);
    }
  }

  return (
    <div className="mx-auto max-w-3xl space-y-8">
      {/* Header */}
      <div className="space-y-2">
        <Link
          to="/manage/bots"
          className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
          {t("wizard.backToBots", "Back to Bots")}
        </Link>
        <h1 className="flex items-center gap-3 text-3xl font-bold text-foreground">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-linear-to-br from-primary to-primary/60 text-primary-foreground shadow-lg">
            <Wand2 className="h-5 w-5" />
          </div>
          {t("wizard.title", "Bot Wizard")}
        </h1>
        <p className="text-muted-foreground">
          {t("wizard.subtitle", "Create a new bot step by step")}
        </p>
      </div>

      {/* Step progress */}
      <div className="flex items-center gap-2" data-testid="wizard-steps">
        {STEPS.map((s, i) => {
          const Icon = s.icon;
          const isActive = i === currentStep;
          const isComplete = i < currentStep;
          return (
            <div key={s.id} className="flex flex-1 items-center gap-2">
              <button
                onClick={() => i < currentStep && setCurrentStep(i)}
                disabled={i > currentStep}
                className={cn(
                  "flex h-10 w-10 shrink-0 items-center justify-center rounded-full border-2 transition-all duration-300",
                  isActive &&
                    "border-primary bg-primary text-primary-foreground shadow-md shadow-primary/25 scale-110",
                  isComplete &&
                    "border-emerald-500 bg-emerald-500 text-white",
                  !isActive &&
                    !isComplete &&
                    "border-border bg-card text-muted-foreground"
                )}
              >
                {isComplete ? (
                  <Check className="h-4 w-4" />
                ) : (
                  <Icon className="h-4 w-4" />
                )}
              </button>
              {i < STEPS.length - 1 && (
                <div
                  className={cn(
                    "h-0.5 flex-1 rounded-full transition-colors duration-300",
                    isComplete ? "bg-emerald-500" : "bg-border"
                  )}
                />
              )}
            </div>
          );
        })}
      </div>

      {/* Step content */}
      <div className="rounded-xl border bg-card p-6 shadow-sm">
        {step.id === "template" && (
          <TemplateStep
            selected={state.template}
            onSelect={(id) => setState({ ...state, template: id })}
          />
        )}
        {step.id === "info" && (
          <InfoStep
            name={state.name}
            description={state.description}
            onNameChange={(name) => setState({ ...state, name })}
            onDescriptionChange={(description) =>
              setState({ ...state, description })
            }
          />
        )}
        {step.id === "packages" && (
          <PackagesStep
            createPackage={state.createPackage}
            onToggle={(v) => setState({ ...state, createPackage: v })}
          />
        )}
        {step.id === "review" && (
          <ReviewStep state={state} />
        )}
      </div>

      {/* Navigation */}
      <div className="flex items-center justify-between">
        <button
          onClick={handleBack}
          disabled={currentStep === 0}
          className={cn(
            "inline-flex items-center gap-2 rounded-lg px-4 py-2.5 text-sm font-medium transition-colors",
            currentStep === 0
              ? "invisible"
              : "text-muted-foreground hover:bg-secondary hover:text-foreground"
          )}
          data-testid="wizard-back"
        >
          <ArrowLeft className="h-4 w-4" />
          {t("wizard.back", "Back")}
        </button>

        {currentStep < STEPS.length - 1 ? (
          <button
            onClick={handleNext}
            disabled={!canProceed()}
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md disabled:cursor-not-allowed disabled:opacity-50 active:scale-[0.98]"
            data-testid="wizard-next"
          >
            {t("wizard.next", "Next")}
            <ArrowRight className="h-4 w-4" />
          </button>
        ) : (
          <div className="flex items-center gap-2">
            <button
              onClick={() => handleCreate(false)}
              disabled={isCreating}
              className="inline-flex items-center gap-2 rounded-lg border border-primary/30 px-4 py-2.5 text-sm font-medium text-primary transition-colors hover:bg-primary/10 disabled:cursor-not-allowed disabled:opacity-50"
              data-testid="wizard-create-only"
            >
              {isCreating ? (
                <RefreshCw className="h-4 w-4 animate-spin" />
              ) : (
                <Check className="h-4 w-4" />
              )}
              {t("wizard.createOnly", "Create Only")}
            </button>
            <button
              onClick={() => handleCreate(true)}
              disabled={isCreating}
              className="inline-flex items-center gap-2 rounded-lg bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md disabled:cursor-not-allowed disabled:opacity-50 active:scale-[0.98]"
              data-testid="wizard-create-deploy"
            >
              {isCreating ? (
                <RefreshCw className="h-4 w-4 animate-spin" />
              ) : (
                <Rocket className="h-4 w-4" />
              )}
              {t("wizard.createAndDeploy", "Create & Deploy")}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

/* ---------- Step components ---------- */

function TemplateStep({
  selected,
  onSelect,
}: {
  selected: string;
  onSelect: (id: string) => void;
}) {
  const { t } = useTranslation();

  const templateNames: Record<string, string> = {
    blank: t("wizard.templateBlank", "Blank Bot"),
    qa: t("wizard.templateQA", "Q&A Bot"),
    weather: t("wizard.templateWeather", "Weather Bot"),
  };

  const templateDescriptions: Record<string, string> = {
    blank: t("wizard.templateBlankDesc", "Start from scratch with an empty bot configuration"),
    qa: t("wizard.templateQADesc", "Pre-configured bot for answering frequently asked questions"),
    weather: t("wizard.templateWeatherDesc", "Example bot using HTTP calls to fetch weather data"),
  };

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("wizard.step1Title", "Choose a Template")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("wizard.step1Desc", "Select a starting point for your bot")}
      </p>

      <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-3" data-testid="template-grid">
        {TEMPLATES.map((tmpl) => {
          const Icon = tmpl.icon;
          const isSelected = selected === tmpl.id;
          return (
            <button
              key={tmpl.id}
              onClick={() => onSelect(tmpl.id)}
              className={cn(
                "group relative flex flex-col items-center gap-3 rounded-xl border-2 p-6 text-center transition-all duration-200",
                isSelected
                  ? "border-primary bg-primary/5 shadow-md shadow-primary/10"
                  : "border-border hover:border-primary/40 hover:shadow-sm"
              )}
              data-testid={`template-${tmpl.id}`}
            >
              <div
                className={cn(
                  "flex h-14 w-14 items-center justify-center rounded-xl bg-linear-to-br text-white shadow-lg transition-transform group-hover:scale-105",
                  tmpl.gradient
                )}
              >
                <Icon className="h-7 w-7" />
              </div>
              <div>
                <p className="font-semibold text-foreground">
                  {templateNames[tmpl.id]}
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  {templateDescriptions[tmpl.id]}
                </p>
              </div>
              {isSelected && (
                <div className="absolute inset-e-2 top-2 flex h-6 w-6 items-center justify-center rounded-full bg-primary text-primary-foreground">
                  <Check className="h-3.5 w-3.5" />
                </div>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function InfoStep({
  name,
  description,
  onNameChange,
  onDescriptionChange,
}: {
  name: string;
  description: string;
  onNameChange: (v: string) => void;
  onDescriptionChange: (v: string) => void;
}) {
  const { t } = useTranslation();
  const nameRef = useRef<HTMLInputElement>(null);

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("wizard.step2Title", "Bot Information")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("wizard.step2Desc", "Give your bot a name and description")}
      </p>

      <div className="mt-6 space-y-5">
        <div>
          <label
            htmlFor="wizard-name"
            className="mb-1.5 block text-sm font-medium text-foreground"
          >
            {t("bots.name", "Name")} *
          </label>
          <input
            ref={nameRef}
            id="wizard-name"
            type="text"
            value={name}
            onChange={(e) => onNameChange(e.target.value)}
            placeholder={t("bots.namePlaceholder", "My Bot")}
            className="w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="wizard-bot-name"
            autoFocus
          />
        </div>
        <div>
          <label
            htmlFor="wizard-desc"
            className="mb-1.5 block text-sm font-medium text-foreground"
          >
            {t("bots.description", "Description")}
          </label>
          <textarea
            id="wizard-desc"
            value={description}
            onChange={(e) => onDescriptionChange(e.target.value)}
            placeholder={t(
              "bots.descriptionPlaceholder",
              "Describe what this bot does..."
            )}
            rows={3}
            className="w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-none transition-shadow"
            data-testid="wizard-bot-desc"
          />
        </div>
      </div>
    </div>
  );
}

function PackagesStep({
  createPackage,
  onToggle,
}: {
  createPackage: boolean;
  onToggle: (v: boolean) => void;
}) {
  const { t } = useTranslation();

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("wizard.step3Title", "Packages")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t(
          "wizard.step3Desc",
          "Configure the initial packages for your bot"
        )}
      </p>

      <div className="mt-6 space-y-4">
        <label className="flex cursor-pointer items-start gap-3 rounded-lg border border-border p-4 transition-colors hover:bg-secondary/50">
          <input
            type="checkbox"
            checked={createPackage}
            onChange={(e) => onToggle(e.target.checked)}
            className="mt-0.5 h-4 w-4 rounded border-input text-primary accent-primary"
            data-testid="wizard-create-package"
          />
          <div>
            <p className="text-sm font-medium text-foreground">
              {t("wizard.createDefaultPackage", "Create a default package")}
            </p>
            <p className="mt-0.5 text-xs text-muted-foreground">
              {t(
                "wizard.createDefaultPackageDesc",
                "An empty package will be created and linked to the bot. You can add extensions later."
              )}
            </p>
          </div>
        </label>
      </div>
    </div>
  );
}

function ReviewStep({ state }: { state: WizardState }) {
  const { t } = useTranslation();

  const templateLabels: Record<string, string> = {
    blank: t("wizard.templateBlank", "Blank Bot"),
    qa: t("wizard.templateQA", "Q&A Bot"),
    weather: t("wizard.templateWeather", "Weather Bot"),
  };

  return (
    <div data-testid="wizard-review">
      <h2 className="text-xl font-semibold text-foreground">
        {t("wizard.step4Title", "Review & Create")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("wizard.step4Desc", "Review your bot configuration before creating")}
      </p>

      <div className="mt-6 space-y-4">
        <div className="rounded-lg border border-border divide-y divide-border overflow-hidden">
          <div className="flex items-center justify-between px-4 py-3 bg-secondary/30">
            <span className="text-sm font-medium text-muted-foreground">
              {t("wizard.templateLabel", "Template")}
            </span>
            <span className="text-sm font-medium text-foreground">
              {templateLabels[state.template] || state.template}
            </span>
          </div>
          <div className="flex items-center justify-between px-4 py-3">
            <span className="text-sm font-medium text-muted-foreground">
              {t("bots.name", "Name")}
            </span>
            <span className="text-sm font-medium text-foreground">
              {state.name || "—"}
            </span>
          </div>
          <div className="flex items-center justify-between px-4 py-3 bg-secondary/30">
            <span className="text-sm font-medium text-muted-foreground">
              {t("bots.description", "Description")}
            </span>
            <span className="text-sm text-foreground max-w-xs truncate">
              {state.description || "—"}
            </span>
          </div>
          <div className="flex items-center justify-between px-4 py-3">
            <span className="text-sm font-medium text-muted-foreground">
              {t("wizard.defaultPackageLabel", "Default Package")}
            </span>
            <span className="text-sm font-medium text-foreground">
              {state.createPackage ? "✓" : "—"}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
