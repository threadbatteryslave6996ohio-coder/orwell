import Link from "next/link";
import { useState } from "react";

import { api } from "~/utils/api";

function todayAsInputValue() {
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60_000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
}

export default function Home() {
  const utils = api.useUtils();
  const workouts = api.workout.list.useQuery();

  const [name, setName] = useState("");
  const [performedAt, setPerformedAt] = useState(todayAsInputValue());

  const createWorkout = api.workout.create.useMutation({
    onSuccess: async () => {
      setName("");
      await utils.workout.list.invalidate();
    },
  });
  const deleteWorkout = api.workout.delete.useMutation({
    onSuccess: () => utils.workout.list.invalidate(),
  });

  return (
    <main className="mx-auto max-w-3xl px-4 py-10">
      <header className="mb-8">
        <h1 className="text-3xl font-bold tracking-tight">Workouts</h1>
        <p className="mt-1 text-sm text-neutral-400">
          Every workout holds exercises; every exercise holds sets.
        </p>
      </header>

      <form
        className="mb-10 flex flex-col gap-3 rounded-xl border border-neutral-800 bg-neutral-900 p-4 sm:flex-row"
        onSubmit={(event) => {
          event.preventDefault();
          if (!name.trim()) return;
          createWorkout.mutate({
            name,
            performedAt: new Date(`${performedAt}T12:00:00`),
          });
        }}
      >
        <input
          className="flex-1 rounded-lg border border-neutral-700 bg-neutral-950 px-3 py-2 text-sm outline-none focus:border-neutral-500"
          placeholder="Push day, Leg day, …"
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
        <input
          type="date"
          className="rounded-lg border border-neutral-700 bg-neutral-950 px-3 py-2 text-sm outline-none focus:border-neutral-500"
          value={performedAt}
          onChange={(event) => setPerformedAt(event.target.value)}
        />
        <button
          type="submit"
          disabled={createWorkout.isPending}
          className="rounded-lg bg-lime-400 px-4 py-2 text-sm font-semibold text-neutral-950 transition hover:bg-lime-300 disabled:opacity-50"
        >
          {createWorkout.isPending ? "Adding…" : "New workout"}
        </button>
      </form>

      {createWorkout.error && (
        <p className="mb-4 text-sm text-red-400">
          {createWorkout.error.message}
        </p>
      )}

      {workouts.isLoading && (
        <p className="text-sm text-neutral-500">Loading…</p>
      )}

      {workouts.data?.length === 0 && (
        <p className="rounded-xl border border-dashed border-neutral-800 p-8 text-center text-sm text-neutral-500">
          Nothing recorded yet.
        </p>
      )}

      <ul className="flex flex-col gap-3">
        {workouts.data?.map((workout) => (
          <li
            key={workout.id}
            className="flex items-center justify-between rounded-xl border border-neutral-800 bg-neutral-900 p-4 transition hover:border-neutral-700"
          >
            <Link href={`/workouts/${workout.id}`} className="min-w-0 flex-1">
              <p className="truncate font-medium">{workout.name}</p>
              <p className="mt-1 text-xs text-neutral-400">
                {workout.performedAt.toLocaleDateString()} ·{" "}
                {workout.exerciseCount} exercise
                {workout.exerciseCount === 1 ? "" : "s"} · {workout.setCount} set
                {workout.setCount === 1 ? "" : "s"}
              </p>
            </Link>
            <button
              className="ml-4 rounded-lg px-3 py-1 text-xs text-neutral-500 transition hover:bg-red-500/10 hover:text-red-400"
              onClick={() => deleteWorkout.mutate({ id: workout.id })}
            >
              Delete
            </button>
          </li>
        ))}
      </ul>
    </main>
  );
}
