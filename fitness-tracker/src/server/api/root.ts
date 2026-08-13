import { workoutRouter } from "~/server/api/routers/workout";
import { createTRPCRouter } from "~/server/api/trpc";

export const appRouter = createTRPCRouter({
  workout: workoutRouter,
});

export type AppRouter = typeof appRouter;
