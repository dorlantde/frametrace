package com.example.server.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class MediaDuration {
    private MediaDuration() { }

    public static long readMillis(String source) throws Exception {
        Path output = Files.createTempFile("frametrace-duration-", ".txt");
        Process process = null;
        try {
            process = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries",
                    "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", source)
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Video duration probe timed out");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("Video duration probe failed");
            return parseMillis(Files.readString(output).trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    static long parseMillis(String value) {
        double seconds = Double.parseDouble(value);
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > Long.MAX_VALUE / 1000.0) {
            throw new IllegalArgumentException("Invalid media duration");
        }
        return Math.max(1, Math.round(seconds * 1000));
    }
}
