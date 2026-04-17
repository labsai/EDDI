import { setupWorker } from "msw/browser";
import { handlers, coordinatorHandlers, orphanHandlers, logAdminHandlers, secretsHandlers, auditHandlers, quotaHandlers, scheduleHandlers, gdprHandlers, capabilityHandlers, userMemoryHandlers, propertiesHandlers, triggerHandlers, backupSyncHandlers } from "./handlers";

export const worker = setupWorker(...handlers, ...coordinatorHandlers, ...orphanHandlers, ...logAdminHandlers, ...secretsHandlers, ...auditHandlers, ...quotaHandlers, ...scheduleHandlers, ...gdprHandlers, ...capabilityHandlers, ...userMemoryHandlers, ...propertiesHandlers, ...triggerHandlers, ...backupSyncHandlers);
