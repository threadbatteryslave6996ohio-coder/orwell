import { createNextApiHandler } from "@trpc/server/adapters/next";

import { appRouter } from "~/server/api/root";
import { createTRPCContext } from "~/server/api/trpc";

export default createNextApiHandler({
  router: appRouter,
  createContext: createTRPCContext,
  onError({ path, error }) {
    console.error(`tRPC failed on ${path ?? "<no-path>"}: ${error.message}`);
  },
});
