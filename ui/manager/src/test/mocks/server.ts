import { setupServer } from "msw/node";
import { handlers, coordinatorHandlers, orphanHandlers, logAdminHandlers, secretsHandlers, variablesHandlers, auditHandlers, quotaHandlers, scheduleHandlers, gdprHandlers, capabilityHandlers, userMemoryHandlers, propertiesHandlers, triggerHandlers, backupSyncHandlers, connectionHandlers, agentTestHandlers, brandingHandlers } from "./handlers";

export const server = setupServer(...handlers, ...coordinatorHandlers, ...orphanHandlers, ...logAdminHandlers, ...secretsHandlers, ...variablesHandlers, ...auditHandlers, ...quotaHandlers, ...scheduleHandlers, ...gdprHandlers, ...capabilityHandlers, ...userMemoryHandlers, ...propertiesHandlers, ...triggerHandlers, ...backupSyncHandlers, ...connectionHandlers, ...agentTestHandlers, ...brandingHandlers);
