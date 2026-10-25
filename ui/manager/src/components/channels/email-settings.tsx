import { useId, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, Info, ShieldCheck } from "lucide-react";
import { Input } from "@/components/ui/input";
import { useConnectionDescriptors } from "@/hooks/use-connections";
import {
  EMAIL_ATTACHMENT_MODES,
  EMAIL_DEFAULTS,
  EMAIL_DEFAULT_AI_DISCLOSURE,
  EMAIL_DEFAULT_INPUT_TEMPLATE,
  EMAIL_DEFAULT_REPLY_TEMPLATE,
  EMAIL_IDENTITY_MODES,
  EMAIL_MODES,
  EMAIL_PORTS,
  EMAIL_REPLY_TEMPLATE_VARIABLES,
  EMAIL_SMTP_PORTS,
  EMAIL_TEMPLATE_VARIABLES,
  EMAIL_THREAD_PARTICIPANTS,
  aiDisclosureEnabled,
  emailConfigProblems,
  emailMode,
  isOpenTrigger,
  withEmailFixedKeys,
  type EmailProblem,
} from "@/lib/email-channel";
import { emailProblemMessage } from "@/lib/email-channel-messages";

const selectClass =
  "flex h-9 w-full rounded-lg border border-border bg-background px-3 text-sm text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary";
const hintClass = "text-[10px] text-muted-foreground";
const labelClass = "text-xs font-medium";

/** The connection auth types an IMAP login can use. */
const MAIL_AUTH_TYPES = new Set(["BASIC", "OAUTH2_CLIENT_CREDENTIALS"]);

/**
 * Platform settings of an `email` channel integration: EDDI polls the mailbox
 * and every new mail starts an agent turn. In trigger mode nothing is ever sent
 * back; in conversation mode the agent replies in the thread, to the
 * authenticated sender only.
 *
 * Only keys the user set are written (a blank field removes its key, so the
 * backend default applies). The one exception is `aiDisclosure`, whose empty
 * value is how the disclosure is switched off. `transport` is fixed and the
 * other mode's keys are dropped at save time by `withEmailFixedKeys`; switching
 * the mode drops them at once. The backend validates everything again.
 */
export function EmailPlatformFields({
  platformConfig: pc,
  onChange,
  advancedOpen = false,
}: {
  platformConfig: Record<string, string>;
  onChange: (platformConfig: Record<string, string>) => void;
  /** Whether the advanced section starts expanded (the detail page), or collapsed (the create dialog). */
  advancedOpen?: boolean;
}) {
  const { t } = useTranslation();
  const uid = useId();
  const set = (key: string, value: string) => {
    const next = { ...pc };
    if (value === "") delete next[key];
    else next[key] = value;
    onChange(next);
  };

  // Offering the existing mail-capable connections by name is a convenience
  // only: the list is eddi-admin-only, and anyone else simply types the name.
  const { data: connections } = useConnectionDescriptors(100);
  const connectionNames = useMemo(
    () =>
      (connections ?? [])
        .filter((c) => MAIL_AUTH_TYPES.has(c.authType))
        .map((c) => c.connectionName)
        .filter((n): n is string => !!n),
    [connections],
  );

  const problems = new Set(emailConfigProblems(pc));
  const problemFor = (...codes: EmailProblem[]) => {
    const code = codes.find((c) => problems.has(c));
    return code ? (
      <p className="text-[10px] text-destructive" data-testid={`email-problem-${code}`}>
        {emailProblemMessage(code, t)}
      </p>
    ) : null;
  };

  const processedAction = pc.processedAction || EMAIL_DEFAULTS.processedAction;
  const isMove = processedAction.startsWith("move:");
  const attachments = pc.attachments || EMAIL_DEFAULTS.attachments;
  const mode = emailMode(pc);
  const conversation = mode === "conversation";
  const requireAuthentication = conversation || (pc.requireAuthentication || EMAIL_DEFAULTS.requireAuthentication) !== "false";
  const disclosureOn = aiDisclosureEnabled(pc);
  const honorReplyTo = (pc.honorReplyTo || EMAIL_DEFAULTS.honorReplyTo) === "true";
  // The other mode's keys go at once: the backend refuses them, and a hidden
  // field must not keep a value the user can no longer see.
  const setMode = (next: string) => onChange(withEmailFixedKeys({ ...pc, mode: next }));
  const setDisclosure = (on: boolean) => {
    const next = { ...pc };
    if (on) delete next.aiDisclosure;
    else next.aiDisclosure = "";
    onChange(next);
  };
  const correlateThreads = (pc.correlateThreads || EMAIL_DEFAULTS.correlateThreads) === "true";
  const stripQuotedHistory = (pc.stripQuotedHistory || EMAIL_DEFAULTS.stripQuotedHistory) !== "false";

  return (
    <div className="space-y-4" data-testid="email-platform-fields">
      <div className="space-y-1">
        <label htmlFor={`${uid}-mode`} className={labelClass}>{t("emailChannel.mode", "Mode")}</label>
        <select id={`${uid}-mode`} data-testid="email-mode" className={selectClass} value={mode} onChange={(e) => setMode(e.target.value)}>
          {EMAIL_MODES.map((m) => (
            <option key={m} value={m}>
              {m === "trigger" ? t("emailChannel.modeTrigger", "Trigger: mail starts agent turns, nothing is sent back") : t("emailChannel.modeConversation", "Conversation: the agent replies in the thread")}
            </option>
          ))}
        </select>
      </div>
      <div className="flex items-start gap-2 rounded-lg border border-primary/20 bg-primary/5 p-3 text-xs" data-testid="email-mode-note">
        <Info className="mt-0.5 h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
        <div className="space-y-0.5">
          <p>
            {conversation
              ? t(
                  "emailChannel.modeNoteConversation",
                  "Conversation mode: the agent replies in the mail thread over SMTP — only to the sender whose address DKIM/DMARC authenticated, never to Cc, never to anyone the model names.",
                )
              : t("emailChannel.modeNote", "Trigger mode: mail starts agent turns; no reply is ever sent.")}
          </p>
          <p className="font-mono text-muted-foreground">mode: {mode} · transport: imap</p>
        </div>
      </div>

      {isOpenTrigger(pc) && (
        <div
          className="flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/5 p-3 text-xs"
          role="alert"
          data-testid="email-open-trigger-warning"
        >
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden="true" />
          <span>
            {t(
              "emailChannel.openTriggerWarning",
              "Anyone on the internet can make this agent run: there is no sender allowlist, and mail that fails DKIM/DMARC alignment is processed. Add allowed senders or require authentication.",
            )}
          </span>
        </div>
      )}

      {/* Mailbox */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div className="space-y-1">
          <label htmlFor={`${uid}-connection`} className={labelClass}>{t("emailChannel.connection", "Connection")}</label>
          <Input
            id={`${uid}-connection`}
            data-testid="email-connection"
            list={`${uid}-connection-names`}
            value={pc.connection ?? ""}
            onChange={(e) => set("connection", e.target.value)}
            placeholder="exchange-mailbox"
          />
          <datalist id={`${uid}-connection-names`}>
            {connectionNames.map((n) => (<option key={n} value={n} />))}
          </datalist>
          <p className={hintClass}>
            {t("emailChannel.connectionHint", "Required. The name of an OAUTH2_CLIENT_CREDENTIALS (XOAUTH2) or BASIC (app password) connection whose allowlist contains imaps://<host> or imap://<host>:143.")}
          </p>
          {problemFor("connectionRequired", "connectionReference")}
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-address`} className={labelClass}>{t("emailChannel.address", "Mailbox address")}</label>
          <Input id={`${uid}-address`} data-testid="email-address" value={pc.address ?? ""} onChange={(e) => set("address", e.target.value)} placeholder="invoices@example.com" />
          <p className={hintClass}>{t("emailChannel.addressHint", "Required. Each mailbox can belong to one integration only.")}</p>
          {problemFor("addressRequired", "addressInvalid")}
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-host`} className={labelClass}>{t("emailChannel.host", "IMAP host")}</label>
          <Input id={`${uid}-host`} data-testid="email-host" value={pc.host ?? ""} onChange={(e) => set("host", e.target.value)} placeholder="outlook.office365.com" />
          {problemFor("hostRequired", "hostIpLiteral", "hostInvalid")}
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-port`} className={labelClass}>{t("emailChannel.port", "Port")}</label>
          <select id={`${uid}-port`} data-testid="email-port" className={selectClass} value={pc.port || EMAIL_DEFAULTS.port} onChange={(e) => set("port", e.target.value)}>
            {EMAIL_PORTS.map((p) => (
              <option key={p} value={p}>
                {p === "993" ? "993 (TLS)" : "143 (STARTTLS)"}
              </option>
            ))}
          </select>
          {problemFor("port")}
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-folder`} className={labelClass}>{t("emailChannel.folder", "Folder")}</label>
          <Input id={`${uid}-folder`} data-testid="email-folder" value={pc.folder ?? ""} onChange={(e) => set("folder", e.target.value)} placeholder={EMAIL_DEFAULTS.folder} />
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-processed`} className={labelClass}>{t("emailChannel.processedAction", "After processing")}</label>
          <div className="flex gap-2">
            <select
              id={`${uid}-processed`}
              data-testid="email-processed-action"
              className={selectClass}
              value={isMove ? "move" : "flag"}
              onChange={(e) => set("processedAction", e.target.value === "move" ? "move:" : "flag")}
            >
              <option value="flag">{t("emailChannel.processedFlag", "Flag it ($EddiProcessed)")}</option>
              <option value="move">{t("emailChannel.processedMove", "Move it to a folder")}</option>
            </select>
            {isMove && (
              <Input
                aria-label={t("emailChannel.moveFolder", "Target folder")}
                data-testid="email-move-folder"
                value={processedAction.slice("move:".length)}
                onChange={(e) => set("processedAction", `move:${e.target.value}`)}
                placeholder="Processed"
              />
            )}
          </div>
          <p className={hintClass}>{t("emailChannel.processedHint", "Mail is never deleted.")}</p>
          {problemFor("moveFolderRequired")}
        </div>
      </div>

      {/* Who may trigger */}
      <div className="space-y-3 border-t border-border/50 pt-3">
        <p className="text-xs font-semibold text-muted-foreground">{t("emailChannel.sendersSection", "Senders")}</p>
        <label className="flex items-start gap-2 text-xs">
          <input
            type="checkbox"
            data-testid="email-require-authentication"
            className="mt-0.5 h-3.5 w-3.5 rounded border-input accent-primary"
            checked={requireAuthentication}
            disabled={conversation}
            onChange={(e) => set("requireAuthentication", e.target.checked ? "true" : "false")}
          />
          <span>
            <span className="font-medium">{t("emailChannel.requireAuthentication", "Require authenticated senders (DKIM/DMARC)")}</span>
            <span className="block text-[10px] text-muted-foreground">
              {conversation
                ? t("emailChannel.requireAuthenticationConversationHint", "Always on in conversation mode: EDDI never answers a sender whose address may be forged.")
                : t("emailChannel.requireAuthenticationHint", "When off, mail that fails DKIM/DMARC alignment still runs a turn, under a one-off anonymous identity with no memory.")}
            </span>
          </span>
        </label>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div className="space-y-1">
            <label htmlFor={`${uid}-allow`} className={labelClass}>{t("emailChannel.senderAllowlist", "Allowed senders")}</label>
            <Input id={`${uid}-allow`} data-testid="email-sender-allowlist" value={pc.senderAllowlist ?? ""} onChange={(e) => set("senderAllowlist", e.target.value)} placeholder="*@example.com, ap@partner.com" />
            <p className={hintClass}>{t("emailChannel.senderAllowlistHint", "Recommended. Comma-separated: user@domain, *@domain or *@*.domain.")}</p>
            {problemFor("senderAllowlist")}
          </div>
          <div className="space-y-1">
            <label htmlFor={`${uid}-block`} className={labelClass}>{t("emailChannel.senderBlocklist", "Blocked senders")}</label>
            <Input id={`${uid}-block`} data-testid="email-sender-blocklist" value={pc.senderBlocklist ?? ""} onChange={(e) => set("senderBlocklist", e.target.value)} placeholder="*@*.spam.example" />
            {problemFor("senderBlocklist")}
          </div>
          <div className="space-y-1">
            <label htmlFor={`${uid}-identity`} className={labelClass}>{t("emailChannel.identityMode", "Identity")}</label>
            <select id={`${uid}-identity`} data-testid="email-identity-mode" className={selectClass} value={pc.identityMode || EMAIL_DEFAULTS.identityMode} onChange={(e) => set("identityMode", e.target.value)}>
              {EMAIL_IDENTITY_MODES.map((m) => (
                <option key={m} value={m}>
                  {m === "perSender" ? t("emailChannel.identityPerSender", "One user per sender") : t("emailChannel.identityIntegration", "One user for the whole integration")}
                </option>
              ))}
            </select>
          </div>
          {!conversation && (
          <label className="flex items-start gap-2 text-xs sm:pt-5">
            <input
              type="checkbox"
              data-testid="email-correlate-threads"
              className="mt-0.5 h-3.5 w-3.5 rounded border-input accent-primary"
              checked={correlateThreads}
              onChange={(e) => set("correlateThreads", e.target.checked ? "true" : "false")}
            />
            <span>
              <span className="font-medium">{t("emailChannel.correlateThreads", "Continue threads")}</span>
              <span className="block text-[10px] text-muted-foreground">
                {t("emailChannel.correlateThreadsHint", "A reply in a thread from the same authenticated sender continues that sender's conversation; anyone else starts their own.")}
              </span>
            </span>
          </label>
          )}
        </div>
      </div>

      {conversation && (
        <div className="space-y-3 border-t border-border/50 pt-3" data-testid="email-replies-section">
          <p className="text-xs font-semibold text-muted-foreground">{t("emailChannel.repliesSection", "Replies")}</p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="space-y-1">
              <label htmlFor={`${uid}-smtp-host`} className={labelClass}>{t("emailChannel.smtpHost", "SMTP host")}</label>
              <Input id={`${uid}-smtp-host`} data-testid="email-smtp-host" value={pc.smtpHost ?? ""} onChange={(e) => set("smtpHost", e.target.value)} placeholder="smtp.office365.com" />
              <p className={hintClass}>
                {t("emailChannel.smtpHostHint", "Required. The connection's allowlist must also contain smtp://<host>:587 or smtps://<host>; the same credential logs in.")}
              </p>
              {problemFor("smtpHostRequired", "smtpHostIpLiteral", "smtpHostInvalid")}
            </div>
            <div className="space-y-1">
              <label htmlFor={`${uid}-smtp-port`} className={labelClass}>{t("emailChannel.smtpPort", "SMTP port")}</label>
              <select id={`${uid}-smtp-port`} data-testid="email-smtp-port" className={selectClass} value={pc.smtpPort || EMAIL_DEFAULTS.smtpPort} onChange={(e) => set("smtpPort", e.target.value)}>
                {EMAIL_SMTP_PORTS.map((p) => (
                  <option key={p} value={p}>
                    {p === "587" ? "587 (STARTTLS)" : "465 (TLS)"}
                  </option>
                ))}
              </select>
              {problemFor("smtpPort")}
            </div>
            <div className="space-y-1">
              <label htmlFor={`${uid}-from-name`} className={labelClass}>{t("emailChannel.fromName", "Sender name")}</label>
              <Input id={`${uid}-from-name`} data-testid="email-from-name" value={pc.fromName ?? ""} onChange={(e) => set("fromName", e.target.value)} placeholder="Contoso Support" />
              <p className={hintClass}>{t("emailChannel.fromNameHint", "Shown next to the mailbox address on every reply.")}</p>
              {problemFor("fromNameInvalid")}
            </div>
            <div className="space-y-1">
              <label htmlFor={`${uid}-participants`} className={labelClass}>{t("emailChannel.threadParticipants", "Who may continue a thread")}</label>
              <select
                id={`${uid}-participants`}
                data-testid="email-thread-participants"
                className={selectClass}
                value={pc.threadParticipants || EMAIL_DEFAULTS.threadParticipants}
                onChange={(e) => set("threadParticipants", e.target.value)}
              >
                {EMAIL_THREAD_PARTICIPANTS.map((m) => (
                  <option key={m} value={m}>
                    {m === "originalSenderOnly"
                      ? t("emailChannel.participantsOriginal", "Only the sender who started it")
                      : t("emailChannel.participantsAny", "Also authenticated addresses its replies went to")}
                  </option>
                ))}
              </select>
              <p className={hintClass}>
                {t("emailChannel.threadParticipantsHint", "Anyone else who answers with the thread's Message-ID (they travel in every forward) starts a conversation of their own.")}
              </p>
            </div>
            <div className="space-y-1">
              <label htmlFor={`${uid}-max-replies`} className={labelClass}>{t("emailChannel.maxRepliesPerThreadPerHour", "Replies per thread per hour")}</label>
              <Input
                id={`${uid}-max-replies`}
                data-testid="email-max-replies"
                inputMode="numeric"
                value={pc.maxRepliesPerThreadPerHour ?? ""}
                onChange={(e) => set("maxRepliesPerThreadPerHour", e.target.value)}
                placeholder={EMAIL_DEFAULTS.maxRepliesPerThreadPerHour}
              />
              <p className={hintClass}>{t("emailChannel.maxRepliesHint", "A loop brake: further replies in that thread wait for the next hour, and the owners are told.")}</p>
              {problemFor("maxRepliesPerThreadPerHour")}
            </div>
          </div>
          <label className="flex items-start gap-2 text-xs">
            <input
              type="checkbox"
              data-testid="email-honor-reply-to"
              className="mt-0.5 h-3.5 w-3.5 rounded border-input accent-primary"
              checked={honorReplyTo}
              onChange={(e) => set("honorReplyTo", e.target.checked ? "true" : "")}
            />
            <span>
              <span className="font-medium">{t("emailChannel.honorReplyTo", "Reply to the Reply-To address")}</span>
              <span className="block text-[10px] text-muted-foreground">
                {t("emailChannel.honorReplyToHint", "Only for a web form that sends on behalf of its visitors. Otherwise replies always go to the authenticated From.")}
              </span>
            </span>
          </label>
          {honorReplyTo && (
            <div className="flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/5 p-3 text-xs" role="alert" data-testid="email-honor-reply-to-warning">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden="true" />
              <span>
                {t(
                  "emailChannel.honorReplyToWarning",
                  "DMARC does not authenticate the Reply-To: anyone who can send authenticated mail from an allowed domain decides where the agent's reply goes.",
                )}
              </span>
            </div>
          )}
          <label className="flex items-start gap-2 text-xs">
            <input
              type="checkbox"
              data-testid="email-ai-disclosure-enabled"
              className="mt-0.5 h-3.5 w-3.5 rounded border-input accent-primary"
              checked={disclosureOn}
              onChange={(e) => setDisclosure(e.target.checked)}
            />
            <span className="font-medium">{t("emailChannel.aiDisclosureEnabled", "Say in every reply that an AI wrote it")}</span>
          </label>
          {disclosureOn ? (
            <div className="space-y-1">
              <label htmlFor={`${uid}-disclosure`} className={labelClass}>{t("emailChannel.aiDisclosure", "AI disclosure")}</label>
              <Input
                id={`${uid}-disclosure`}
                data-testid="email-ai-disclosure"
                value={pc.aiDisclosure ?? ""}
                onChange={(e) => set("aiDisclosure", e.target.value)}
                placeholder={EMAIL_DEFAULT_AI_DISCLOSURE}
              />
              <p className={hintClass}>{t("emailChannel.aiDisclosureHint", "Appended after the reply. Empty uses the default shown.")}</p>
              {problemFor("aiDisclosureTooLong")}
            </div>
          ) : (
            <div className="flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/5 p-3 text-xs" role="alert" data-testid="email-ai-disclosure-warning">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden="true" />
              <span>
                {t(
                  "emailChannel.aiDisclosureWarning",
                  "Replies will not say that an AI wrote them. Transparency rules such as the EU AI Act expect people to know they are talking to an AI.",
                )}
              </span>
            </div>
          )}
          <div className="space-y-1">
            <label htmlFor={`${uid}-reply-template`} className={labelClass}>{t("emailChannel.replyTemplate", "Reply template")}</label>
            <textarea
              id={`${uid}-reply-template`}
              data-testid="email-reply-template"
              rows={3}
              className="w-full rounded-lg border border-border bg-background px-3 py-2 font-mono text-xs text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary"
              value={pc.replyTemplate ?? ""}
              onChange={(e) => set("replyTemplate", e.target.value)}
              placeholder={EMAIL_DEFAULT_REPLY_TEMPLATE}
            />
            <p className={hintClass}>
              {t("emailChannel.replyTemplateHint", "Wraps the agent's answer, which is inserted as text and never run as a template. Variables:")}{" "}
              <span className="font-mono">{EMAIL_REPLY_TEMPLATE_VARIABLES.map((v) => `{${v}}`).join(" ")}</span>
            </p>
            {problemFor("replyTemplateTooLong")}
          </div>
        </div>
      )}

      <details open={advancedOpen} className="group space-y-3 border-t border-border/50 pt-3" data-testid="email-advanced">
        <summary className="cursor-pointer text-xs font-semibold text-muted-foreground">{t("emailChannel.advancedSection", "Content, attachments and limits")}</summary>
        <div className="mt-3 space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="space-y-1">
              <label htmlFor={`${uid}-attachments`} className={labelClass}>{t("emailChannel.attachments", "Attachments")}</label>
              <select id={`${uid}-attachments`} data-testid="email-attachments" className={selectClass} value={attachments} onChange={(e) => set("attachments", e.target.value)}>
                {EMAIL_ATTACHMENT_MODES.map((m) => (
                  <option key={m} value={m}>
                    {m === "drop"
                      ? t("emailChannel.attachmentsDrop", "Drop them")
                      : m === "metadata"
                        ? t("emailChannel.attachmentsMetadata", "Pass name, type and size only")
                        : t("emailChannel.attachmentsStore", "Store them")}
                  </option>
                ))}
              </select>
              <p className={hintClass}>{t("emailChannel.attachmentsHint", "Executables are never stored and archives are never expanded.")}</p>
            </div>
            <div className="space-y-1">
              <label htmlFor={`${uid}-mime`} className={labelClass}>{t("emailChannel.attachmentMimeTypes", "Allowed attachment types")}</label>
              <Input id={`${uid}-mime`} data-testid="email-attachment-mime-types" value={pc.attachmentMimeTypes ?? ""} onChange={(e) => set("attachmentMimeTypes", e.target.value)} placeholder="application/pdf, image/*" />
              <p className={hintClass}>{t("emailChannel.attachmentMimeTypesHint", "Comma-separated type/subtype or type/*. Required when attachments are stored.")}</p>
              {problemFor("mimeTypesRequired", "mimeTypesInvalid")}
            </div>
            <div className="space-y-1">
              <label htmlFor={`${uid}-maxbytes`} className={labelClass}>{t("emailChannel.maxMessageBytes", "Maximum message size (bytes)")}</label>
              <Input id={`${uid}-maxbytes`} data-testid="email-max-message-bytes" inputMode="numeric" value={pc.maxMessageBytes ?? ""} onChange={(e) => set("maxMessageBytes", e.target.value)} placeholder={EMAIL_DEFAULTS.maxMessageBytes} />
              {problemFor("maxMessageBytes")}
            </div>
            <div className="space-y-1">
              <label htmlFor={`${uid}-poll`} className={labelClass}>{t("emailChannel.pollSeconds", "Poll interval (seconds)")}</label>
              <Input id={`${uid}-poll`} data-testid="email-poll-seconds" inputMode="numeric" value={pc.pollSeconds ?? ""} onChange={(e) => set("pollSeconds", e.target.value)} placeholder={EMAIL_DEFAULTS.pollSeconds} />
              {problemFor("pollSeconds")}
            </div>
          </div>
          <label className="flex items-start gap-2 text-xs">
            <input
              type="checkbox"
              data-testid="email-strip-quoted-history"
              className="mt-0.5 h-3.5 w-3.5 rounded border-input accent-primary"
              checked={stripQuotedHistory}
              onChange={(e) => set("stripQuotedHistory", e.target.checked ? "true" : "false")}
            />
            <span className="font-medium">{t("emailChannel.stripQuotedHistory", "Remove quoted earlier messages from the text")}</span>
          </label>
          <div className="space-y-1">
            <label htmlFor={`${uid}-template`} className={labelClass}>{t("emailChannel.inputTemplate", "Input template")}</label>
            <textarea
              id={`${uid}-template`}
              data-testid="email-input-template"
              rows={5}
              className="w-full rounded-lg border border-border bg-background px-3 py-2 font-mono text-xs text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary"
              value={pc.inputTemplate ?? ""}
              onChange={(e) => set("inputTemplate", e.target.value)}
              placeholder={EMAIL_DEFAULT_INPUT_TEMPLATE}
            />
            <p className={hintClass}>
              {t("emailChannel.inputTemplateHint", "The turn's input text; empty uses the default shown. Variables:")}{" "}
              <span className="font-mono">{EMAIL_TEMPLATE_VARIABLES.map((v) => `{${v}}`).join(" ")}</span>
            </p>
            {problemFor("inputTemplateTooLong")}
          </div>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="space-y-1">
              <label htmlFor={`${uid}-turns`} className={labelClass}>{t("emailChannel.maxTurnsPerSenderPerDay", "Turns per sender per day")}</label>
              <Input id={`${uid}-turns`} data-testid="email-max-turns" inputMode="numeric" value={pc.maxTurnsPerSenderPerDay ?? ""} onChange={(e) => set("maxTurnsPerSenderPerDay", e.target.value)} placeholder={EMAIL_DEFAULTS.maxTurnsPerSenderPerDay} />
              <p className={hintClass}>{t("emailChannel.maxTurnsHint", "0 means no limit.")}</p>
              {problemFor("maxTurnsPerSenderPerDay")}
            </div>
            <div className="space-y-1">
              <label htmlFor={`${uid}-budget`} className={labelClass}>{t("emailChannel.dailyCostBudgetUsd", "Daily cost budget (USD)")}</label>
              <Input id={`${uid}-budget`} data-testid="email-daily-budget" inputMode="decimal" value={pc.dailyCostBudgetUsd ?? ""} onChange={(e) => set("dailyCostBudgetUsd", e.target.value)} placeholder="5.00" />
              <p className={hintClass}>{t("emailChannel.dailyCostBudgetHint", "Empty means no budget. Mail over budget stays in the mailbox for the next day.")}</p>
              {problemFor("dailyCostBudgetUsd")}
            </div>
          </div>
          <div className="space-y-1">
            <label htmlFor={`${uid}-owners`} className={labelClass}>{t("emailChannel.ownerNotifyAddresses", "Owner notification addresses")}</label>
            <Input id={`${uid}-owners`} data-testid="email-owner-notify" value={pc.ownerNotifyAddresses ?? ""} onChange={(e) => set("ownerNotifyAddresses", e.target.value)} placeholder="ops@example.com" />
            <p className={hintClass}>{t("emailChannel.ownerNotifyHint", "Up to 5, comma-separated. They get one email a day when the integration stops on a budget, if the system mailer is configured.")}</p>
            {problemFor("ownerNotifyTooMany", "ownerNotifyInvalid")}
          </div>
        </div>
      </details>
    </div>
  );
}

/** Setup and security guidance for an email integration, shown on its detail page. */
export function EmailSecurityNotes({ conversation = false }: { conversation?: boolean }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-2 text-xs text-muted-foreground" data-testid="email-security-notes">
      {conversation && (
        <p className="flex items-start gap-2" data-testid="email-dkim-note">
          <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
          <span>
            {t(
              "emailChannel.noteDkim",
              "Replies need a mail server that signs outgoing mail with DKIM for the mailbox's domain, and a DMARC record for that domain — EDDI does not sign. Without them replies land in spam or are rejected. Exchange Online and most hosters sign submitted mail once DKIM is enabled for the domain.",
            )}
          </span>
        </p>
      )}
      {conversation && (
        <p className="flex items-start gap-2" data-testid="email-bounce-note">
          <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
          <span>
            {t(
              "emailChannel.noteBounces",
              "A reply that bounces permanently puts the address on this integration's suppression list, and EDDI sends it nothing more until an administrator clears it (POST /channelstore/channels/<id>/email-suppressions/clear).",
            )}
          </span>
        </p>
      )}
      <p className="flex items-start gap-2">
        <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
        <span>{t("emailChannel.noteAllowlist", "Use a sender allowlist. Without one, and with authentication not required, anyone who can send mail can make the agent run.")}</span>
      </p>
      <p className="flex items-start gap-2" data-testid="email-exchange-note">
        <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
        <span>
          {t(
            "emailChannel.noteExchange",
            "Exchange Online: use an OAUTH2_CLIENT_CREDENTIALS connection with scope https://outlook.office365.com/.default and allowlist imaps://outlook.office365.com, and grant the app's service principal access to this mailbox only (Add-MailboxPermission). The IMAP.AccessAsApp permission alone does not limit which mailboxes the app can open; the per-mailbox permission is the restriction. Other hosts: a BASIC connection with an app password.",
          )}
        </span>
      </p>
      <p className="flex items-start gap-2">
        <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
        <span>{t("emailChannel.noteToolApprovals", "Mail content is untrusted input. If the target agent has tools that change things, require approval for them (hitlConfig.toolApprovals).")}</span>
      </p>
    </div>
  );
}
