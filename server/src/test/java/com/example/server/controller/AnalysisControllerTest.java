package com.example.server.controller;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AnalysisMode;
import com.example.server.entity.MediaFile;
import com.example.server.service.*;
import com.example.server.service.mode.ModeRouter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisControllerTest {
    @Test
    void detailedSearchReturnsReportThroughNormalEntryPoint() {
        AiService ai = mock(AiService.class);
        MediaService media = mock(MediaService.class);
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        when(checkpoints.loadContext(7L)).thenReturn(new com.example.server.dto.VideoContext("video", "", List.of()));
        var report = new com.example.server.dto.RetrievalReport("question",
                new com.example.server.dto.VideoRetrievalIntent("question", List.of(), List.of()),
                com.example.server.dto.RetrievalReport.Strategy.HYBRID, List.of(), List.of());
        when(ai.explainEvidence(7L, "question", report.strategy())).thenReturn(report);
        AnalysisDispatchService dispatch = mock(AnalysisDispatchService.class);
        AnalysisController controller = new AnalysisController(ai, dispatch, checkpoints,
                mock(AgentEvaluationService.class), mock(AgentTelemetry.class), media,
                mock(TaskEventService.class), mock(AnalysisStatusService.class), mock(ModeRouter.class), Runnable::run);
        assertEquals(report, controller.explainEvidence(7L, "question", report.strategy(), 3L).join().data());
        verify(media).requireOwnedMedia(7L, 3L);
        verify(dispatch).requireAiQuota(3L);
    }

    @Test
    void detailedSearchChecksOwnershipBeforeCallingModels() {
        AiService ai = mock(AiService.class);
        MediaService media = mock(MediaService.class);
        when(media.requireOwnedMedia(7L, 3L)).thenThrow(new SecurityException("not owner"));
        AnalysisController controller = new AnalysisController(ai, mock(AnalysisDispatchService.class),
                mock(AgentCheckpointService.class), mock(AgentEvaluationService.class),
                mock(AgentTelemetry.class), media, mock(TaskEventService.class),
                mock(AnalysisStatusService.class), mock(ModeRouter.class), Runnable::run);
        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,
                () -> controller.explainEvidence(7L, "question",
                        com.example.server.dto.RetrievalReport.Strategy.HYBRID, 3L));
        verifyNoInteractions(ai);
    }

    @Test
    void revisionUsesBodyModeWhenQueryIsMissingAndAllowsExplicitQueryOverride() {
        AiService ai = mock(AiService.class);
        AnalysisDispatchService dispatch = mock(AnalysisDispatchService.class);
        MediaService media = mock(MediaService.class);
        MediaFile owned = new MediaFile();
        when(media.requireOwnedMedia(7L, 3L)).thenReturn(owned);
        when(ai.revisionGoal(any())).thenReturn("goal");
        when(dispatch.submit(any(), anyString(), any(), any()))
                .thenReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED);
        AnalysisController controller = new AnalysisController(ai, dispatch,
                mock(AgentCheckpointService.class), mock(AgentEvaluationService.class),
                mock(AgentTelemetry.class), media, mock(TaskEventService.class),
                mock(AnalysisStatusService.class), mock(ModeRouter.class), Runnable::run);
        AgentFeedback feedback = new AgentFeedback(7L, "goal", "LEARNING", null,
                null, null, null, List.of("new task"), null, null, null);

        assertEquals(202, controller.reviseAgentResult(feedback, null, 3L).getStatusCode().value());
        verify(dispatch).submit(owned, "goal", feedback, AnalysisMode.LEARNING);
        assertEquals(202, controller.reviseAgentResult(feedback, "REVIEW", 3L).getStatusCode().value());
        verify(dispatch).submit(owned, "goal", feedback, AnalysisMode.REVIEW);
    }
}
