import { setupServer } from "msw/node";
import { handlers, coordinatorHandlers, orphanHandlers } from "./handlers";

export const server = setupServer(...handlers, ...coordinatorHandlers, ...orphanHandlers);
