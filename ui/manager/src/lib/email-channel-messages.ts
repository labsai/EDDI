import type { TFunction } from "i18next";
import { toast } from "sonner";
import {
  EMAIL_MAX_ALIASES,
  EMAIL_MAX_MENTIONS_PER_TARGET,
  EMAIL_MAX_AI_DISCLOSURE_CHARS,
  EMAIL_MAX_FROM_NAME_CHARS,
  EMAIL_MAX_OWNER_ADDRESSES,
  EMAIL_MAX_REPLY_TEMPLATE_CHARS,
  EMAIL_MAX_TEMPLATE_CHARS,
  type EmailProblem,
} from "./email-channel";

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
    case "smtpHostRequired":
      return t("emailChannel.problem.smtpHostRequired", "Enter the SMTP server's host name: replies are sent through it.");
    case "smtpHostIpLiteral":
      return t("emailChannel.problem.smtpHostIpLiteral", "Use a DNS name for the SMTP server, not an IP address: the TLS certificate is checked against it.");
    case "smtpHostInvalid":
      return t("emailChannel.problem.smtpHostInvalid", "This is not a valid SMTP host name.");
    case "smtpPort":
      return t("emailChannel.problem.smtpPort", "The SMTP port must be 587 (STARTTLS) or 465 (TLS).");
    case "fromNameInvalid":
      return t("emailChannel.problem.fromNameInvalid", {
        max: EMAIL_MAX_FROM_NAME_CHARS,
        defaultValue: "The sender name may be at most {{max}} characters, without line breaks.",
      });
    case "replyTemplateTooLong":
      return t("emailChannel.problem.replyTemplateTooLong", {
        max: EMAIL_MAX_REPLY_TEMPLATE_CHARS,
        defaultValue: "The reply template may be at most {{max}} characters.",
      });
    case "aiDisclosureTooLong":
      return t("emailChannel.problem.aiDisclosureTooLong", {
        max: EMAIL_MAX_AI_DISCLOSURE_CHARS,
        defaultValue: "The AI disclosure may be at most {{max}} characters.",
      });
    case "maxRepliesPerThreadPerHour":
      return t("emailChannel.problem.maxRepliesPerThreadPerHour", "The reply limit must be a whole number from 1 to 1000.");
    case "aliasesTooMany":
      return t("emailChannel.problem.aliasesTooMany", {
        max: EMAIL_MAX_ALIASES,
        defaultValue: "Name at most {{max}} aliases.",
      });
    case "aliasesInvalid":
      return t("emailChannel.problem.aliasesInvalid", "Each alias must be a valid email address.");
    case "aliasIsAddress":
      return t("emailChannel.problem.aliasIsAddress", "The mailbox address itself is not an alias; list only additional addresses.");
    case "mentionInvalid":
      return t("emailChannel.problem.mentionInvalid", "A mention is 1–40 letters, digits, _ or -, starting with a letter or digit, written without the @.");
    case "mentionsTooMany":
      return t("emailChannel.problem.mentionsTooMany", {
        max: EMAIL_MAX_MENTIONS_PER_TARGET,
        defaultValue: "A target can have at most {{max}} mentions.",
      });
    case "mentionDuplicate":
      return t("emailChannel.problem.mentionDuplicate", "Each mention can address one target only.");
    case "targetAddressInvalid":
      return t("emailChannel.problem.targetAddressInvalid", "Each target address must be a valid email address.");
    case "targetAddressNotReceived":
      return t(
        "emailChannel.problem.targetAddressNotReceived",
        "A target address must be one of the aliases or a plus-address of the mailbox address, so the mailbox receives it.",
      );
    case "targetAddressDuplicate":
      return t("emailChannel.problem.targetAddressDuplicate", "Each target address can address one target only.");
    case "targetAllowedSendersInvalid":
      return t("emailChannel.problem.targetAllowedSendersInvalid", "Each allowed sender of a target must be user@domain, *@domain or *@*.domain.");
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
    case "email.ai-disclosure-disabled":
      return t(
        "emailChannel.warning.aiDisclosureDisabled",
        "Saved, but replies no longer say that an AI wrote them. Transparency rules such as the EU AI Act expect people to know they are talking to an AI.",
      );
    case "email.honor-reply-to":
      return t(
        "emailChannel.warning.honorReplyTo",
        "Saved, but replies go to the Reply-To address, which DMARC does not authenticate: whoever can send authenticated mail from an allowed domain decides where the reply goes.",
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
