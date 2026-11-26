package com.example.server.service;
import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class VideoContextDurationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @Test void actualShortAudioKeepsItsEndTime() throws Exception {
        var source = directory.resolve("sample.wav");
        Process process;
        try {
            process = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                    "sine=frequency=440:duration=30.25", source.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        } catch (java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "FFmpeg is unavailable");
            return;
        }
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        var asr = org.mockito.Mockito.mock(com.example.server.utils.AliyunAsrUtils.class);
        org.mockito.Mockito.when(asr.audioToText(org.mockito.ArgumentMatchers.anyString())).thenReturn("speech");
        var service = new SegmentedTranscriptionService(asr, org.mockito.Mockito.mock(AgentTelemetry.class));
        var segments = service.transcribe(source.toString(), directory.resolve("chunks"), "test");
        assertEquals(1, segments.size());
        assertEquals(30250, segments.getFirst().endMs());
    }

    @Test void clipsShortVideoAndDropsOutOfRangeFrames() {
        var context = new VideoContext("video", "goal", List.of(
                new VideoContext.VideoSegment(0, 60000, "speech", List.of("text"), List.of("frame")),
                new VideoContext.VideoSegment(60000, 120000, "", List.of(), List.of())));
        var result = VideoContextService.trimToDuration(context, 30500);
        assertEquals(1, result.segments().size());
        assertEquals(30500, result.segments().getFirst().endMs());
        assertEquals("speech", result.segments().getFirst().transcript());
        assertEquals(60000, VideoContextService.trimToDuration(context, 60000).segments().getFirst().endMs());
        assertEquals(90500, VideoContextService.trimToDuration(context, 90500).segments().getLast().endMs());
    }
}
