## 🩹 fix(manager): webhook channel — explain the options, label every field (2026-10-17)

**Repo:** EDDI (`feat/channels-signed-webhook`)

Browser-checked the webhook channel in the create dialog and on the detail page. Fixed: the signature scheme and conversation strategy were bare identifiers (now a one-line explanation of the chosen option); the generic-HMAC and event-type inputs had only placeholders and untranslated English `aria-label`s such as `signatureHeader` (now visible, translated labels); the last create step talked about trigger keywords, which a webhook does not have; those grids now stack on a narrow screen. A `Billing Webhook` entry was added to the mock channel store. Files: [`webhook-settings.tsx`](../../ui/manager/src/components/channels/webhook-settings.tsx), [`create-channel-dialog.tsx`](../../ui/manager/src/components/channels/create-channel-dialog.tsx).
