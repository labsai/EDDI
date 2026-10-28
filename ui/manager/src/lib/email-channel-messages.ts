import type { TFunction } from "i18next";
import { toast } from "sonner";
import { EMAIL_MAX_OWNER_ADDRESSES, EMAIL_MAX_TEMPLATE_CHARS, type EmailProblem } from "./email-channel";

/**
 * The translated sentence for one email-settings problem. Literal keys on
 * purpose — `npm run i18n:check` reads `t()` calls statically.
 */
export function emailProblemMessage(problem: EmailProblem, t: TFunction): string {
  switch (problem) {
    case "connectionRequired":
      return t("emailChannel.problem.connectionRequired", "Name the connection that holds the mailbox credential.");
    case "connectionReference":
      return t("emailChannel.problem.connectionReference", "Enter the connection's name only, not a ${…} reference.");
    case "hostRequired":
      return t("emailChannel.problem.hostRequired", "Enter the IMAP server's host name.");
    case "hostIpLiteral":
      return t("emailChannel.problem.hostIpLiteral", "Use a DNS name, not an IP address: the TLS certificate is checked against it.");
    case "hostInvalid":
      return t("emailChannel.problem.hostInvalid", "This is not a valid host name.");
    case "addressRequired":
      return t("emailChannel.problem.addressRequired", "Enter the mailbox address.");
    case "addressInvalid":
      return t("emailChannel.problem.addressInvalid", "This is not a valid email address.");
    case "port":
      return t("emailChannel.problem.port", "The port must be 993 (TLS) or 143 (STARTTLS).");
    case "moveFolderRequired":
      return t("emailChannel.problem.moveFolderRequired", "Name the folder processed mail is moved to.");
    case "pollSeconds":
      return t("emailChannel.problem.pollSeconds", "Poll every 60 to 86400 seconds (whole seconds).");
    case "senderAllowlist":
      return t("emailChannel.problem.senderAllowlist", "Each allowed sender must be user@domain, *@domain or *@*.domain.");
    case "senderBlocklist":
      return t("emailChannel.problem.senderBlocklist", "Each blocked sender must be user@domain, *@domain or *@*.domain.");
    case "mimeTypesRequired":
      return t("emailChannel.problem.mimeTypesRequired", "Storing attachments needs at least one allowed MIME type.");
    case "mimeTypesInvalid":
      return t("emailChannel.problem.mimeTypesInvalid", "Each MIME type must be type/subtype or type/*.");
    case "maxMessageBytes":
      return t("emailChannel.problem.maxMessageBytes", "The size limit must be a whole number from 1024 to 52428800 bytes.");
    case "inputTemplateTooLong":
      return t("emailChannel.problem.inputTemplateTooLong", {
        max: EMAIL_MAX_TEMPLATE_CHARS,
        defaultValue: "The input template may be at most {{max}} characters.",
      });
    case "maxTurnsPerSenderPerDay":
      return t("emailChannel.problem.maxTurnsPerSenderPerDay", "The daily turn limit must be a whole number from 0 to 100000.");
    case "dailyCostBudgetUsd":
      return t("emailChannel.problem.dailyCostBudgetUsd", "The daily budget must be a number above 0, or empty for no budget.");
    case "ownerNotifyTooMany":
      return t("emailChannel.problem.ownerNotifyTooMany", {
        max: EMAIL_MAX_OWNER_ADDRESSES,
        defaultValue: "Name at most {{max}} owner addresses.",
      });
    case "ownerNotifyInvalid":
      return t("emailChannel.problem.ownerNotifyInvalid", "Each owner address must be a valid email address.");
  }
}

/** The translated text of one `X-Eddi-Warnings` code; an unknown code is shown as it is. */
export function channelWarningMessage(code: string, t: TFunction): string {
  switch (code) {
    case "email.open-trigger":
      return t(
        "emailChannel.warning.openTrigger",
        "Saved, but anyone on the internet can make this agent run: there is no sender allowlist and unauthenticated mail is processed.",
      );
    case "email.tools-without-approvals":
      return t(
        "emailChannel.warning.toolsWithoutApprovals",
        "Saved, but an agent this integration targets has tools and no tool approvals. Mail content is untrusted input: add hitlConfig.toolApprovals for tools that change things.",
      );
    default:
      return code;
  }
}

/** One warning toast per save-time warning code. */
export function toastChannelWarnings(warnings: readonly string[], t: TFunction): void {
  for (const code of warnings) {
    toast.warning(channelWarningMessage(code, t), { id: `channel-warning-${code}` });
  }
}
