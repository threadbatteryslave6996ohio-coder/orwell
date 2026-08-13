import Link from "next/link";
import { useRouter } from "next/router";
import { useRef, useState } from "react";

import { api, type RouterOutputs } from "~/utils/api";

type Workout = RouterOutputs["workout"]["byId"];
type Exercise = Workout["exercises"][number];

const input =
  "rounded-lg border border-neutral-700 bg-neutral-950 px-3 py-2 text-sm outline-none focus:border-neutral-500";

export default function WorkoutPage() {
  const router = useRouter();
  const id = Number(router.query.id);
  const enabled = Number.isInteger(id);

  const workout = api.workout.byId.useQuery({ id }, { enabled });
  const utils = api.useUtils();

  const updateWorkout = api.workout.update.useMutation({
    onSuccess: () => utils.workout.byId.invalidate({ id }),
  });
  const addExercise = api.workout.addExercise.useMutation({
    onSuccess: () => utils.workout.byId.invalidate({ id }),
  });

  const [exerciseName, setExerciseName] = useState("");

  if (!enabled || workout.isLoading) {
    return <main className="p-10 text-sm text-neutral-500">Loading…</main>;
  }
  if (workout.error || !workout.data) {
    return (
      <main className="p-10 text-sm text-red-400">
        {workout.error?.message ?? "Not found."}{" "}
        <Link href="/" className="underline">
          Back
        </Link>
      </main>
    );
  }

  const data = workout.data;

  return (
    <main className="mx-auto max-w-3xl px-4 py-10">
      <Link href="/" className="text-xs text-neutral-500 hover:text-neutral-300">
        ← All workouts
      </Link>

      <header className="mb-8 mt-3">
        <input
          className="w-full bg-transparent text-3xl font-bold tracking-tight outline-none focus:text-lime-300"
          defaultValue={data.name}
          onBlur={(event) => {
            const name = event.target.value.trim();
            if (name && name !== data.name) updateWorkout.mutate({ id, name });
          }}
        />
        <p className="mt-1 text-sm text-neutral-400">
          {data.performedAt.toLocaleDateString()}
        </p>
        <textarea
          className={`${input} mt-3 w-full`}
          rows={2}
          placeholder="Notes — how it felt, what to change next time…"
          defaultValue={data.notes ?? ""}
          onBlur={(event) => {
            const notes = event.target.value;
            if (notes !== (data.notes ?? "")) updateWorkout.mutate({ id, notes });
          }}
        />
      </header>

      <div className="flex flex-col gap-4">
        {data.exercises.map((exercise) => (
          <ExerciseCard key={exercise.id} workoutId={id} exercise={exercise} />
        ))}
      </div>

      <form
        className="mt-6 flex gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          if (!exerciseName.trim()) return;
          addExercise.mutate({ workoutId: id, name: exerciseName });
          setExerciseName("");
        }}
      >
        <input
          className={`${input} flex-1`}
          placeholder="Add an exercise — Bench press, Squat, …"
          value={exerciseName}
          onChange={(event) => setExerciseName(event.target.value)}
        />
        <button
          type="submit"
          className="rounded-lg bg-lime-400 px-4 py-2 text-sm font-semibold text-neutral-950 transition hover:bg-lime-300"
        >
          Add exercise
        </button>
      </form>
    </main>
  );
}

function ExerciseCard({
  workoutId,
  exercise,
}: {
  workoutId: number;
  exercise: Exercise;
}) {
  const utils = api.useUtils();
  const invalidate = () => utils.workout.byId.invalidate({ id: workoutId });

  const rename = api.workout.renameExercise.useMutation({ onSuccess: invalidate });
  const remove = api.workout.deleteExercise.useMutation({ onSuccess: invalidate });
  const deleteSet = api.workout.deleteSet.useMutation({ onSuccess: invalidate });

  return (
    <section className="rounded-xl border border-neutral-800 bg-neutral-900 p-4">
      <div className="flex items-center justify-between gap-3">
        <input
          className="min-w-0 flex-1 bg-transparent text-lg font-semibold outline-none focus:text-lime-300"
          defaultValue={exercise.name}
          onBlur={(event) => {
            const name = event.target.value.trim();
            if (name && name !== exercise.name) {
              rename.mutate({ id: exercise.id, name });
            }
          }}
        />
        <button
          className="rounded-lg px-3 py-1 text-xs text-neutral-500 transition hover:bg-red-500/10 hover:text-red-400"
          onClick={() => remove.mutate({ id: exercise.id })}
        >
          Remove
        </button>
      </div>

      {exercise.sets.length > 0 && (
        <ol className="mt-4 flex flex-col gap-3">
          {exercise.sets.map((set, index) => (
            <li
              key={set.id}
              className="flex flex-col gap-3 rounded-lg border border-neutral-800 bg-neutral-950 p-3 sm:flex-row sm:items-start"
            >
              <span className="w-6 shrink-0 text-sm text-neutral-500">
                {index + 1}
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-sm">
                  {set.description || (
                    <span className="text-neutral-600">No description</span>
                  )}
                </p>
                <p className="mt-1 text-xs text-neutral-400">
                  {set.weight === null ? "bodyweight" : `${set.weight} kg`}
                </p>
              </div>
              {set.videoUrl && (
                <video
                  src={set.videoUrl}
                  controls
                  playsInline
                  className="w-full rounded-md bg-black sm:w-48"
                />
              )}
              <button
                className="self-start rounded-lg px-2 py-1 text-xs text-neutral-600 transition hover:bg-red-500/10 hover:text-red-400"
                onClick={() => deleteSet.mutate({ id: set.id })}
              >
                ✕
              </button>
            </li>
          ))}
        </ol>
      )}

      <AddSetForm workoutId={workoutId} exerciseId={exercise.id} />
    </section>
  );
}

function AddSetForm({
  workoutId,
  exerciseId,
}: {
  workoutId: number;
  exerciseId: number;
}) {
  const utils = api.useUtils();
  const [description, setDescription] = useState("");
  const [weight, setWeight] = useState("");
  const [videoUrl, setVideoUrl] = useState("");
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const addSet = api.workout.addSet.useMutation({
    onSuccess: async () => {
      setDescription("");
      setWeight("");
      setVideoUrl("");
      if (fileInput.current) fileInput.current.value = "";
      await utils.workout.byId.invalidate({ id: workoutId });
    },
    onError: (mutationError) => setError(mutationError.message),
  });

  async function upload(file: File) {
    setError(null);
    setUploading(true);
    try {
      const response = await fetch(
        `/api/upload?name=${encodeURIComponent(file.name)}`,
        { method: "POST", body: file },
      );
      const body = (await response.json()) as { url?: string; error?: string };
      if (!response.ok || !body.url) {
        throw new Error(body.error ?? "Upload failed.");
      }
      setVideoUrl(body.url);
    } catch (uploadError) {
      setError(
        uploadError instanceof Error ? uploadError.message : "Upload failed.",
      );
    } finally {
      setUploading(false);
    }
  }

  return (
    <form
      className="mt-4 flex flex-col gap-2 border-t border-neutral-800 pt-4"
      onSubmit={(event) => {
        event.preventDefault();
        setError(null);
        addSet.mutate({
          exerciseId,
          description,
          weight: weight.trim() === "" ? null : Number(weight),
          videoUrl: videoUrl.trim() === "" ? null : videoUrl.trim(),
        });
      }}
    >
      <div className="flex flex-col gap-2 sm:flex-row">
        <input
          className={`${input} flex-1`}
          placeholder="Set description — 8 reps, slow eccentric…"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
        <input
          className={`${input} sm:w-28`}
          type="number"
          step="0.5"
          min="0"
          placeholder="kg"
          value={weight}
          onChange={(event) => setWeight(event.target.value)}
        />
      </div>

      <div className="flex flex-col gap-2 sm:flex-row">
        <input
          className={`${input} flex-1`}
          placeholder="Video link, or pick a file →"
          value={videoUrl}
          onChange={(event) => setVideoUrl(event.target.value)}
        />
        <input
          ref={fileInput}
          type="file"
          accept="video/*"
          className="text-xs text-neutral-400 file:mr-3 file:rounded-lg file:border-0 file:bg-neutral-800 file:px-3 file:py-2 file:text-xs file:text-neutral-200"
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) void upload(file);
          }}
        />
        <button
          type="submit"
          disabled={uploading || addSet.isPending}
          className="rounded-lg border border-neutral-700 px-4 py-2 text-sm font-medium transition hover:border-neutral-500 disabled:opacity-50"
        >
          {uploading ? "Uploading…" : "Add set"}
        </button>
      </div>

      {error && <p className="text-xs text-red-400">{error}</p>}
    </form>
  );
}
