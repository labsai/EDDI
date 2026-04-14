import { useState, useCallback, useMemo, useRef } from "react";
import { useTranslation } from "react-i18next";
import {
  Wand2,
  ArrowLeft,
  ArrowRight,
  Check,
  Bot,
  Globe,
  Brain,
  Settings2,
  Rocket,
  RefreshCw,
  Upload,
  Link as LinkIcon,
  FileCode2,
  Sparkles,
  MessageSquarePlus,
  BarChart3,
  Wrench,
  ChevronDown,
  AlertCircle,
  CheckCircle2,
  ExternalLink,
  ListFilter,
  Info,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useSetupAgent, useCreateApiAgent } from "@/hooks/use-agent-setup";
import {
  LLM_PROVIDERS,
  getProviderConfig,
  type SetupAgentRequest,
  type CreateApiAgentRequest,
  type SetupResult,
} from "@/lib/api/agent-setup";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { BUILT_IN_TOOLS } from "@/components/editors/llm/types";

/* ================================================================
   Types & Constants
   ================================================================ */

type WizardMode = "standard" | "api";

interface WizardState {
  mode: WizardMode | "";
  // Shared
  name: string;
  systemPrompt: string;
  // LLM
  provider: string;
  model: string;
  apiKey: string;
  baseUrl: string;
  // Standard-specific
  introMessage: string;
  enableBuiltInTools: boolean;
  builtInToolsWhitelist: string;
  enableQuickReplies: boolean;
  enableSentimentAnalysis: boolean;
  // API-specific
  openApiSpec: string;
  apiBaseUrl: string;
  apiAuth: string;
  endpoints: string;
  specInputMode: "url" | "file" | "paste";
  specUrl: string;
  // Deploy
  deploy: boolean;
  environment: string;
}

const INITIAL_STATE: WizardState = {
  mode: "",
  name: "",
  systemPrompt: "",
  provider: "anthropic",
  model: "",
  apiKey: "",
  baseUrl: "",
  introMessage: "",
  enableBuiltInTools: false,
  builtInToolsWhitelist: "",
  enableQuickReplies: false,
  enableSentimentAnalysis: false,
  openApiSpec: "",
  apiBaseUrl: "",
  apiAuth: "",
  endpoints: "",
  specInputMode: "url",
  specUrl: "",
  deploy: true,
  environment: "production",
};

/** Popular model suggestions per provider — users can still type any custom model */
const MODEL_SUGGESTIONS: Record<string, string[]> = {
  anthropic: [
    "claude-sonnet-4-6",
    "claude-opus-4-6",
    "claude-haiku-4-5",
  ],
  openai: [
    "gpt-5.4",
    "gpt-5.4-mini",
    "gpt-5.4-thinking",
    "o3-mini",
  ],
  gemini: [
    "gemini-2.5-flash",
    "gemini-2.5-pro",
    "gemini-3-flash-preview",
    "gemini-3.1-pro-preview",
    "gemini-3.1-flash-lite-preview",
  ],
  "gemini-vertex": [
    "gemini-2.5-flash",
    "gemini-2.5-pro",
    "gemini-3-flash-preview",
    "gemini-3.1-pro-preview",
  ],
  ollama: [
    "gemma4:4b",
    "llama3.2:3b",
    "llama3.1:8b",
    "gemma3:4b",
    "mistral:7b",
    "qwen2.5:7b",
    "deepseek-r1:8b",
    "phi4:mini",
  ],
  jlama: [
    "llama-3.2-1b",
    "tinyllama",
  ],
  huggingface: [
    "Qwen/Qwen3.5-7B",
    "meta-llama/Llama-3.2-1B",
  ],
  mistral: [
    "mistral-large-latest",
    "mistral-medium-latest",
    "mistral-small-latest",
    "magistral-medium-latest",
    "magistral-small-latest",
  ],
  "azure-openai": [
    "gpt-5.4",
    "gpt-5.4-mini",
  ],
  bedrock: [
    "anthropic.claude-sonnet-4-6-v1",
    "amazon.nova-pro-v1:0",
    "amazon.nova-lite-v1:0",
  ],
  "oracle-genai": [
    "cohere.command-r-plus",
    "meta.llama-3.1-70b-instruct",
  ],
};

/** Whether a provider requires a base URL (local providers) or it's just optional */
function isBaseUrlRequired(providerId: string): boolean {
  return providerId === "ollama" || providerId === "jlama";
}

const STEPS_STANDARD = [
  { id: "type", icon: Sparkles, label: "Type" },
  { id: "info", icon: Brain, label: "Identity" },
  { id: "llm", icon: Settings2, label: "Model" },
  { id: "features", icon: Wrench, label: "Features" },
  { id: "review", icon: Rocket, label: "Review" },
] as const;

const STEPS_API = [
  { id: "type", icon: Sparkles, label: "Type" },
  { id: "info", icon: Brain, label: "Identity" },
  { id: "apispec", icon: Globe, label: "API Spec" },
  { id: "llm", icon: Settings2, label: "Model" },
  { id: "features", icon: Wrench, label: "Features" },
  { id: "review", icon: Rocket, label: "Review" },
] as const;

/* ================================================================
   Main Wizard Component
   ================================================================ */

export function AgentWizardPage() {
  const { t } = useTranslation();

  const [currentStep, setCurrentStep] = useState(0);
  const [state, setState] = useState<WizardState>(INITIAL_STATE);
  const [result, setResult] = useState<SetupResult | null>(null);
  const [error, setError] = useState<string>("");

  const setupAgent = useSetupAgent();
  const createApiAgent = useCreateApiAgent();

  const isCreating = setupAgent.isPending || createApiAgent.isPending;
  const steps = state.mode === "api" ? STEPS_API : STEPS_STANDARD;
  const step = steps[currentStep];

  const update = useCallback(
    (patch: Partial<WizardState>) => setState((s) => ({ ...s, ...patch })),
    [],
  );

  function handleProviderChange(providerId: string) {
    const config = getProviderConfig(providerId);
    update({
      provider: providerId,
      model: "",
      apiKey: config?.needsKey === false ? "" : state.apiKey,
    });
  }

  function canProceed(): boolean {
    if (!step) return false;
    switch (step.id) {
      case "type":
        return state.mode !== "";
      case "info":
        return state.name.trim().length > 0 && state.systemPrompt.trim().length > 0;
      case "llm": {
        const prov = getProviderConfig(state.provider);
        if (prov?.needsKey && !state.apiKey.trim()) return false;
        return state.model.trim().length > 0;
      }
      case "apispec":
        return state.openApiSpec.trim().length > 0;
      case "features":
      case "review":
        return true;
      default:
        return false;
    }
  }

  function handleNext() {
    if (currentStep < steps.length - 1) setCurrentStep(currentStep + 1);
  }

  function handleBack() {
    if (currentStep > 0) setCurrentStep(currentStep - 1);
  }

  async function handleCreate() {
    setError("");
    try {
      let res: SetupResult;
      if (state.mode === "api") {
        const req: CreateApiAgentRequest = {
          name: state.name,
          systemPrompt: state.systemPrompt,
          openApiSpec: state.openApiSpec,
          provider: state.provider,
          model: state.model,
          apiKey: state.apiKey || undefined,
          apiBaseUrl: state.apiBaseUrl || undefined,
          apiAuth: state.apiAuth || undefined,
          endpoints: state.endpoints || undefined,
          enableQuickReplies: state.enableQuickReplies || undefined,
          enableSentimentAnalysis: state.enableSentimentAnalysis || undefined,
          deploy: state.deploy,
          environment: state.environment,
        };
        res = await createApiAgent.mutateAsync(req);
      } else {
        const req: SetupAgentRequest = {
          name: state.name,
          systemPrompt: state.systemPrompt,
          provider: state.provider,
          model: state.model,
          apiKey: state.apiKey || undefined,
          baseUrl: state.baseUrl || undefined,
          introMessage: state.introMessage || undefined,
          enableBuiltInTools: state.enableBuiltInTools || undefined,
          builtInToolsWhitelist: state.builtInToolsWhitelist || undefined,
          enableQuickReplies: state.enableQuickReplies || undefined,
          enableSentimentAnalysis: state.enableSentimentAnalysis || undefined,
          deploy: state.deploy,
          environment: state.environment,
        };
        res = await setupAgent.mutateAsync(req);
      }
      setResult(res);
      toast.success(t("setupWizard.success", "Agent created successfully!"));
    } catch (err: unknown) {
      const msg =
        err && typeof err === "object" && "message" in err
          ? (err as { message: string }).message
          : t("common.error");
      setError(msg);
      toast.error(msg);
    }
  }

  // ---- Success state ----
  if (result) {
    return (
      <div className="mx-auto max-w-2xl space-y-8">
        <div className="rounded-2xl border border-emerald-500/30 bg-card p-8 text-center shadow-lg">
          <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-emerald-500/10">
            <CheckCircle2 className="h-8 w-8 text-emerald-500" />
          </div>
          <h2 className="text-2xl font-bold text-foreground">
            {t("setupWizard.agentCreated", "Agent Created!")}
          </h2>
          <p className="mt-2 text-muted-foreground">
            {result.agentName} — {result.provider}/{result.model}
          </p>

          {result.deployed && (
            <div className="mt-3 inline-flex items-center gap-1.5 rounded-full bg-emerald-500/10 px-3 py-1 text-sm font-medium text-emerald-600 dark:text-emerald-400">
              <CheckCircle2 className="h-3.5 w-3.5" />
              {t("setupWizard.deployed", "Deployed")} — {result.deploymentStatus}
            </div>
          )}
          {result.deployed === false && result.deploymentStatus && (
            <div className="mt-3 inline-flex items-center gap-1.5 rounded-full bg-amber-500/10 px-3 py-1 text-sm font-medium text-amber-600 dark:text-amber-400">
              <AlertCircle className="h-3.5 w-3.5" />
              {result.deploymentStatus}
            </div>
          )}

          {result.endpointCount != null && (
            <p className="mt-3 text-sm text-muted-foreground">
              {t("setupWizard.endpointsParsed", "{{count}} API endpoints parsed", {
                count: result.endpointCount,
              })}
              {result.groups && result.groups.length > 0 && (
                <> — {result.groups.join(", ")}</>
              )}
            </p>
          )}

          <div className="mt-6 flex items-center justify-center gap-3">
            <Link
              to={`/manage/agentview/${result.agentId}`}
              className="inline-flex items-center gap-2 rounded-lg bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md active:scale-[0.98]"
            >
              {t("setupWizard.viewAgent", "View Agent")}
              <ExternalLink className="h-4 w-4" />
            </Link>
            <button
              onClick={() => {
                setState(INITIAL_STATE);
                setCurrentStep(0);
                setResult(null);
                setError("");
              }}
              className="inline-flex items-center gap-2 rounded-lg border border-border px-4 py-2.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
            >
              {t("setupWizard.createAnother", "Create Another")}
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl space-y-8">
      {/* Header */}
      <div className="space-y-2">
        <Link
          to="/manage/agents"
          className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
          {t("setupWizard.backToAgents", "Back to Agents")}
        </Link>
        <h1 className="flex items-center gap-3 text-3xl font-bold text-foreground">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-linear-to-br from-primary to-primary/60 text-primary-foreground shadow-lg">
            <Wand2 className="h-5 w-5" />
          </div>
          {t("setupWizard.title", "Agent Setup Wizard")}
        </h1>
        <p className="text-muted-foreground">
          {t("setupWizard.subtitle", "Create a fully configured agent in minutes")}
        </p>
      </div>

      {/* Step progress */}
      <div className="flex items-center gap-2" data-testid="wizard-steps">
        {steps.map((s, i) => {
          const Icon = s.icon;
          const isActive = i === currentStep;
          const isComplete = i < currentStep;
          return (
            <div key={s.id} className="flex flex-1 items-center gap-2">
              <div className="flex flex-col items-center gap-1">
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
                      "border-border bg-card text-muted-foreground",
                  )}
                >
                  {isComplete ? (
                    <Check className="h-4 w-4" />
                  ) : (
                    <Icon className="h-4 w-4" />
                  )}
                </button>
                <span
                  className={cn(
                    "text-[10px] font-medium transition-colors duration-300 whitespace-nowrap",
                    isActive && "text-primary",
                    isComplete && "text-emerald-500",
                    !isActive && !isComplete && "text-muted-foreground",
                  )}
                >
                  {s.label}
                </span>
              </div>
              {i < steps.length - 1 && (
                <div
                  className={cn(
                    "h-0.5 flex-1 rounded-full transition-colors duration-300 mb-5",
                    isComplete ? "bg-emerald-500" : "bg-border",
                  )}
                />
              )}
            </div>
          );
        })}
      </div>

      {/* Step content */}
      <div className="rounded-xl border bg-card p-6 shadow-sm">
        {step?.id === "type" && <TypeStep mode={state.mode} onSelect={(m) => update({ mode: m })} />}
        {step?.id === "info" && (
          <InfoStep
            name={state.name}
            systemPrompt={state.systemPrompt}
            onNameChange={(name) => update({ name })}
            onPromptChange={(systemPrompt) => update({ systemPrompt })}
          />
        )}
        {step?.id === "llm" && (
          <LlmStep
            provider={state.provider}
            model={state.model}
            apiKey={state.apiKey}
            baseUrl={state.baseUrl}
            onProviderChange={handleProviderChange}
            onModelChange={(model) => update({ model })}
            onApiKeyChange={(apiKey) => update({ apiKey })}
            onBaseUrlChange={(baseUrl) => update({ baseUrl })}
          />
        )}
        {step?.id === "apispec" && (
          <ApiSpecStep
            spec={state.openApiSpec}
            specUrl={state.specUrl}
            specInputMode={state.specInputMode}
            apiBaseUrl={state.apiBaseUrl}
            apiAuth={state.apiAuth}
            endpoints={state.endpoints}
            onSpecChange={(openApiSpec) => update({ openApiSpec })}
            onSpecUrlChange={(specUrl) => update({ specUrl })}
            onModeChange={(specInputMode) => update({ specInputMode })}
            onApiBaseUrlChange={(apiBaseUrl) => update({ apiBaseUrl })}
            onApiAuthChange={(apiAuth) => update({ apiAuth })}
            onEndpointsChange={(endpoints) => update({ endpoints })}
          />
        )}
        {step?.id === "features" && (
          <FeaturesStep state={state} onChange={update} />
        )}
        {step?.id === "review" && <ReviewStep state={state} error={error} />}
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
              : "text-muted-foreground hover:bg-secondary hover:text-foreground",
          )}
          data-testid="wizard-back"
        >
          <ArrowLeft className="h-4 w-4" />
          {t("setupWizard.back", "Back")}
        </button>

        {step?.id !== "review" ? (
          <button
            onClick={handleNext}
            disabled={!canProceed()}
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md disabled:cursor-not-allowed disabled:opacity-50 active:scale-[0.98]"
            data-testid="wizard-next"
          >
            {t("setupWizard.next", "Next")}
            <ArrowRight className="h-4 w-4" />
          </button>
        ) : (
          <div className="flex items-center gap-2">
            <button
              onClick={() => {
                update({ deploy: false });
                setTimeout(handleCreate, 0);
              }}
              disabled={isCreating}
              className="inline-flex items-center gap-2 rounded-lg border border-primary/30 px-4 py-2.5 text-sm font-medium text-primary transition-colors hover:bg-primary/10 disabled:cursor-not-allowed disabled:opacity-50"
              data-testid="wizard-create-only"
            >
              {isCreating ? (
                <RefreshCw className="h-4 w-4 animate-spin" />
              ) : (
                <Check className="h-4 w-4" />
              )}
              {t("setupWizard.createOnly", "Create Only")}
            </button>
            <button
              onClick={() => {
                update({ deploy: true });
                setTimeout(handleCreate, 0);
              }}
              disabled={isCreating}
              className="inline-flex items-center gap-2 rounded-lg bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md disabled:cursor-not-allowed disabled:opacity-50 active:scale-[0.98]"
              data-testid="wizard-create-deploy"
            >
              {isCreating ? (
                <RefreshCw className="h-4 w-4 animate-spin" />
              ) : (
                <Rocket className="h-4 w-4" />
              )}
              {t("setupWizard.createAndDeploy", "Create & Deploy")}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

/* ================================================================
   Step 1: Choose Type
   ================================================================ */

function TypeStep({
  mode,
  onSelect,
}: {
  mode: WizardMode | "";
  onSelect: (m: WizardMode) => void;
}) {
  const { t } = useTranslation();
  const types: {
    id: WizardMode;
    icon: typeof Bot;
    gradient: string;
  }[] = [
    {
      id: "standard",
      icon: Bot,
      gradient: "from-violet-500 to-indigo-600",
    },
    {
      id: "api",
      icon: Globe,
      gradient: "from-emerald-500 to-teal-600",
    },
  ];

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("setupWizard.typeTitle", "What kind of agent?")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("setupWizard.typeDesc", "Choose the type of agent you want to create")}
      </p>

      <div
        className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2"
        data-testid="type-grid"
      >
        {types.map((tp) => {
          const Icon = tp.icon;
          const isSelected = mode === tp.id;
          return (
            <button
              key={tp.id}
              onClick={() => onSelect(tp.id)}
              className={cn(
                "group relative flex flex-col items-center gap-4 rounded-xl border-2 p-8 text-center transition-all duration-200",
                isSelected
                  ? "border-primary bg-primary/5 shadow-lg shadow-primary/10"
                  : "border-border hover:border-primary/40 hover:shadow-md",
              )}
              data-testid={`type-${tp.id}`}
            >
              <div
                className={cn(
                  "flex h-16 w-16 items-center justify-center rounded-2xl bg-linear-to-br text-white shadow-lg transition-transform group-hover:scale-110",
                  tp.gradient,
                )}
              >
                <Icon className="h-8 w-8" />
              </div>
              <div>
                <p className="text-lg font-semibold text-foreground">
                  {tp.id === "standard"
                    ? t("setupWizard.standardAgent", "Standard Agent")
                    : t("setupWizard.apiAgent", "API Agent")}
                </p>
                <p className="mt-1.5 text-sm text-muted-foreground leading-relaxed">
                  {tp.id === "standard"
                    ? t(
                        "setupWizard.standardDesc",
                        "Conversational AI powered by an LLM with optional tools, quick replies, and sentiment analysis",
                      )
                    : t(
                        "setupWizard.apiDesc",
                        "Import an OpenAPI/Swagger specification to auto-generate an AI agent that can call your APIs",
                      )}
                </p>
              </div>
              {isSelected && (
                <div className="absolute inset-e-3 top-3 flex h-7 w-7 items-center justify-center rounded-full bg-primary text-primary-foreground shadow">
                  <Check className="h-4 w-4" />
                </div>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/* ================================================================
   Step 2: Agent Info
   ================================================================ */

function InfoStep({
  name,
  systemPrompt,
  onNameChange,
  onPromptChange,
}: {
  name: string;
  systemPrompt: string;
  onNameChange: (v: string) => void;
  onPromptChange: (v: string) => void;
}) {
  const { t } = useTranslation();

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("setupWizard.infoTitle", "Agent Identity")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("setupWizard.infoDesc", "Give your agent a name and personality")}
      </p>

      <div className="mt-6 space-y-5">
        <div>
          <label
            htmlFor="wizard-name"
            className="mb-1.5 block text-sm font-medium text-foreground"
          >
            {t("setupWizard.agentName", "Agent Name")} *
          </label>
          <input
            id="wizard-name"
            type="text"
            value={name}
            onChange={(e) => onNameChange(e.target.value)}
            placeholder={t("setupWizard.namePlaceholder", "e.g. Customer Support Bot")}
            className="w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="wizard-agent-name"
            autoFocus
          />
        </div>
        <div>
          <label
            htmlFor="wizard-prompt"
            className="mb-1.5 block text-sm font-medium text-foreground"
          >
            {t("setupWizard.systemPrompt", "System Prompt")} *
          </label>
          <textarea
            id="wizard-prompt"
            value={systemPrompt}
            onChange={(e) => onPromptChange(e.target.value)}
            placeholder={t(
              "setupWizard.promptPlaceholder",
              "You are a helpful AI assistant that...",
            )}
            rows={5}
            className="w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-y transition-shadow font-mono"
            data-testid="wizard-system-prompt"
          />
          <p className="mt-1 text-xs text-muted-foreground">
            {t(
              "setupWizard.promptHint",
              "This is the instruction that shapes how your agent behaves and responds.",
            )}
          </p>
        </div>
      </div>
    </div>
  );
}

/* ================================================================
   Step 3: LLM Configuration
   ================================================================ */

function LlmStep({
  provider,
  model,
  apiKey,
  baseUrl,
  onProviderChange,
  onModelChange,
  onApiKeyChange,
  onBaseUrlChange,
}: {
  provider: string;
  model: string;
  apiKey: string;
  baseUrl: string;
  onProviderChange: (v: string) => void;
  onModelChange: (v: string) => void;
  onApiKeyChange: (v: string) => void;
  onBaseUrlChange: (v: string) => void;
}) {
  const { t } = useTranslation();
  const prov = getProviderConfig(provider);
  const suggestions = useMemo(() => MODEL_SUGGESTIONS[provider] ?? [], [provider]);
  const datalistId = `model-suggestions-${provider}`;
  const baseUrlRequired = isBaseUrlRequired(provider);

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("setupWizard.llmTitle", "LLM Configuration")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("setupWizard.llmDesc", "Choose the AI model that powers your agent")}
      </p>

      <div className="mt-6 space-y-5">
        {/* Provider */}
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            {t("setupWizard.provider", "Provider")}
          </label>
          <div className="relative">
            <select
              value={provider}
              onChange={(e) => onProviderChange(e.target.value)}
              className="w-full appearance-none rounded-lg border border-input bg-background px-3 py-2.5 pe-10 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
              data-testid="wizard-provider"
            >
              {LLM_PROVIDERS.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
            <ChevronDown className="pointer-events-none absolute inset-e-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          </div>
        </div>

        {/* Model — combobox with autocomplete suggestions */}
        <div>
          <label
            htmlFor="wizard-model"
            className="mb-1.5 block text-sm font-medium text-foreground"
          >
            {t("setupWizard.model", "Model")} *
          </label>
          <input
            id="wizard-model"
            type="text"
            list={datalistId}
            value={model}
            onChange={(e) => onModelChange(e.target.value)}
            placeholder={t("setupWizard.modelPlaceholder", "e.g. {{model}} (or any model your provider supports)", { model: prov?.defaultModel ?? "" })}
            className="w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="wizard-model"
            autoComplete="off"
          />
          <datalist id={datalistId}>
            {suggestions.map((m) => (
              <option key={m} value={m} />
            ))}
          </datalist>
          <p className="mt-1 text-xs text-muted-foreground">
            {t(
              "setupWizard.modelHint",
              "Type any model name supported by your provider, or pick one from the suggestions"
            )}
          </p>
        </div>

        {/* API Key */}
        {prov?.needsKey !== false && (
          <div>
            <label
              htmlFor="wizard-apikey"
              className="mb-1.5 block text-sm font-medium text-foreground"
            >
              {t("setupWizard.apiKey", "API Key")} *
            </label>
            <SecretKeyPicker
              value={apiKey}
              onChange={onApiKeyChange}
              placeholder="sk-..."
              testId="wizard-apikey"
            />
          </div>
        )}

        {/* Base URL */}
        <div>
          <label
            htmlFor="wizard-baseurl"
            className="mb-1.5 block text-sm font-medium text-foreground"
          >
            {t("setupWizard.baseUrl", "Base URL")}{" "}
            {!baseUrlRequired && (
              <span className="text-muted-foreground font-normal">
                ({t("setupWizard.optional", "optional")})
              </span>
            )}
            {baseUrlRequired && (
              <span className="text-primary font-normal">*</span>
            )}
          </label>
          <input
            id="wizard-baseurl"
            type="url"
            value={baseUrl}
            onChange={(e) => onBaseUrlChange(e.target.value)}
            placeholder={
              provider === "ollama"
                ? "http://localhost:11434"
                : provider === "jlama"
                  ? "http://localhost:8080"
                  : t("setupWizard.baseUrlPlaceholder", "Custom endpoint URL")
            }
            className="w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="wizard-baseurl"
          />
          <p className="mt-1 text-xs text-muted-foreground">
            {baseUrlRequired
              ? t(
                  "setupWizard.baseUrlHintLocal",
                  "Required — the URL where your local model server is running"
                )
              : t(
                  "setupWizard.baseUrlHintCloud",
                  "Only needed if using a proxy, private deployment, or Azure OpenAI endpoint"
                )}
          </p>
        </div>
      </div>
    </div>
  );
}

/* ================================================================
   Step 3B: OpenAPI Spec (API Agent only)
   ================================================================ */

function ApiSpecStep({
  spec,
  specUrl,
  specInputMode,
  apiBaseUrl,
  apiAuth,
  endpoints,
  onSpecChange,
  onSpecUrlChange,
  onModeChange,
  onApiBaseUrlChange,
  onApiAuthChange,
  onEndpointsChange,
}: {
  spec: string;
  specUrl: string;
  specInputMode: "url" | "file" | "paste";
  apiBaseUrl: string;
  apiAuth: string;
  endpoints: string;
  onSpecChange: (v: string) => void;
  onSpecUrlChange: (v: string) => void;
  onModeChange: (v: "url" | "file" | "paste") => void;
  onApiBaseUrlChange: (v: string) => void;
  onApiAuthChange: (v: string) => void;
  onEndpointsChange: (v: string) => void;
}) {
  const { t } = useTranslation();
  const fileRef = useRef<HTMLInputElement>(null);
  const [fetching, setFetching] = useState(false);

  const inputModes = [
    { id: "url" as const, icon: LinkIcon, label: t("setupWizard.specByUrl", "URL") },
    { id: "file" as const, icon: Upload, label: t("setupWizard.specByFile", "File") },
    { id: "paste" as const, icon: FileCode2, label: t("setupWizard.specByPaste", "Paste") },
  ];

  async function fetchSpec() {
    if (!specUrl.trim()) return;
    setFetching(true);
    try {
      const res = await fetch(specUrl);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const text = await res.text();
      onSpecChange(text);
      toast.success(t("setupWizard.specFetched", "Specification loaded!"));
    } catch {
      toast.error(t("setupWizard.specFetchError", "Failed to fetch specification"));
    } finally {
      setFetching(false);
    }
  }

  function handleFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      onSpecChange(reader.result as string);
      toast.success(t("setupWizard.specLoaded", "File loaded: {{name}}", { name: file.name }));
    };
    reader.readAsText(file);
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault();
    const file = e.dataTransfer.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      onSpecChange(reader.result as string);
      onModeChange("file");
      toast.success(t("setupWizard.specLoaded", "File loaded: {{name}}", { name: file.name }));
    };
    reader.readAsText(file);
  }

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("setupWizard.apiSpecTitle", "OpenAPI Specification")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("setupWizard.apiSpecDesc", "Provide your API's OpenAPI/Swagger spec")}
      </p>

      {/* Input mode tabs */}
      <div className="mt-4 flex rounded-lg border border-border bg-secondary/30 p-1" data-testid="spec-mode-tabs">
        {inputModes.map((m) => {
          const Icon = m.icon;
          return (
            <button
              key={m.id}
              onClick={() => onModeChange(m.id)}
              className={cn(
                "flex flex-1 items-center justify-center gap-1.5 rounded-md px-3 py-2 text-sm font-medium transition-all",
                specInputMode === m.id
                  ? "bg-background text-foreground shadow-sm"
                  : "text-muted-foreground hover:text-foreground",
              )}
            >
              <Icon className="h-4 w-4" />
              {m.label}
            </button>
          );
        })}
      </div>

      <div className="mt-4 space-y-4">
        {specInputMode === "url" && (
          <div className="flex gap-2">
            <input
              type="url"
              value={specUrl}
              onChange={(e) => onSpecUrlChange(e.target.value)}
              placeholder="https://api.example.com/openapi.json"
              className="flex-1 rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
              data-testid="wizard-spec-url"
            />
            <button
              onClick={fetchSpec}
              disabled={fetching || !specUrl.trim()}
              className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
              data-testid="wizard-fetch-spec"
            >
              {fetching ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Globe className="h-4 w-4" />}
              {t("setupWizard.fetch", "Fetch")}
            </button>
          </div>
        )}

        {specInputMode === "file" && (
          <div
            onDrop={handleDrop}
            onDragOver={(e) => e.preventDefault()}
            className="flex flex-col items-center justify-center rounded-xl border-2 border-dashed border-border bg-secondary/20 p-8 text-center transition-colors hover:border-primary/40"
            data-testid="wizard-spec-dropzone"
          >
            <Upload className="mb-3 h-8 w-8 text-muted-foreground" />
            <p className="text-sm font-medium text-foreground">
              {t("setupWizard.dropFile", "Drop your OpenAPI file here")}
            </p>
            <p className="mt-1 text-xs text-muted-foreground">
              {t("setupWizard.dropHint", "JSON or YAML format")}
            </p>
            <button
              onClick={() => fileRef.current?.click()}
              className="mt-3 inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
            >
              <Upload className="h-3.5 w-3.5" />
              {t("setupWizard.browse", "Browse Files")}
            </button>
            <input
              ref={fileRef}
              type="file"
              accept=".json,.yaml,.yml"
              onChange={handleFile}
              className="hidden"
              data-testid="wizard-spec-file"
            />
          </div>
        )}

        {specInputMode === "paste" && (
          <textarea
            value={spec}
            onChange={(e) => onSpecChange(e.target.value)}
            placeholder={t("setupWizard.pasteSpec", "Paste your OpenAPI/Swagger JSON or YAML here...")}
            rows={10}
            className="w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-y transition-shadow font-mono"
            data-testid="wizard-spec-paste"
          />
        )}

        {/* Spec preview indicator */}
        {spec && (
          <div className="flex items-center gap-2 rounded-lg bg-emerald-500/10 px-3 py-2 text-sm text-emerald-600 dark:text-emerald-400">
            <CheckCircle2 className="h-4 w-4 shrink-0" />
            {t("setupWizard.specReady", "Specification loaded ({{chars}} chars)", {
              chars: spec.length.toLocaleString(),
            })}
          </div>
        )}

        {/* Optional API config */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label className="mb-1.5 block text-sm font-medium text-foreground">
              {t("setupWizard.apiBaseUrlLabel", "API Base URL")}{" "}
              <span className="text-muted-foreground font-normal">
                ({t("setupWizard.optional", "optional")})
              </span>
            </label>
            <input
              type="url"
              value={apiBaseUrl}
              onChange={(e) => onApiBaseUrlChange(e.target.value)}
              placeholder="https://api.example.com"
              className="w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
              data-testid="wizard-api-baseurl"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-sm font-medium text-foreground">
              {t("setupWizard.apiAuthLabel", "API Auth Header")}{" "}
              <span className="text-muted-foreground font-normal">
                ({t("setupWizard.optional", "optional")})
              </span>
            </label>
            <SecretKeyPicker
              value={apiAuth}
              onChange={onApiAuthChange}
              placeholder="Bearer sk-..."
              testId="wizard-api-auth"
            />
          </div>
        </div>

        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            {t("setupWizard.endpointFilter", "Endpoint Filter")}{" "}
            <span className="text-muted-foreground font-normal">
              ({t("setupWizard.optional", "optional")})
            </span>
          </label>
          <input
            type="text"
            value={endpoints}
            onChange={(e) => onEndpointsChange(e.target.value)}
            placeholder={t("setupWizard.endpointFilterPlaceholder", "Comma-separated: GET /users, POST /orders")}
            className="w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="wizard-endpoints"
          />
        </div>
      </div>
    </div>
  );
}

/* ================================================================
   Step 4: Features & Options
   ================================================================ */

function FeaturesStep({
  state,
  onChange,
}: {
  state: WizardState;
  onChange: (patch: Partial<WizardState>) => void;
}) {
  const { t } = useTranslation();

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("setupWizard.featuresTitle", "Features & Options")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("setupWizard.featuresDesc", "Customize your agent's capabilities")}
      </p>

      <div className="mt-6 space-y-4">
        {/* Intro Message (standard only) */}
        {state.mode === "standard" && (
          <div className="rounded-lg border border-border p-4 space-y-3">
            <FeatureToggle
              icon={MessageSquarePlus}
              label={t("setupWizard.introMessage", "Intro Message")}
              description={t("setupWizard.introMessageDesc", "Greeting shown when a conversation starts")}
              checked={state.introMessage.length > 0}
              onChange={(on) => onChange({ introMessage: on ? state.introMessage || "Hello! How can I help you today?" : "" })}
              data-testid="wizard-toggle-intro"
            />
            {state.introMessage.length > 0 && (
              <textarea
                value={state.introMessage}
                onChange={(e) => onChange({ introMessage: e.target.value })}
                rows={2}
                className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-none transition-shadow"
                data-testid="wizard-intro-text"
              />
            )}
          </div>
        )}

        {/* Quick Replies */}
        <FeatureToggle
          icon={Sparkles}
          label={t("setupWizard.quickReplies", "Quick Replies")}
          description={t("setupWizard.quickRepliesDesc", "LLM generates clickable reply suggestions")}
          checked={state.enableQuickReplies}
          onChange={(on) => onChange({ enableQuickReplies: on })}
          data-testid="wizard-toggle-qr"
        />

        {/* Sentiment Analysis */}
        <FeatureToggle
          icon={BarChart3}
          label={t("setupWizard.sentiment", "Sentiment Analysis")}
          description={t("setupWizard.sentimentDesc", "Track user mood, intent and urgency in real-time")}
          checked={state.enableSentimentAnalysis}
          onChange={(on) => onChange({ enableSentimentAnalysis: on })}
          data-testid="wizard-toggle-sentiment"
        />

        {/* Built-in Tools (standard only) */}
        {state.mode === "standard" && (
          <div className="rounded-lg border border-border p-4 space-y-3">
            <FeatureToggle
              icon={Wrench}
              label={t("setupWizard.builtInTools", "Built-in Tools")}
              description={t("setupWizard.builtInToolsDesc", "Enable EDDI's built-in tool library (calculator, datetime, etc.)")}
              checked={state.enableBuiltInTools}
              onChange={(on) => onChange({ enableBuiltInTools: on })}
              data-testid="wizard-toggle-tools"
            />
            {state.enableBuiltInTools && (
              <div className="space-y-3 ps-9">
                {/* All / Select Specific toggle */}
                <div className="flex items-center gap-1 rounded-lg border border-border bg-background p-0.5" role="radiogroup" aria-label={t("setupWizard.builtInTools", "Built-in Tools")} data-testid="wizard-tool-selection-mode">
                  <button
                    type="button"
                    role="radio"
                    aria-checked={!state.builtInToolsWhitelist}
                    onClick={() => onChange({ builtInToolsWhitelist: "" })}
                    className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-all ${
                      !state.builtInToolsWhitelist
                        ? "bg-primary text-primary-foreground shadow-sm"
                        : "text-muted-foreground hover:text-foreground"
                    }`}
                    data-testid="wizard-tool-mode-all"
                  >
                    <Check className="h-3 w-3" />
                    {t("setupWizard.allTools", "All Tools")}
                  </button>
                  <button
                    type="button"
                    role="radio"
                    aria-checked={!!state.builtInToolsWhitelist}
                    onClick={() => {
                      if (!state.builtInToolsWhitelist) {
                        onChange({ builtInToolsWhitelist: BUILT_IN_TOOLS.join(",") });
                      }
                    }}
                    className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-all ${
                      state.builtInToolsWhitelist
                        ? "bg-primary text-primary-foreground shadow-sm"
                        : "text-muted-foreground hover:text-foreground"
                    }`}
                    data-testid="wizard-tool-mode-specific"
                  >
                    <ListFilter className="h-3 w-3" />
                    {t("setupWizard.selectSpecific", "Select Specific")}
                  </button>
                </div>

                {/* Info banner for All mode */}
                {!state.builtInToolsWhitelist && (
                  <div className="flex items-start gap-2 rounded-md bg-sky-500/10 px-3 py-2 text-xs text-sky-700 dark:text-sky-400" data-testid="wizard-all-tools-info">
                    <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    <span>
                      {t(
                        "setupWizard.allToolsInfo",
                        "All {{count}} built-in tools will be available to your agent. Switch to \"Select Specific\" to restrict which tools are enabled.",
                        { count: BUILT_IN_TOOLS.length }
                      )}
                    </span>
                  </div>
                )}

                {/* Tool chips in Select Specific mode */}
                {state.builtInToolsWhitelist && (() => {
                  const currentTools = state.builtInToolsWhitelist.split(",").filter(Boolean);
                  return (
                    <div className="space-y-2">
                      <div className="flex items-center justify-between">
                        <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                          {t("setupWizard.availableTools", "Available Tools")}
                        </span>
                        <span className="text-[10px] text-muted-foreground">
                          {t("setupWizard.toolCount", "{{selected}} of {{total}} selected", {
                            selected: currentTools.length,
                            total: BUILT_IN_TOOLS.length,
                          })}
                        </span>
                      </div>
                      <div className="flex flex-wrap gap-1.5" data-testid="wizard-tools-whitelist">
                        {BUILT_IN_TOOLS.map((tool) => {
                          const selected = currentTools.includes(tool);
                          return (
                            <button
                              key={tool}
                              type="button"
                              aria-pressed={selected}
                              onClick={() => {
                                const next = selected
                                  ? currentTools.filter((item) => item !== tool)
                                  : [...currentTools, tool];
                                onChange({ builtInToolsWhitelist: next.length > 0 ? next.join(",") : "" });
                              }}
                              className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1.5 text-xs font-medium transition-all ${
                                selected
                                  ? "bg-primary/15 text-primary border border-primary/30 shadow-sm"
                                  : "bg-secondary/50 text-muted-foreground border border-transparent hover:border-border hover:text-foreground"
                              }`}
                              data-testid={`wizard-tool-chip-${tool}`}
                            >
                              {selected && <Check className="h-3 w-3" />}
                              {tool}
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  );
                })()}
              </div>
            )}
          </div>
        )}

        {/* Deploy toggle */}
        <div className="mt-2 rounded-lg border border-primary/20 bg-primary/5 p-4">
          <FeatureToggle
            icon={Rocket}
            label={t("setupWizard.autoDeploy", "Auto-Deploy")}
            description={t("setupWizard.autoDeployDesc", "Deploy the agent immediately after creation")}
            checked={state.deploy}
            onChange={(on) => onChange({ deploy: on })}
            data-testid="wizard-toggle-deploy"
          />
          {state.deploy && (
            <div className="mt-3 flex items-center gap-3 ps-9">
              <label className="text-sm font-medium text-foreground">
                {t("setupWizard.environment", "Environment")}:
              </label>
              <div className="relative">
                <select
                  value={state.environment}
                  onChange={(e) => onChange({ environment: e.target.value })}
                  className="appearance-none rounded-lg border border-input bg-background px-3 py-1.5 pe-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
                  data-testid="wizard-environment"
                >
                  <option value="production">{t("setupWizard.envProduction", "Production")}</option>
                  <option value="test">{t("setupWizard.envTest", "Test")}</option>
                </select>
                <ChevronDown className="pointer-events-none absolute inset-e-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/* ================================================================
   Step 5: Review
   ================================================================ */

function ReviewStep({
  state,
  error,
}: {
  state: WizardState;
  error: string;
}) {
  const { t } = useTranslation();
  const prov = getProviderConfig(state.provider);

  const rows: [string, string][] = [
    [t("setupWizard.reviewType", "Type"), state.mode === "api" ? t("setupWizard.apiAgent", "API Agent") : t("setupWizard.standardAgent", "Standard Agent")],
    [t("setupWizard.reviewName", "Name"), state.name],
    [t("setupWizard.reviewPrompt", "System Prompt"), state.systemPrompt.length > 80 ? state.systemPrompt.slice(0, 80) + "…" : state.systemPrompt],
    [t("setupWizard.reviewProvider", "Provider"), prov?.name ?? state.provider],
    [t("setupWizard.reviewModel", "Model"), state.model],
  ];

  if (state.mode === "api" && state.openApiSpec) {
    rows.push([t("setupWizard.reviewSpec", "OpenAPI Spec"), `${state.openApiSpec.length.toLocaleString()} chars`]);
  }
  if (state.introMessage) {
    rows.push([t("setupWizard.reviewIntro", "Intro Message"), state.introMessage.length > 60 ? state.introMessage.slice(0, 60) + "…" : state.introMessage]);
  }
  if (state.enableQuickReplies) rows.push([t("setupWizard.quickReplies", "Quick Replies"), "✓"]);
  if (state.enableSentimentAnalysis) rows.push([t("setupWizard.sentiment", "Sentiment Analysis"), "✓"]);
  if (state.enableBuiltInTools) rows.push([t("setupWizard.builtInTools", "Built-in Tools"), "✓"]);
  rows.push([t("setupWizard.reviewDeploy", "Deploy"), state.deploy ? `✓ (${state.environment})` : "—"]);

  return (
    <div data-testid="wizard-review">
      <h2 className="text-xl font-semibold text-foreground">
        {t("setupWizard.reviewTitle", "Review & Create")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("setupWizard.reviewDesc", "Review your configuration before creating the agent")}
      </p>

      <div className="mt-6 rounded-lg border border-border divide-y divide-border overflow-hidden">
        {rows.map(([label, value], i) => (
          <div
            key={label}
            className={cn(
              "flex items-start justify-between gap-4 px-4 py-3",
              i % 2 === 0 && "bg-secondary/30",
            )}
          >
            <span className="text-sm font-medium text-muted-foreground whitespace-nowrap">
              {label}
            </span>
            <span className="text-sm font-medium text-foreground text-end break-all max-w-xs">
              {value}
            </span>
          </div>
        ))}
      </div>

      {error && (
        <div className="mt-4 flex items-start gap-2 rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          {error}
        </div>
      )}
    </div>
  );
}

/* ================================================================
   Shared Components
   ================================================================ */

function FeatureToggle({
  icon: Icon,
  label,
  description,
  checked,
  onChange,
  "data-testid": testId,
}: {
  icon: typeof Bot;
  label: string;
  description: string;
  checked: boolean;
  onChange: (v: boolean) => void;
  "data-testid"?: string;
}) {
  return (
    <label className="flex cursor-pointer items-start gap-3 rounded-lg p-0.5 transition-colors">
      <div className="relative mt-0.5 flex h-5 w-9 shrink-0 items-center rounded-full transition-colors duration-200"
        style={{ backgroundColor: checked ? "var(--color-primary)" : "var(--color-border)" }}
      >
        <span
          className={cn(
            "absolute h-4 w-4 rounded-full bg-white shadow-sm transition-transform duration-200",
            checked ? "translate-x-4" : "translate-x-0.5",
          )}
        />
        <input
          type="checkbox"
          checked={checked}
          onChange={(e) => onChange(e.target.checked)}
          className="sr-only"
          data-testid={testId}
        />
      </div>
      <div className="flex items-start gap-2">
        <Icon className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
        <div>
          <p className="text-sm font-medium text-foreground">{label}</p>
          <p className="text-xs text-muted-foreground">{description}</p>
        </div>
      </div>
    </label>
  );
}
