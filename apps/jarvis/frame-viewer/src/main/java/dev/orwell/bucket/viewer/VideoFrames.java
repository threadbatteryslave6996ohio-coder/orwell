package dev.orwell.bucket.viewer;

import dev.orwell.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Cuts a video into JPEG frames by shelling out to ffmpeg.
 *
 * <p><strong>Why a subprocess and not a library.</strong> The JDK cannot decode video, and the two
 * Java options both cost more than they are worth here: JavaCV/{@code FFmpegFrameGrabber} ships
 * hundreds of megabytes of platform-specific natives into the image, and a pure-Java decoder handles
 * a fraction of the containers a phone or a screen recorder produces. The repo already treats ffmpeg
 * as the tool for this — {@code apps/jarvis/clients/{linux,mac}} record with it and the syncer concats
 * with it — so this is the same dependency the recording side already has, on the server side of the
 * same pipeline. It is what the runtime image installs it for.
 *
 * <p><strong>Frames go to disk, never through the heap.</strong> ffmpeg writes numbered files into a
 * scratch directory and the caller reads them back one at a time as it pushes them. A ten-minute
 * upload is a few hundred frames; holding them all in memory would be tens of megabytes for no
 * reason, and the frame count is the caller's choice rather than something this can bound.
 */
final class VideoFrames {
    /** How long ffmpeg gets before it is killed. A long upload is slow, but not this slow. */
    private static final long TIMEOUT_MINUTES = 30;
    /**
     * Frame files are named so that lexical order is capture order — six digits, so a 1 fps
     * extraction stays ordered past eleven days of footage. Order matters: it is what makes the
     * timestamps this app assigns line up with the sequence the frames were recorded in.
     */
    private static final String PATTERN = "frame-%06d.jpg";

    private final String ffmpeg;
    private final Logger logger;

    VideoFrames(String ffmpeg, Logger logger) {
        this.ffmpeg = Objects.requireNonNull(ffmpeg, "ffmpeg");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Thrown when the video cannot be turned into frames, for whatever reason ffmpeg gives. */
    static final class ExtractionException extends Exception {
        ExtractionException(String message) {
            super(message);
        }

        ExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Extracts frames from {@code video} into {@code into}, and returns them in capture order.
     *
     * @param framesPerSecond how many frames to take per second of video. This is a sampling rate,
     *                        not the video's own frame rate: asking for 1 takes one frame a second
     *                        however fast the source was recorded, which is what keeps an hour of
     *                        footage from becoming 90,000 rows of a table that is capped at 2 GiB.
     */
    List<Path> extract(Path video, Path into, double framesPerSecond) throws ExtractionException {
        if (framesPerSecond <= 0) {
            throw new ExtractionException("fps must be greater than zero");
        }
        List<String> command = List.of(
                ffmpeg,
                // No stdin: without this ffmpeg can block waiting on a terminal it does not have.
                "-nostdin",
                "-hide_banner",
                // Only errors, so the captured output is short enough to put in a job's error field.
                "-loglevel", "error",
                "-i", video.toString(),
                "-vf", "fps=" + framesPerSecond,
                // Constant quality rather than a bitrate: each frame is stored on its own, so there
                // is no stream for a bitrate to apply to. 3 is visually close to source for a JPEG.
                "-q:v", "3",
                "-f", "image2",
                into.resolve(PATTERN).toString());

        Path ffmpegLog = null;
        try {
            // ffmpeg's output goes to a file rather than to a pipe this thread has to drain.
            //
            // Draining on this thread first — which is what this did — reads to EOF, and EOF only
            // arrives when ffmpeg exits. So waitFor(timeout) always ran against an already-dead
            // process and the timeout could never fire; an ffmpeg wedged on a malformed container
            // blocked here forever, taking the single backfill worker with it and leaving every
            // later upload queued behind a job stuck in EXTRACTING. Draining *after* waitFor would
            // be the opposite bug: a full pipe buffer would deadlock the child. A file write blocks
            // on neither, so the timeout below is the only thing that decides how long this runs.
            ffmpegLog = Files.createTempFile("ffmpeg-", ".log");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ffmpegLog.toFile())
                    .start();
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new ExtractionException(
                        "ffmpeg did not finish within " + TIMEOUT_MINUTES + " minutes");
            }
            if (process.exitValue() != 0) {
                String output = readQuietly(ffmpegLog);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("exitCode", process.exitValue());
                metadata.put("output", output);
                logger.error("ffmpeg could not read an uploaded video.", metadata);
                throw new ExtractionException(shortened(output.isBlank()
                        ? "ffmpeg exited with " + process.exitValue()
                        : output));
            }
        } catch (IOException exception) {
            // Overwhelmingly the binary not being on PATH, which is worth saying plainly rather
            // than surfacing as a bare IOException in a job's error field.
            throw new ExtractionException(
                    "could not run '" + ffmpeg + "': " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("frame extraction was interrupted", exception);
        } finally {
            if (ffmpegLog != null) {
                try {
                    Files.deleteIfExists(ffmpegLog);
                } catch (IOException ignored) {
                }
            }
        }

        List<Path> frames = listFrames(into);
        if (frames.isEmpty()) {
            // ffmpeg exits 0 having written nothing if the input has no video stream — an audio-only
            // file, say. That is a client error, not a server one, and it needs saying.
            throw new ExtractionException(
                    "no frames came out of that file; is it a video with a picture in it?");
        }
        return frames;
    }

    /** True if the configured ffmpeg can be run at all, which is what gates the upload route. */
    boolean available() {
        try {
            // DISCARD rather than draining this thread: `ffmpeg -version` is short, but a binary
            // that is not really ffmpeg can sit there saying nothing, and reading to EOF first
            // would hang startup before the listener binds. Nothing here wants the output.
            Process process = new ProcessBuilder(List.of(ffmpeg, "-version"))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<Path> listFrames(Path directory) throws ExtractionException {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> frames = new ArrayList<>(files
                    .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                    .toList());
            // Sorted by name, which the zero-padded pattern makes the same as sorted by time.
            frames.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return frames;
        } catch (IOException exception) {
            throw new ExtractionException("could not read the extracted frames", exception);
        }
    }

    /** ffmpeg's captured output, or an empty string if it cannot be read — never the reason a job fails. */
    private static String readQuietly(Path log) {
        try {
            return Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }

    /** ffmpeg can be verbose even at error level, and this ends up in a JSON field somebody reads. */
    private static String shortened(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "…";
    }
}
