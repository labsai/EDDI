/* ──────────────────────────────────────────────
   EDDI Chat — tiny i18n
   One flat string table per locale. English is the source of truth and the
   fallback for any key a locale does not define yet, so a language can be
   added (or completed) one string at a time: add an object to LOCALES.

   The locale is process-wide and chosen once at startup (`?lang=` first, then
   `navigator.language`), which is why plain functions outside React — the SSE
   copy helpers, the HITL headlines — can call t() as well.
   Only text the WIDGET owns goes through here. What an agent says is never
   translated.
   ────────────────────────────────────────────── */

const en = {
  "lang.name": "English",

  // Transcript / chrome
  "transcript.label": "Conversation",
  "transcript.starting": "Starting conversation…",
  "transcript.ready": "Say hello to get started.",
  "scroll.bottom": "Scroll to bottom",
  "message.noResponse": "No response",
  "speaker.you": "You said:",
  "speaker.agent": "{name} said:",
  "speaker.agentDefault": "Assistant",
  "copy.message": "Copy message",
  "copy.code": "Copy code",
  "copy.done": "Copied",
  "copy.failed": "Could not copy",
  "theme.toggle": "Toggle theme",

  // Starting
  "start.retry": "Try again",
  "start.notFoundEnv":
    "⚠️ This agent is not available in the \"{env}\" environment. It may not be deployed there.",
  "start.notFound": "⚠️ This agent is not available. It may not be deployed.",
  "start.unauthorized":
    "⚠️ Please sign in to chat with this agent, then try again.",
  "start.forbidden":
    "⚠️ Access to this agent was refused. If this chat is embedded in another site, that site may not be allowed to use it — ask its owner.",
  "start.badRequest":
    "⚠️ The conversation could not be started. Check the address — the environment must be \"production\" or \"test\".",
  "start.generic": "⚠️ The conversation could not be started. Please try again.",

  // Sending
  "send.conflict":
    "⚠️ Your message was not sent — this conversation is busy or waiting on a decision. Try again in a moment.",
  "send.awaiting":
    "⚠️ Your message was not sent — this conversation is waiting on a decision.",
  "send.notSent": "⚠️ Your message was not sent — {message}",
  "send.notFound":
    "⚠️ Your message was not sent — this agent is not ready to answer (it may not be deployed). It is back in the message box.",
  "send.tooLarge":
    "⚠️ Your message was not sent — it or its attachment is too large. It is back in the message box.",
  "send.rateLimited":
    "⚠️ Your message was not sent — you have reached a usage limit. Wait a moment and try again.",
  "send.unavailable":
    "⚠️ Your message was not sent — the service is temporarily unavailable. Please try again shortly.",
  "send.ended":
    "⚠️ This conversation has ended. Start a new conversation to continue.",
  "send.sessionExpired":
    "⚠️ Your session has expired. Sign in again, then resend your message.",
  "send.forbidden":
    "⚠️ You are not allowed to continue this conversation. It may belong to a different user.",
  "send.failed":
    "⚠️ Your message could not be sent. It is back in the message box — please try again.",
  "send.connectionLost":
    "Connection lost — the response may be incomplete.",
  "send.cancelled": "This request was cancelled.",

  // Skipped turns
  "skipped.awaiting":
    "This conversation is waiting for a reviewer to approve the previous step. Your message was not sent.",
  "skipped.busy":
    "The agent is still working on your previous message. Your message was not sent — please try again in a moment.",
  "skipped.ended": "This conversation has ended. Your message was not sent.",
  "skipped.default": "Your message was not processed.",

  // Undo / redo / retry
  "undo.title": "Undo last message",
  "undo.notNow": "⚠️ Undo is not possible right now.",
  "undo.failed": "⚠️ Undo failed.",
  "redo.title": "Redo message",
  "redo.notNow": "⚠️ Redo is not possible right now.",
  "redo.failed": "⚠️ Redo failed.",
  "retry.nothing": "⚠️ There is nothing to retry right now.",
  "retry.failed": "⚠️ Retrying failed. You can start a new conversation instead.",

  // Recovery / ended
  "recovery.interrupted": "This request was interrupted before it finished.",
  "recovery.error": "Something went wrong on the last step.",
  "recovery.retry": "Try again",
  "recovery.restart": "Start over",
  "ended.label": "Conversation Ended",
  "ended.retired":
    "This assistant was updated. Start a new conversation to continue.",
  "ended.restart": "Start New Conversation",

  // Actions
  "action.stop": "Stop generating",
  "action.new": "New conversation",
  "action.newConfirm":
    "Start a new conversation? The current one will be closed.",
  "action.newYes": "Start new",
  "action.newCancel": "Keep this one",

  // Paused (HITL)
  "paused.title": "Waiting for approval",
  "paused.pending": "Checking the approval status…",
  "paused.cancel": "Cancel this request",
  "paused.headline.tool":
    "A reviewer must approve \"{tool}\" before I can continue.",
  "paused.headline.tools":
    "A reviewer must approve these actions before I can continue: {tools}.",
  "paused.headline.reason":
    "A reviewer must approve this step before I can continue — {reason}.",
  "paused.headline": "A reviewer must approve this step before I can continue.",
  "paused.policy.approve": "approved automatically",
  "paused.policy.reject": "rejected automatically",
  "paused.policy.abort": "cancelled automatically",
  "paused.policy.other": "decided automatically",
  "paused.deadline": "Otherwise {policy} in {remaining}.",
  "paused.deadlineAt": "Otherwise {policy} at {time}.",
  "paused.anyMoment": "any moment now",

  // Composer
  "input.placeholder": "Type a message...",
  "input.label": "Message",
  "input.send": "Send message",
  "input.attach": "Attach file",
  "input.attachBusy": "Attach file (upload in progress)",
  "input.attachLimit": "Attachment limit ({max}) reached",
  "input.secretToggle": "Secret mode",
  "input.secretOff": "Turn on secret mode — the message is hidden in the chat and masked in stored history",
  "input.secretOn": "Secret mode is on — the message is hidden in the chat and masked in stored history",
  "input.secretPlaceholder": "Enter secret value...",
  "input.secretShow": "Show secret",
  "input.secretHide": "Hide secret",
  "input.show": "Show",
  "input.hide": "Hide",
  "input.typeInstead": "Type a message instead",
  "input.sendSecret": "Send secret",

  // Attachments
  "attach.tooLarge": "{file} is too large to upload.",
  "attach.notAllowed": "You are not allowed to attach files to this conversation.",
  "attach.rejectedWhy": "{file} was rejected — {message}",
  "attach.rejected": "{file} was rejected.",
  "attach.failed": "Failed to upload {file}.",
  "attach.maxFiles": "You can attach at most {max} files per message.",
  "attach.notAttached": "Not attached: {files}",
  "attach.done": "{file} attached.",
  "attach.doneLarge":
    "{file} attached, but it is too large to send directly — the assistant may not be able to read it.",
  "attach.removed": "{file} removed.",
  "attach.remove": "Remove {file}",
  "attach.removeNth": "Remove {file} ({n} of {total})",
  "attach.uploading": "Uploading",
  "attach.chipLarge": "too large to send directly",
  "attach.largeLine": "📎 {file} — too large to send directly",
} as const;

export type MessageKey = keyof typeof en;
type Table = Partial<Record<MessageKey, string>>;

const de: Table = {
  "lang.name": "Deutsch",
  "transcript.label": "Unterhaltung",
  "transcript.starting": "Unterhaltung wird gestartet…",
  "transcript.ready": "Sagen Sie Hallo, um zu beginnen.",
  "scroll.bottom": "Nach unten scrollen",
  "message.noResponse": "Keine Antwort",
  "speaker.you": "Sie sagten:",
  "speaker.agent": "{name} sagte:",
  "speaker.agentDefault": "Assistent",
  "copy.message": "Nachricht kopieren",
  "copy.code": "Code kopieren",
  "copy.done": "Kopiert",
  "copy.failed": "Kopieren fehlgeschlagen",
  "theme.toggle": "Design wechseln",
  "start.retry": "Erneut versuchen",
  "start.notFoundEnv":
    "⚠️ Dieser Agent ist in der Umgebung „{env}“ nicht verfügbar. Er ist dort möglicherweise nicht bereitgestellt.",
  "start.notFound":
    "⚠️ Dieser Agent ist nicht verfügbar. Er ist möglicherweise nicht bereitgestellt.",
  "start.unauthorized":
    "⚠️ Bitte melden Sie sich an, um mit diesem Agenten zu chatten, und versuchen Sie es erneut.",
  "start.forbidden":
    "⚠️ Der Zugriff auf diesen Agenten wurde verweigert. Ist dieser Chat in eine andere Website eingebettet, darf sie ihn möglicherweise nicht nutzen – fragen Sie deren Betreiber.",
  "start.badRequest":
    "⚠️ Die Unterhaltung konnte nicht gestartet werden. Prüfen Sie die Adresse – die Umgebung muss „production“ oder „test“ sein.",
  "start.generic":
    "⚠️ Die Unterhaltung konnte nicht gestartet werden. Bitte versuchen Sie es erneut.",
  "send.conflict":
    "⚠️ Ihre Nachricht wurde nicht gesendet – die Unterhaltung ist beschäftigt oder wartet auf eine Entscheidung. Versuchen Sie es gleich noch einmal.",
  "send.awaiting":
    "⚠️ Ihre Nachricht wurde nicht gesendet – die Unterhaltung wartet auf eine Entscheidung.",
  "send.notSent": "⚠️ Ihre Nachricht wurde nicht gesendet – {message}",
  "send.notFound":
    "⚠️ Ihre Nachricht wurde nicht gesendet – der Agent ist nicht bereit (möglicherweise nicht bereitgestellt). Der Text steht wieder im Eingabefeld.",
  "send.tooLarge":
    "⚠️ Ihre Nachricht wurde nicht gesendet – sie oder ihr Anhang ist zu groß. Der Text steht wieder im Eingabefeld.",
  "send.rateLimited":
    "⚠️ Ihre Nachricht wurde nicht gesendet – ein Nutzungslimit ist erreicht. Warten Sie einen Moment und versuchen Sie es erneut.",
  "send.unavailable":
    "⚠️ Ihre Nachricht wurde nicht gesendet – der Dienst ist vorübergehend nicht verfügbar. Bitte versuchen Sie es in Kürze erneut.",
  "send.ended":
    "⚠️ Diese Unterhaltung wurde beendet. Starten Sie eine neue Unterhaltung, um fortzufahren.",
  "send.sessionExpired":
    "⚠️ Ihre Sitzung ist abgelaufen. Melden Sie sich erneut an und senden Sie die Nachricht noch einmal.",
  "send.forbidden":
    "⚠️ Sie dürfen diese Unterhaltung nicht fortsetzen. Sie gehört möglicherweise einem anderen Benutzer.",
  "send.failed":
    "⚠️ Ihre Nachricht konnte nicht gesendet werden. Der Text steht wieder im Eingabefeld – bitte versuchen Sie es erneut.",
  "send.connectionLost":
    "Verbindung unterbrochen – die Antwort ist möglicherweise unvollständig.",
  "send.cancelled": "Diese Anfrage wurde abgebrochen.",
  "skipped.awaiting":
    "Diese Unterhaltung wartet auf die Freigabe des vorherigen Schritts durch einen Prüfer. Ihre Nachricht wurde nicht gesendet.",
  "skipped.busy":
    "Der Agent bearbeitet noch Ihre vorherige Nachricht. Ihre Nachricht wurde nicht gesendet – bitte versuchen Sie es gleich noch einmal.",
  "skipped.ended":
    "Diese Unterhaltung wurde beendet. Ihre Nachricht wurde nicht gesendet.",
  "skipped.default": "Ihre Nachricht wurde nicht verarbeitet.",
  "undo.title": "Letzte Nachricht rückgängig machen",
  "undo.notNow": "⚠️ Rückgängig ist gerade nicht möglich.",
  "undo.failed": "⚠️ Rückgängig machen fehlgeschlagen.",
  "redo.title": "Nachricht wiederherstellen",
  "redo.notNow": "⚠️ Wiederherstellen ist gerade nicht möglich.",
  "redo.failed": "⚠️ Wiederherstellen fehlgeschlagen.",
  "retry.nothing": "⚠️ Es gibt gerade nichts zu wiederholen.",
  "retry.failed":
    "⚠️ Wiederholen fehlgeschlagen. Sie können stattdessen eine neue Unterhaltung starten.",
  "recovery.interrupted":
    "Diese Anfrage wurde unterbrochen, bevor sie abgeschlossen war.",
  "recovery.error": "Im letzten Schritt ist etwas schiefgelaufen.",
  "recovery.retry": "Erneut versuchen",
  "recovery.restart": "Neu beginnen",
  "ended.label": "Unterhaltung beendet",
  "ended.retired":
    "Dieser Assistent wurde aktualisiert. Starten Sie eine neue Unterhaltung, um fortzufahren.",
  "ended.restart": "Neue Unterhaltung starten",
  "action.stop": "Generierung stoppen",
  "action.new": "Neue Unterhaltung",
  "action.newConfirm":
    "Neue Unterhaltung starten? Die aktuelle wird geschlossen.",
  "action.newYes": "Neu starten",
  "action.newCancel": "Diese behalten",
  "paused.title": "Wartet auf Freigabe",
  "paused.pending": "Freigabestatus wird geprüft…",
  "paused.cancel": "Diese Anfrage abbrechen",
  "paused.headline.tool":
    "Ein Prüfer muss „{tool}“ freigeben, bevor ich fortfahren kann.",
  "paused.headline.tools":
    "Ein Prüfer muss diese Aktionen freigeben, bevor ich fortfahren kann: {tools}.",
  "paused.headline.reason":
    "Ein Prüfer muss diesen Schritt freigeben, bevor ich fortfahren kann – {reason}.",
  "paused.headline":
    "Ein Prüfer muss diesen Schritt freigeben, bevor ich fortfahren kann.",
  "paused.policy.approve": "automatisch freigegeben",
  "paused.policy.reject": "automatisch abgelehnt",
  "paused.policy.abort": "automatisch abgebrochen",
  "paused.policy.other": "automatisch entschieden",
  "paused.deadline": "Andernfalls wird {policy} in {remaining}.",
  "paused.deadlineAt": "Andernfalls wird {policy} um {time}.",
  "paused.anyMoment": "jeden Moment",
  "input.placeholder": "Nachricht eingeben...",
  "input.label": "Nachricht",
  "input.send": "Nachricht senden",
  "input.attach": "Datei anhängen",
  "input.attachBusy": "Datei anhängen (Upload läuft)",
  "input.attachLimit": "Anhang-Limit ({max}) erreicht",
  "input.secretToggle": "Geheimmodus",
  "input.secretOff":
    "Geheimmodus einschalten – die Nachricht wird im Chat verborgen und im gespeicherten Verlauf maskiert",
  "input.secretOn":
    "Geheimmodus ist an – die Nachricht wird im Chat verborgen und im gespeicherten Verlauf maskiert",
  "input.secretPlaceholder": "Geheimen Wert eingeben...",
  "input.secretShow": "Geheimnis anzeigen",
  "input.secretHide": "Geheimnis verbergen",
  "input.show": "Anzeigen",
  "input.hide": "Verbergen",
  "input.typeInstead": "Stattdessen eine Nachricht schreiben",
  "input.sendSecret": "Geheimnis senden",
  "attach.tooLarge": "{file} ist zu groß zum Hochladen.",
  "attach.notAllowed":
    "Sie dürfen dieser Unterhaltung keine Dateien anhängen.",
  "attach.rejectedWhy": "{file} wurde abgelehnt – {message}",
  "attach.rejected": "{file} wurde abgelehnt.",
  "attach.failed": "Hochladen von {file} fehlgeschlagen.",
  "attach.maxFiles": "Sie können höchstens {max} Dateien pro Nachricht anhängen.",
  "attach.notAttached": "Nicht angehängt: {files}",
  "attach.done": "{file} angehängt.",
  "attach.doneLarge":
    "{file} angehängt, ist aber zu groß für den direkten Versand – der Assistent kann sie möglicherweise nicht lesen.",
  "attach.removed": "{file} entfernt.",
  "attach.remove": "{file} entfernen",
  "attach.removeNth": "{file} entfernen ({n} von {total})",
  "attach.uploading": "Wird hochgeladen",
  "attach.chipLarge": "zu groß für den direkten Versand",
  "attach.largeLine": "📎 {file} – zu groß für den direkten Versand",
};

const fr: Table = {
  "lang.name": "Français",
  "transcript.label": "Conversation",
  "transcript.starting": "Démarrage de la conversation…",
  "transcript.ready": "Dites bonjour pour commencer.",
  "scroll.bottom": "Aller en bas",
  "message.noResponse": "Aucune réponse",
  "speaker.you": "Vous avez dit :",
  "speaker.agent": "{name} a dit :",
  "speaker.agentDefault": "L’assistant",
  "copy.message": "Copier le message",
  "copy.code": "Copier le code",
  "copy.done": "Copié",
  "copy.failed": "Échec de la copie",
  "theme.toggle": "Changer de thème",
  "start.retry": "Réessayer",
  "start.notFoundEnv":
    "⚠️ Cet agent n’est pas disponible dans l’environnement « {env} ». Il n’y est peut-être pas déployé.",
  "start.notFound":
    "⚠️ Cet agent n’est pas disponible. Il n’est peut-être pas déployé.",
  "start.unauthorized":
    "⚠️ Connectez-vous pour discuter avec cet agent, puis réessayez.",
  "start.forbidden":
    "⚠️ L’accès à cet agent a été refusé. Si ce chat est intégré dans un autre site, celui-ci n’est peut-être pas autorisé à l’utiliser — contactez son propriétaire.",
  "start.badRequest":
    "⚠️ La conversation n’a pas pu démarrer. Vérifiez l’adresse — l’environnement doit être « production » ou « test ».",
  "start.generic":
    "⚠️ La conversation n’a pas pu démarrer. Veuillez réessayer.",
  "send.conflict":
    "⚠️ Votre message n’a pas été envoyé — la conversation est occupée ou attend une décision. Réessayez dans un instant.",
  "send.awaiting":
    "⚠️ Votre message n’a pas été envoyé — la conversation attend une décision.",
  "send.notSent": "⚠️ Votre message n’a pas été envoyé — {message}",
  "send.notFound":
    "⚠️ Votre message n’a pas été envoyé — cet agent n’est pas prêt à répondre (il n’est peut-être pas déployé). Le texte est revenu dans la zone de saisie.",
  "send.tooLarge":
    "⚠️ Votre message n’a pas été envoyé — il ou sa pièce jointe est trop volumineux. Le texte est revenu dans la zone de saisie.",
  "send.rateLimited":
    "⚠️ Votre message n’a pas été envoyé — une limite d’utilisation est atteinte. Patientez un instant et réessayez.",
  "send.unavailable":
    "⚠️ Votre message n’a pas été envoyé — le service est temporairement indisponible. Veuillez réessayer sous peu.",
  "send.ended":
    "⚠️ Cette conversation est terminée. Démarrez une nouvelle conversation pour continuer.",
  "send.sessionExpired":
    "⚠️ Votre session a expiré. Reconnectez-vous, puis renvoyez votre message.",
  "send.forbidden":
    "⚠️ Vous n’êtes pas autorisé à poursuivre cette conversation. Elle appartient peut-être à un autre utilisateur.",
  "send.failed":
    "⚠️ Votre message n’a pas pu être envoyé. Il est revenu dans la zone de saisie — veuillez réessayer.",
  "send.connectionLost":
    "Connexion perdue — la réponse est peut-être incomplète.",
  "send.cancelled": "Cette demande a été annulée.",
  "skipped.awaiting":
    "Cette conversation attend qu’un relecteur approuve l’étape précédente. Votre message n’a pas été envoyé.",
  "skipped.busy":
    "L’agent travaille encore sur votre message précédent. Votre message n’a pas été envoyé — réessayez dans un instant.",
  "skipped.ended":
    "Cette conversation est terminée. Votre message n’a pas été envoyé.",
  "skipped.default": "Votre message n’a pas été traité.",
  "undo.title": "Annuler le dernier message",
  "undo.notNow": "⚠️ Impossible d’annuler pour le moment.",
  "undo.failed": "⚠️ L’annulation a échoué.",
  "redo.title": "Rétablir le message",
  "redo.notNow": "⚠️ Impossible de rétablir pour le moment.",
  "redo.failed": "⚠️ Le rétablissement a échoué.",
  "retry.nothing": "⚠️ Il n’y a rien à relancer pour le moment.",
  "retry.failed":
    "⚠️ La nouvelle tentative a échoué. Vous pouvez démarrer une nouvelle conversation.",
  "recovery.interrupted":
    "Cette demande a été interrompue avant la fin.",
  "recovery.error": "Un problème est survenu à la dernière étape.",
  "recovery.retry": "Réessayer",
  "recovery.restart": "Recommencer",
  "ended.label": "Conversation terminée",
  "ended.retired":
    "Cet assistant a été mis à jour. Démarrez une nouvelle conversation pour continuer.",
  "ended.restart": "Démarrer une nouvelle conversation",
  "action.stop": "Arrêter la génération",
  "action.new": "Nouvelle conversation",
  "action.newConfirm":
    "Démarrer une nouvelle conversation ? La conversation actuelle sera fermée.",
  "action.newYes": "Démarrer",
  "action.newCancel": "Garder celle-ci",
  "paused.title": "En attente d’approbation",
  "paused.pending": "Vérification de l’état d’approbation…",
  "paused.cancel": "Annuler cette demande",
  "paused.headline.tool":
    "Un relecteur doit approuver « {tool} » avant que je puisse continuer.",
  "paused.headline.tools":
    "Un relecteur doit approuver ces actions avant que je puisse continuer : {tools}.",
  "paused.headline.reason":
    "Un relecteur doit approuver cette étape avant que je puisse continuer — {reason}.",
  "paused.headline":
    "Un relecteur doit approuver cette étape avant que je puisse continuer.",
  "paused.policy.approve": "approuvée automatiquement",
  "paused.policy.reject": "rejetée automatiquement",
  "paused.policy.abort": "annulée automatiquement",
  "paused.policy.other": "décidée automatiquement",
  "paused.deadline": "Sinon, elle sera {policy} dans {remaining}.",
  "paused.deadlineAt": "Sinon, elle sera {policy} à {time}.",
  "paused.anyMoment": "d’un instant à l’autre",
  "input.placeholder": "Saisissez un message...",
  "input.label": "Message",
  "input.send": "Envoyer le message",
  "input.attach": "Joindre un fichier",
  "input.attachBusy": "Joindre un fichier (envoi en cours)",
  "input.attachLimit": "Limite de pièces jointes ({max}) atteinte",
  "input.secretToggle": "Mode secret",
  "input.secretOff":
    "Activer le mode secret — le message est masqué dans le chat et dans l’historique enregistré",
  "input.secretOn":
    "Le mode secret est activé — le message est masqué dans le chat et dans l’historique enregistré",
  "input.secretPlaceholder": "Saisissez la valeur secrète...",
  "input.secretShow": "Afficher le secret",
  "input.secretHide": "Masquer le secret",
  "input.show": "Afficher",
  "input.hide": "Masquer",
  "input.typeInstead": "Écrire un message à la place",
  "input.sendSecret": "Envoyer le secret",
  "attach.tooLarge": "{file} est trop volumineux pour être envoyé.",
  "attach.notAllowed":
    "Vous n’êtes pas autorisé à joindre des fichiers à cette conversation.",
  "attach.rejectedWhy": "{file} a été rejeté — {message}",
  "attach.rejected": "{file} a été rejeté.",
  "attach.failed": "Échec de l’envoi de {file}.",
  "attach.maxFiles":
    "Vous pouvez joindre au maximum {max} fichiers par message.",
  "attach.notAttached": "Non joints : {files}",
  "attach.done": "{file} joint.",
  "attach.doneLarge":
    "{file} joint, mais trop volumineux pour être transmis directement — l’assistant ne pourra peut-être pas le lire.",
  "attach.removed": "{file} retiré.",
  "attach.remove": "Retirer {file}",
  "attach.removeNth": "Retirer {file} ({n} sur {total})",
  "attach.uploading": "Envoi en cours",
  "attach.chipLarge": "trop volumineux pour être transmis directement",
  "attach.largeLine": "📎 {file} — trop volumineux pour être transmis directement",
};

const es: Table = {
  "lang.name": "Español",
  "transcript.label": "Conversación",
  "transcript.starting": "Iniciando la conversación…",
  "transcript.ready": "Saluda para empezar.",
  "scroll.bottom": "Ir al final",
  "message.noResponse": "Sin respuesta",
  "speaker.you": "Dijiste:",
  "speaker.agent": "{name} dijo:",
  "speaker.agentDefault": "El asistente",
  "copy.message": "Copiar mensaje",
  "copy.code": "Copiar código",
  "copy.done": "Copiado",
  "copy.failed": "No se pudo copiar",
  "theme.toggle": "Cambiar tema",
  "start.retry": "Reintentar",
  "start.notFoundEnv":
    "⚠️ Este agente no está disponible en el entorno «{env}». Puede que no esté desplegado allí.",
  "start.notFound":
    "⚠️ Este agente no está disponible. Puede que no esté desplegado.",
  "start.unauthorized":
    "⚠️ Inicia sesión para chatear con este agente y vuelve a intentarlo.",
  "start.forbidden":
    "⚠️ Se rechazó el acceso a este agente. Si este chat está incrustado en otro sitio, puede que ese sitio no tenga permiso para usarlo: consulta a su propietario.",
  "start.badRequest":
    "⚠️ No se pudo iniciar la conversación. Revisa la dirección: el entorno debe ser «production» o «test».",
  "start.generic":
    "⚠️ No se pudo iniciar la conversación. Inténtalo de nuevo.",
  "send.conflict":
    "⚠️ Tu mensaje no se envió: la conversación está ocupada o esperando una decisión. Inténtalo de nuevo en un momento.",
  "send.awaiting":
    "⚠️ Tu mensaje no se envió: la conversación está esperando una decisión.",
  "send.notSent": "⚠️ Tu mensaje no se envió: {message}",
  "send.notFound":
    "⚠️ Tu mensaje no se envió: este agente no está listo para responder (puede que no esté desplegado). El texto volvió al cuadro de mensaje.",
  "send.tooLarge":
    "⚠️ Tu mensaje no se envió: él o su adjunto es demasiado grande. El texto volvió al cuadro de mensaje.",
  "send.rateLimited":
    "⚠️ Tu mensaje no se envió: se alcanzó un límite de uso. Espera un momento e inténtalo de nuevo.",
  "send.unavailable":
    "⚠️ Tu mensaje no se envió: el servicio no está disponible temporalmente. Inténtalo de nuevo en breve.",
  "send.ended":
    "⚠️ Esta conversación ha terminado. Inicia una nueva para continuar.",
  "send.sessionExpired":
    "⚠️ Tu sesión ha caducado. Inicia sesión de nuevo y reenvía tu mensaje.",
  "send.forbidden":
    "⚠️ No tienes permiso para continuar esta conversación. Puede pertenecer a otro usuario.",
  "send.failed":
    "⚠️ No se pudo enviar tu mensaje. El texto volvió al cuadro de mensaje: inténtalo de nuevo.",
  "send.connectionLost":
    "Conexión perdida: la respuesta puede estar incompleta.",
  "send.cancelled": "Esta solicitud se canceló.",
  "skipped.awaiting":
    "Esta conversación espera a que un revisor apruebe el paso anterior. Tu mensaje no se envió.",
  "skipped.busy":
    "El agente aún está trabajando en tu mensaje anterior. Tu mensaje no se envió: inténtalo de nuevo en un momento.",
  "skipped.ended": "Esta conversación ha terminado. Tu mensaje no se envió.",
  "skipped.default": "Tu mensaje no se procesó.",
  "undo.title": "Deshacer el último mensaje",
  "undo.notNow": "⚠️ No se puede deshacer en este momento.",
  "undo.failed": "⚠️ No se pudo deshacer.",
  "redo.title": "Rehacer mensaje",
  "redo.notNow": "⚠️ No se puede rehacer en este momento.",
  "redo.failed": "⚠️ No se pudo rehacer.",
  "retry.nothing": "⚠️ No hay nada que reintentar ahora.",
  "retry.failed":
    "⚠️ El reintento falló. Puedes iniciar una nueva conversación.",
  "recovery.interrupted": "Esta solicitud se interrumpió antes de terminar.",
  "recovery.error": "Algo salió mal en el último paso.",
  "recovery.retry": "Reintentar",
  "recovery.restart": "Empezar de nuevo",
  "ended.label": "Conversación terminada",
  "ended.retired":
    "Este asistente se actualizó. Inicia una nueva conversación para continuar.",
  "ended.restart": "Iniciar nueva conversación",
  "action.stop": "Detener la generación",
  "action.new": "Nueva conversación",
  "action.newConfirm":
    "¿Iniciar una nueva conversación? La actual se cerrará.",
  "action.newYes": "Iniciar nueva",
  "action.newCancel": "Conservar esta",
  "paused.title": "Esperando aprobación",
  "paused.pending": "Comprobando el estado de la aprobación…",
  "paused.cancel": "Cancelar esta solicitud",
  "paused.headline.tool":
    "Un revisor debe aprobar «{tool}» antes de que pueda continuar.",
  "paused.headline.tools":
    "Un revisor debe aprobar estas acciones antes de que pueda continuar: {tools}.",
  "paused.headline.reason":
    "Un revisor debe aprobar este paso antes de que pueda continuar: {reason}.",
  "paused.headline":
    "Un revisor debe aprobar este paso antes de que pueda continuar.",
  "paused.policy.approve": "aprobada automáticamente",
  "paused.policy.reject": "rechazada automáticamente",
  "paused.policy.abort": "cancelada automáticamente",
  "paused.policy.other": "decidida automáticamente",
  "paused.deadline": "De lo contrario, será {policy} en {remaining}.",
  "paused.deadlineAt": "De lo contrario, será {policy} a las {time}.",
  "paused.anyMoment": "en cualquier momento",
  "input.placeholder": "Escribe un mensaje...",
  "input.label": "Mensaje",
  "input.send": "Enviar mensaje",
  "input.attach": "Adjuntar archivo",
  "input.attachBusy": "Adjuntar archivo (subida en curso)",
  "input.attachLimit": "Límite de adjuntos ({max}) alcanzado",
  "input.secretToggle": "Modo secreto",
  "input.secretOff":
    "Activar el modo secreto: el mensaje se oculta en el chat y se enmascara en el historial guardado",
  "input.secretOn":
    "El modo secreto está activado: el mensaje se oculta en el chat y se enmascara en el historial guardado",
  "input.secretPlaceholder": "Introduce el valor secreto...",
  "input.secretShow": "Mostrar secreto",
  "input.secretHide": "Ocultar secreto",
  "input.show": "Mostrar",
  "input.hide": "Ocultar",
  "input.typeInstead": "Escribir un mensaje en su lugar",
  "input.sendSecret": "Enviar secreto",
  "attach.tooLarge": "{file} es demasiado grande para subirlo.",
  "attach.notAllowed":
    "No tienes permiso para adjuntar archivos a esta conversación.",
  "attach.rejectedWhy": "{file} fue rechazado: {message}",
  "attach.rejected": "{file} fue rechazado.",
  "attach.failed": "No se pudo subir {file}.",
  "attach.maxFiles": "Puedes adjuntar como máximo {max} archivos por mensaje.",
  "attach.notAttached": "No adjuntados: {files}",
  "attach.done": "{file} adjuntado.",
  "attach.doneLarge":
    "{file} adjuntado, pero es demasiado grande para enviarlo directamente: puede que el asistente no pueda leerlo.",
  "attach.removed": "{file} eliminado.",
  "attach.remove": "Quitar {file}",
  "attach.removeNth": "Quitar {file} ({n} de {total})",
  "attach.uploading": "Subiendo",
  "attach.chipLarge": "demasiado grande para enviarlo directamente",
  "attach.largeLine":
    "📎 {file} — demasiado grande para enviarlo directamente",
};

/** Add a locale here (the key is its BCP 47 primary subtag). */
const LOCALES: Record<string, Table> = { en, de, fr, es };

/** The string tables, for the test that keeps them consistent. */
export const TRANSLATIONS: Readonly<Record<string, Table>> = LOCALES;

/** Languages written right to left; applied when a table for one is added. */
const RTL = new Set(["ar", "he", "fa", "ur"]);

export const SUPPORTED_LOCALES: readonly string[] = Object.keys(LOCALES);

let current = "en";

/** Reduce "de-AT" / "DE_at" to the table key, or null when unsupported. */
export function resolveLocale(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const primary = raw.trim().toLowerCase().split(/[-_]/)[0];
  return primary in LOCALES ? primary : null;
}

/**
 * Pick the locale: an explicit `?lang=` wins, then the browser's languages in
 * preference order, then English. Also sets `<html lang>` and `dir`.
 */
export function initLocale(langParam?: string | null): string {
  let chosen = resolveLocale(langParam);
  if (!chosen && typeof navigator !== "undefined") {
    const preferred = navigator.languages?.length
      ? navigator.languages
      : [navigator.language];
    for (const candidate of preferred) {
      chosen = resolveLocale(candidate);
      if (chosen) break;
    }
  }
  setLocale(chosen ?? "en");
  return current;
}

export function setLocale(locale: string): void {
  current = locale in LOCALES ? locale : "en";
  if (typeof document !== "undefined") {
    document.documentElement.lang = current;
    document.documentElement.dir = RTL.has(current) ? "rtl" : "ltr";
  }
}

export function getLocale(): string {
  return current;
}

/** Look a string up, falling back to English, and fill `{name}` placeholders. */
export function t(
  key: MessageKey,
  params?: Record<string, string | number>,
): string {
  const template = LOCALES[current]?.[key] ?? en[key];
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (match, name: string) =>
    Object.hasOwn(params, name) ? String(params[name]) : match,
  );
}
