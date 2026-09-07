package dev.nuclr.plugin.core.assimp.blender;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs an external command with a timeout and a cancellation token, capturing
 * its merged output.
 *
 * <p>Every wait is bounded: the caller is a quick-view load that the user can
 * walk away from at any moment by moving the selection, so a child process must
 * never outlive interest in its result.
 */
@Slf4j
final class ProcessRunner {

    /** How often the wait loop rechecks cancellation and the deadline. */
    private static final long POLL_INTERVAL_MS = 100;

    /** Output kept for diagnostics. Blender is chatty; only the tail is useful. */
    private static final int MAX_CAPTURED_CHARS = 64_000;

    /** Grace period for a killed process to actually disappear. */
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);

    enum Status {
        /** The process exited on its own; {@code exitCode} is meaningful. */
        COMPLETED,
        /** Killed after exceeding the timeout. */
        TIMED_OUT,
        /** Killed because the caller lost interest. */
        CANCELLED,
        /** Never started; {@code failure} says why. */
        START_FAILED
    }

    record Outcome(Status status, int exitCode, String output, String failure) {

        boolean succeeded() {
            return status == Status.COMPLETED && exitCode == 0;
        }
    }

    private ProcessRunner() {
    }

    static Outcome run(List<String> command, Path workingDirectory, Duration timeout, AtomicBoolean cancelled) {

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException | SecurityException e) {
            return new Outcome(Status.START_FAILED, -1, "", e.getMessage());
        }

        // Nothing is ever written to the child. Leaving stdin open would let a
        // process that decides to prompt wait forever for an answer.
        closeQuietly(process.getOutputStream());

        StringBuilder captured = new StringBuilder();
        Thread drain = Thread.ofVirtual()
                             .name("blender-output")
                             .start(() -> drain(process.getInputStream(), captured));

        Status status = await(process, timeout, cancelled);
        if (status != Status.COMPLETED) {
            terminate(process);
        }

        try {
            drain.join(TERMINATION_GRACE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int exitCode = process.isAlive() ? -1 : process.exitValue();
        String output;
        synchronized (captured) {
            output = captured.toString();
        }
        return new Outcome(status, exitCode, output, null);
    }

    private static Status await(Process process, Duration timeout, AtomicBoolean cancelled) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                if (cancelled != null && cancelled.get()) {
                    return Status.CANCELLED;
                }
                if (System.nanoTime() - deadline > 0) {
                    return Status.TIMED_OUT;
                }
            }
            return Status.COMPLETED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Status.CANCELLED;
        }
    }

    /** Kills the process and anything it spawned, then waits for it to go. */
    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            if (!process.waitFor(TERMINATION_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("Process {} did not exit after being killed", process.pid());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void drain(InputStream stream, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (sink) {
                    sink.append(line).append('\n');
                    // Keep the tail rather than the head: whatever explains a
                    // failure is the last thing the process prints.
                    if (sink.length() > MAX_CAPTURED_CHARS * 2) {
                        sink.delete(0, sink.length() - MAX_CAPTURED_CHARS);
                    }
                }
            }
        } catch (IOException e) {
            // Expected when the process is killed mid-write; nothing to report.
            log.debug("Output stream closed early: {}", e.getMessage());
        }
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("Could not close the child's stdin: {}", e.getMessage());
        }
    }
}
