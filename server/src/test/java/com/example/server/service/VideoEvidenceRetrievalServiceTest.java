package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.dto.RetrievalReport.Strategy;
import com.example.server.utils.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VideoEvidenceRetrievalServiceTest {
    private final DeepSeekUtils model = mock(DeepSeekUtils.class);
    private final EmbeddingUtils embedding = mock(EmbeddingUtils.class);
    private final QdrantVectorStore store = mock(QdrantVectorStore.class);
    private final VideoEvidenceRetrievalService service = new VideoEvidenceRetrievalService(
            model, embedding, store, mock(AgentTelemetry.class));

    private List<VideoChunk> chunks() {
        return List.of(chunk(0, "苹果", List.of(1.0, 0.0)),
                chunk(300000, "香蕉", List.of(0.0, 1.0)));
    }

    private VideoChunk chunk(long start, String text, List<Double> vector) {
        return new VideoChunk(start, start + 300000, text, List.of(text),
                List.of(new VideoContext.VideoSegment(start, start + 60000, text, List.of(), List.of())), vector);
    }

    @Test void keywordAvoidsEmbeddingsAndReportsItsRanking() {
        when(model.planRetrieval("问题")).thenReturn(new VideoRetrievalIntent("问题", List.of("香蕉"), List.of()));
        var report = service.explain(1L, "问题", chunks(), Strategy.KEYWORD);
        assertEquals(300000, report.hits().getFirst().startMs());
        assertEquals(1.0, report.candidates().getFirst().keywordScore());
        verifyNoInteractions(embedding, store);
    }

    @Test void vectorFailureFallsBackToLocalVectorsWithVisibleReason() {
        when(model.planRetrieval("问题")).thenReturn(new VideoRetrievalIntent("问题", List.of("苹果"), List.of()));
        when(embedding.embed("问题")).thenReturn(List.of(0.0, 1.0));
        when(store.search(anyLong(), anyList(), anyInt())).thenThrow(new IllegalStateException("offline"));
        var report = service.explain(1L, "问题", chunks(), Strategy.VECTOR);
        assertEquals(300000, report.hits().getFirst().startMs());
        assertEquals("LOCAL", report.candidates().getFirst().vectorSource());
        assertTrue(report.fallbacks().stream().anyMatch(reason -> reason.contains("Qdrant")));
    }

    @Test void qdrantScoresAreReportedAndOverrideLocalSimilarity() {
        when(model.planRetrieval("问题")).thenReturn(new VideoRetrievalIntent("问题", List.of(), List.of()));
        when(embedding.embed("问题")).thenReturn(List.of(1.0, 0.0));
        when(store.search(anyLong(), anyList(), anyInt())).thenReturn(List.of(
                new QdrantVectorStore.VectorHit(0, 300000, 0.1),
                new QdrantVectorStore.VectorHit(300000, 600000, 0.9)));
        var report = service.explain(1L, "问题", chunks(), Strategy.VECTOR);
        assertEquals(300000, report.hits().getFirst().startMs());
        assertEquals("QDRANT", report.candidates().getFirst().vectorSource());
        assertEquals(0.9, report.candidates().getFirst().semanticScore());
        assertTrue(report.fallbacks().isEmpty());
    }

    @Test void failedQueryAndEmbeddingStillReturnKeywordEvidenceAndExplainDegradation() {
        when(model.planRetrieval(anyString())).thenThrow(new IllegalStateException("offline"));
        when(embedding.embed(anyString())).thenThrow(new IllegalStateException("offline"));
        var report = service.explain(1L, "香蕉", chunks(), Strategy.HYBRID);
        assertEquals(300000, report.hits().getFirst().startMs());
        assertEquals(0.0, report.candidates().getFirst().semanticScore());
        assertEquals(3, report.fallbacks().size());
        assertEquals(service.search(1L, "香蕉", chunks()), report.hits());
    }
}
