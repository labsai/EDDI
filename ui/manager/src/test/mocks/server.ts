import { setupServer } from "msw/node";
import { handlers, coordinatorHandlers, orphanHandlers, logAdminHandlers, secretsHandlers, auditHandlers, quotaHandlers, scheduleHandlers } from "./handlers";

export const server = setupServer(...handlers, ...coordinatorHandlers, ...orphanHandlers, ...logAdminHandlers, ...secretsHandlers, ...auditHandlers, ...quotaHandlers, ...scheduleHandlers);
