import { setupWorker } from "msw/browser";
import { handlers, coordinatorHandlers, orphanHandlers, logAdminHandlers, secretsHandlers, auditHandlers, quotaHandlers, scheduleHandlers, gdprHandlers } from "./handlers";

export const worker = setupWorker(...handlers, ...coordinatorHandlers, ...orphanHandlers, ...logAdminHandlers, ...secretsHandlers, ...auditHandlers, ...quotaHandlers, ...scheduleHandlers, ...gdprHandlers);
