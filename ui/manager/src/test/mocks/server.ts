import { setupServer } from "msw/node";
import { handlers, coordinatorHandlers, orphanHandlers, logAdminHandlers, secretsHandlers, variablesHandlers, auditHandlers, quotaHandlers, scheduleHandlers, gdprHandlers, capabilityHandlers, userMemoryHandlers, propertiesHandlers, triggerHandlers, backupSyncHandlers, connectionHandlers, agentTestHandlers, brandingHandlers, healthHandlers } from "./handlers";
import { healthPendingHandlers } from "./health-handlers";

export const server = setupServer(...healthHandlers, ...healthPendingHandlers, ...handlers, ...coordinatorHandlers, ...orphanHandlers, ...logAdminHandlers, ...secretsHandlers, ...variablesHandlers, ...auditHandlers, ...quotaHandlers, ...scheduleHandlers, ...gdprHandlers, ...capabilityHandlers, ...userMemoryHandlers, ...propertiesHandlers, ...triggerHandlers, ...backupSyncHandlers, ...connectionHandlers, ...agentTestHandlers, ...brandingHandlers);
