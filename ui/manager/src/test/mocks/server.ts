import { setupServer } from "msw/node";
import { handlers, coordinatorHandlers, orphanHandlers, logAdminHandlers } from "./handlers";

export const server = setupServer(...handlers, ...coordinatorHandlers, ...orphanHandlers, ...logAdminHandlers);
