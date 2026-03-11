import { setupServer } from "msw/node";
import { handlers } from "./handlers";
import { coordinatorHandlers } from "./handlers";

export const server = setupServer(...handlers, ...coordinatorHandlers);
