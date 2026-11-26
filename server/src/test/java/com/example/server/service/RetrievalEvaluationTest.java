package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.dto.RetrievalReport.Strategy;
import com.example.server.utils.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetrievalEvaluationTest {
    record Question(String query, List<String> keywords, List<String> visualKeywords,
                    long expectedStartMs, long expectedEndMs) { }
    record Dataset(String description, List<VideoChunk> chunks, List<Question> questions) { }

    @Test
    @EnabledIfSystemProperty(named = "retrieval.live", matches = "true")
    void compareStrategiesWithLiveEmbeddings() throws Exception {
        var mapper = new ObjectMapper();
        Dataset data;
        try (var input = getClass().getResourceAsStream("/evaluation/retrieval-cases.json")) {
            data = mapper.readValue(input, Dataset.class);
        }
        String key = System.getenv().getOrDefault("EMBEDDING_API_KEY", System.getenv("SILICONFLOW_API_KEY"));
        String base = System.getenv().getOrDefault("EMBEDDING_BASE_URL",
                System.getenv().getOrDefault("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1"));
        String modelName = System.getenv().getOrDefault("EMBEDDING_MODEL", "BAAI/bge-m3");
        assertNotNull(key, "Set embedding credentials for the live benchmark");
        var live = new EmbeddingUtils(key, base, modelName);
        List<VideoChunk> chunks = new ArrayList<>();
        for (var chunk : data.chunks()) {
            var vector = live.embed(chunk.segmentSummary() + "\n" + String.join(" ", chunk.keywords()));
            chunks.add(new VideoChunk(chunk.startTime(), chunk.endTime(), chunk.segmentSummary(),
                    chunk.keywords(), chunk.rawSegments(), vector));
        }
        var planner = mock(DeepSeekUtils.class);
        var embedding = mock(EmbeddingUtils.class);
        for (var question : data.questions()) {
            when(planner.planRetrieval(question.query())).thenReturn(new VideoRetrievalIntent(
                    question.query(), question.keywords(), question.visualKeywords()));
            when(embedding.embed(question.query())).thenReturn(live.embed(question.query()));
        }
        var retrieval = new VideoEvidenceRetrievalService(planner, embedding,
                mock(QdrantVectorStore.class), mock(AgentTelemetry.class));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataset", data.description());
        report.put("embeddingModel", modelName);
        report.put("method", "Fixed manually authored queries and keywords; live embeddings; local cosine; shared production ranking");
        report.put("samples", data.questions().size());
        Map<String, Object> strategies = new LinkedHashMap<>();
        for (Strategy strategy : Strategy.values()) {
            int hit1 = 0;
            int hit3 = 0;
            double reciprocalRank = 0;
            List<Object> results = new ArrayList<>();
            for (var question : data.questions()) {
                var result = retrieval.explain(null, question.query(), chunks, strategy);
                int rank = 0;
                for (int index = 0; index < result.hits().size(); index++) {
                    var hit = result.hits().get(index);
                    if (hit.startMs() < question.expectedEndMs() && hit.endMs() > question.expectedStartMs()) {
                        rank = index + 1;
                        break;
                    }
                }
                if (rank == 1) hit1++;
                if (rank > 0 && rank <= 3) hit3++;
                if (rank > 0) reciprocalRank += 1.0 / rank;
                results.add(Map.of("query", question.query(), "expectedStartMs", question.expectedStartMs(),
                        "rank", rank, "retrieval", result));
            }
            strategies.put(strategy.name(), Map.of("hitAt1", (double) hit1 / data.questions().size(),
                    "hitAt3", (double) hit3 / data.questions().size(),
                    "mrrAt8", reciprocalRank / data.questions().size(), "results", results));
        }
        report.put("strategies", strategies);
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/retrieval-evaluation.json").toFile(), report);
        assertEquals(3, strategies.size());
    }
}
