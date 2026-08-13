import { asc, desc, eq, sql } from "drizzle-orm";
import { TRPCError } from "@trpc/server";
import { z } from "zod";

import { createTRPCRouter, publicProcedure } from "~/server/api/trpc";
import { exerciseSets, exercises, workouts } from "~/server/db/schema";
import { VIDEO_URL_PREFIX } from "~/server/uploads";

/**
 * A video is either an upload we serve ourselves or a link to one elsewhere.
 * `z.string().url()` alone would reject our own upload paths.
 */
const videoUrl = z
  .string()
  .trim()
  .refine(
    (value) =>
      value.startsWith(VIDEO_URL_PREFIX) || /^https?:\/\/\S+$/.test(value),
    "Give a http(s) link or upload a file.",
  );

export const workoutRouter = createTRPCRouter({
  list: publicProcedure.query(async ({ ctx }) => {
    const rows = await ctx.db.query.workouts.findMany({
      orderBy: [desc(workouts.performedAt), desc(workouts.id)],
      with: { exercises: { with: { sets: true } } },
    });

    return rows.map((workout) => ({
      id: workout.id,
      name: workout.name,
      notes: workout.notes,
      performedAt: workout.performedAt,
      exerciseCount: workout.exercises.length,
      setCount: workout.exercises.reduce((sum, e) => sum + e.sets.length, 0),
    }));
  }),

  byId: publicProcedure
    .input(z.object({ id: z.number().int() }))
    .query(async ({ ctx, input }) => {
      const workout = await ctx.db.query.workouts.findFirst({
        where: eq(workouts.id, input.id),
        with: {
          exercises: {
            orderBy: [asc(exercises.position), asc(exercises.id)],
            with: {
              sets: {
                orderBy: [asc(exerciseSets.position), asc(exerciseSets.id)],
              },
            },
          },
        },
      });

      if (!workout) {
        throw new TRPCError({ code: "NOT_FOUND", message: "No such workout." });
      }
      return workout;
    }),

  create: publicProcedure
    .input(
      z.object({
        name: z.string().trim().min(1, "A workout needs a name."),
        notes: z.string().trim().optional(),
        performedAt: z.date().optional(),
      }),
    )
    .mutation(async ({ ctx, input }) => {
      const [row] = await ctx.db
        .insert(workouts)
        .values({
          name: input.name,
          notes: input.notes ?? null,
          performedAt: input.performedAt ?? new Date(),
        })
        .returning();
      return row!;
    }),

  update: publicProcedure
    .input(
      z.object({
        id: z.number().int(),
        name: z.string().trim().min(1).optional(),
        notes: z.string().optional(),
        performedAt: z.date().optional(),
      }),
    )
    .mutation(async ({ ctx, input }) => {
      const { id, ...changes } = input;
      await ctx.db.update(workouts).set(changes).where(eq(workouts.id, id));
    }),

  delete: publicProcedure
    .input(z.object({ id: z.number().int() }))
    .mutation(async ({ ctx, input }) => {
      await ctx.db.delete(workouts).where(eq(workouts.id, input.id));
    }),

  addExercise: publicProcedure
    .input(
      z.object({
        workoutId: z.number().int(),
        name: z.string().trim().min(1, "An exercise needs a name."),
      }),
    )
    .mutation(async ({ ctx, input }) => {
      const [last] = await ctx.db
        .select({ max: sql<number | null>`max(${exercises.position})` })
        .from(exercises)
        .where(eq(exercises.workoutId, input.workoutId));
      const position = (last?.max ?? -1) + 1;

      const [row] = await ctx.db
        .insert(exercises)
        .values({ workoutId: input.workoutId, name: input.name, position })
        .returning();
      return row!;
    }),

  renameExercise: publicProcedure
    .input(
      z.object({ id: z.number().int(), name: z.string().trim().min(1) }),
    )
    .mutation(async ({ ctx, input }) => {
      await ctx.db
        .update(exercises)
        .set({ name: input.name })
        .where(eq(exercises.id, input.id));
    }),

  deleteExercise: publicProcedure
    .input(z.object({ id: z.number().int() }))
    .mutation(async ({ ctx, input }) => {
      await ctx.db.delete(exercises).where(eq(exercises.id, input.id));
    }),

  addSet: publicProcedure
    .input(
      z.object({
        exerciseId: z.number().int(),
        description: z.string().trim().default(""),
        weight: z.number().nonnegative().nullable().default(null),
        videoUrl: videoUrl.nullable().default(null),
      }),
    )
    .mutation(async ({ ctx, input }) => {
      const [last] = await ctx.db
        .select({ max: sql<number | null>`max(${exerciseSets.position})` })
        .from(exerciseSets)
        .where(eq(exerciseSets.exerciseId, input.exerciseId));
      const position = (last?.max ?? -1) + 1;

      const [row] = await ctx.db
        .insert(exerciseSets)
        .values({
          exerciseId: input.exerciseId,
          description: input.description,
          weight: input.weight,
          videoUrl: input.videoUrl,
          position,
        })
        .returning();
      return row!;
    }),

  updateSet: publicProcedure
    .input(
      z.object({
        id: z.number().int(),
        description: z.string().optional(),
        weight: z.number().nonnegative().nullable().optional(),
        videoUrl: videoUrl.nullable().optional(),
      }),
    )
    .mutation(async ({ ctx, input }) => {
      const { id, ...changes } = input;
      await ctx.db.update(exerciseSets).set(changes).where(eq(exerciseSets.id, id));
    }),

  deleteSet: publicProcedure
    .input(z.object({ id: z.number().int() }))
    .mutation(async ({ ctx, input }) => {
      await ctx.db.delete(exerciseSets).where(eq(exerciseSets.id, input.id));
    }),
});
