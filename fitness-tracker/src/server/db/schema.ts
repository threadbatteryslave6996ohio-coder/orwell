import { relations, sql } from "drizzle-orm";
import { index, integer, real, sqliteTable, text } from "drizzle-orm/sqlite-core";

/** A training session: a named bag of exercises done on one day. */
export const workouts = sqliteTable("workout", {
  id: integer("id").primaryKey({ autoIncrement: true }),
  name: text("name").notNull(),
  notes: text("notes"),
  performedAt: integer("performed_at", { mode: "timestamp" })
    .notNull()
    .default(sql`(unixepoch())`),
  createdAt: integer("created_at", { mode: "timestamp" })
    .notNull()
    .default(sql`(unixepoch())`),
});

/** One movement inside a workout, e.g. "Bench press". */
export const exercises = sqliteTable(
  "exercise",
  {
    id: integer("id").primaryKey({ autoIncrement: true }),
    workoutId: integer("workout_id")
      .notNull()
      .references(() => workouts.id, { onDelete: "cascade" }),
    name: text("name").notNull(),
    position: integer("position").notNull().default(0),
  },
  (table) => ({
    workoutIdx: index("exercise_workout_idx").on(table.workoutId),
  }),
);

/** One set of an exercise: what you did, how heavy, and the clip of it. */
export const exerciseSets = sqliteTable(
  "exercise_set",
  {
    id: integer("id").primaryKey({ autoIncrement: true }),
    exerciseId: integer("exercise_id")
      .notNull()
      .references(() => exercises.id, { onDelete: "cascade" }),
    description: text("description").notNull().default(""),
    /** Kilograms. Null means bodyweight / unweighted. */
    weight: real("weight"),
    /** Either an uploaded file under /uploads or an external URL. */
    videoUrl: text("video_url"),
    position: integer("position").notNull().default(0),
  },
  (table) => ({
    exerciseIdx: index("set_exercise_idx").on(table.exerciseId),
  }),
);

export const workoutRelations = relations(workouts, ({ many }) => ({
  exercises: many(exercises),
}));

export const exerciseRelations = relations(exercises, ({ one, many }) => ({
  workout: one(workouts, {
    fields: [exercises.workoutId],
    references: [workouts.id],
  }),
  sets: many(exerciseSets),
}));

export const exerciseSetRelations = relations(exerciseSets, ({ one }) => ({
  exercise: one(exercises, {
    fields: [exerciseSets.exerciseId],
    references: [exercises.id],
  }),
}));
