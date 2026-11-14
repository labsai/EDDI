import { setupServer } from "msw/node";
import { handlers, coordinatorHandlers, orphanHandlers, logAdminHandlers, secretsHandlers, variablesHandlers, auditHandlers, quotaHandlers, scheduleHandlers, gdprHandlers, capabilityHandlers, userMemoryHandlers, propertiesHandlers, triggerHandlers, backupSyncHandlers, connectionHandlers, agentTestHandlers, brandingHandlers } from "./handlers";
import { healthHandlers } from "./health-handlers";

export const server = setupServer(...healthHandlers, ...handlers, ...coordinatorHandlers, ...orphanHandlers, ...logAdminHandlers, ...secretsHandlers, ...variablesHandlers, ...auditHandlers, ...quotaHandlers, ...scheduleHandlers, ...gdprHandlers, ...capabilityHandlers, ...userMemoryHandlers, ...propertiesHandlers, ...triggerHandlers, ...backupSyncHandlers, ...connectionHandlers, ...agentTestHandlers, ...brandingHandlers);
