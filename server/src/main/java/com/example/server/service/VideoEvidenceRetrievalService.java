package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.RetrievalReport;
import com.example.server.dto.RetrievalReport.Strategy;
import java.util.ArrayList;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.dto.VideoRetrievalIntent;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 查询感知的视频证据检索：语义召回负责“说了什么”，OCR 通道负责“画面写了什么”。 */
@Service
public class VideoEvidenceRetrievalService {

    private static final int TOP_K = 3;
    private static final int MAX_USER_HITS = 8;
    private static final int MAX_SNIPPET_LENGTH = 180;

    private final DeepSeekUtils deepSeekUtils;
    private final EmbeddingUtils embeddingUtils;
    private final QdrantVectorStore vectorStore;
    private final AgentTelemetry telemetry;

    public VideoEvidenceRetrievalService(DeepSeekUtils deepSeekUtils,
                                         EmbeddingUtils embeddingUtils,
                                         QdrantVectorStore vectorStore,
                                         AgentTelemetry telemetry) {
        this.deepSeekUtils = deepSeekUtils;
        this.embeddingUtils = embeddingUtils;
        this.vectorStore = vectorStore;
        this.telemetry = telemetry;
    }

    public List<VideoContext.VideoSegment> retrieve(Long mediaId,
                                                     String goal,
                                                     List<VideoChunk> chunks) {
        return rank(mediaId, goal, chunks, Strategy.HYBRID, new ArrayList<>()).segments().stream()
                .map(ScoredSegment::segment)
                .toList();
    }

    public List<VideoEvidenceHit> search(Long mediaId,
                                         String query,
                                         List<VideoChunk> chunks) {
        return explain(mediaId, query, chunks, Strategy.HYBRID).hits();
    }

    public RetrievalReport explain(Long mediaId, String query, List<VideoChunk> chunks, Strategy strategy) {
        List<String> fallbacks = new ArrayList<>();
        Ranking ranking = rank(mediaId, query, chunks, strategy, fallbacks);
        return new RetrievalReport(query, ranking.intent(), strategy, List.copyOf(fallbacks),
                ranking.segments().stream().limit(MAX_USER_HITS).map(segment ->
                        new RetrievalReport.ScoredHit(toHit(segment), segment.score(),
                                segment.chunk().score(), segment.chunk().semantic(), segment.transcriptScore(),
                                segment.visualScore(), segment.chunk().vectorSource())).toList());
    }

    private Ranking rank(Long mediaId, String goal, List<VideoChunk> chunks,
                         Strategy strategy, List<String> fallbacks) {
        VideoRetrievalIntent intent = retrievalIntent(goal, fallbacks);
        List<Double> queryEmbedding = strategy == Strategy.KEYWORD
                ? List.of() : embed(intent.semanticQuery(), fallbacks);
        Map<String, Double> vectorScores = vectorScores(mediaId, queryEmbedding, fallbacks);
        List<ScoredChunk> rankedChunks = chunks.stream()
                .map(chunk -> score(intent, queryEmbedding, vectorScores, chunk, strategy))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparingLong(chunk -> chunk.chunk().startTime()))
                .limit(TOP_K).toList();
        if (strategy != Strategy.KEYWORD && rankedChunks.stream()
                .anyMatch(chunk -> chunk.vectorSource().equals("UNAVAILABLE"))) {
            fallbacks.add("部分片段缺少兼容向量，语义分数为 0");
        }
        if (!rankedChunks.isEmpty()) {
            telemetry.valueCurrent("retrievalTopScore", rankedChunks.getFirst().score());
            telemetry.incrementCurrent("retrievalChunks", rankedChunks.size());
        }
        List<ScoredSegment> segments = rankedChunks.stream()
                .flatMap(chunk -> chunk.chunk().rawSegments().stream()
                        .map(segment -> scoreSegment(intent, chunk, segment, strategy)))
                .sorted(Comparator.comparingDouble(ScoredSegment::score).reversed()
                        .thenComparingLong(result -> result.segment().startMs())).toList();
        telemetry.retrievalCurrent(new RetrievalReport(goal, intent, strategy, List.copyOf(fallbacks),
                segments.stream().map(segment -> new RetrievalReport.ScoredHit(toHit(segment),
                        segment.score(), segment.chunk().score(), segment.chunk().semantic(), segment.transcriptScore(),
                        segment.visualScore(), segment.chunk().vectorSource())).toList()));
        return new Ranking(intent, segments);
    }

    public void index(Long mediaId, List<VideoChunk> chunks) {
        try {
            vectorStore.upsert(mediaId, chunks);
            telemetry.incrementCurrent("vectorStoreWrites", chunks.size());
        } catch (RuntimeException e) {
            // 向量库挂了仍可走内存向量和关键词，别让检索基础设施拖垮分析主链路。
            telemetry.incrementCurrent("vectorStoreFallbacks", 1);
        }
    }

    private ScoredChunk score(VideoRetrievalIntent intent, List<Double> queryEmbedding,
                              Map<String, Double> vectorScores, VideoChunk chunk, Strategy strategy) {
        Double remote = vectorScores.get(chunkKey(chunk));
        boolean compatible = !queryEmbedding.isEmpty() && queryEmbedding.size() == chunk.embedding().size();
        double semantic = remote == null ? cosine(queryEmbedding, chunk.embedding()) : remote;
        String source = remote != null ? "QDRANT" : compatible ? "LOCAL" : "UNAVAILABLE";
        if (strategy == Strategy.KEYWORD) source = "NOT_REQUESTED";
        double keyword = termScore(intent.keywords(), searchableText(chunk));
        double visual = termScore(intent.visualKeywords(), visualText(chunk));
        double score = switch (strategy) {
            case KEYWORD -> keyword * 0.625 + visual * 0.375;
            case VECTOR -> semantic;
            case HYBRID -> semantic * 0.6 + keyword * 0.25 + visual * 0.15;
        };
        return new ScoredChunk(chunk, score, semantic, source);
    }

    private Map<String, Double> vectorScores(Long mediaId, List<Double> queryEmbedding,
                                              List<String> fallbacks) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (mediaId == null || queryEmbedding.isEmpty()) return scores;
        try {
            vectorStore.search(mediaId, queryEmbedding, TOP_K * 2).forEach(hit ->
                    scores.put(hit.startMs() + ":" + hit.endMs(), hit.score()));
            if (scores.isEmpty()) fallbacks.add("Qdrant 未返回结果，使用已有本地向量");
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("vectorStoreFallbacks", 1);
            fallbacks.add("Qdrant 不可用，使用已有本地向量");
        }
        return scores;
    }

    private ScoredSegment scoreSegment(VideoRetrievalIntent intent, ScoredChunk chunk,
                                        VideoContext.VideoSegment segment, Strategy strategy) {
        double transcriptScore = termScore(intent.keywords(), segment.transcript());
        double visualScore = termScore(intent.visualKeywords(), String.join(" ", normalizedOcrTexts(segment)));
        double score = strategy == Strategy.VECTOR ? chunk.score()
                : chunk.score() * 0.55 + transcriptScore * 0.25 + visualScore * 0.20;
        return new ScoredSegment(segment, score, transcriptScore, visualScore, chunk);
    }

    private VideoEvidenceHit toHit(ScoredSegment result) {
        VideoContext.VideoSegment segment = result.segment();
        List<String> ocrTexts = normalizedOcrTexts(segment);
        boolean hasTranscript = !segment.transcript().isBlank();
        boolean hasOcr = !ocrTexts.isEmpty();
        String source = hasTranscript && hasOcr
                ? "ASR+OCR"
                : hasOcr ? "OCR" : hasTranscript ? "ASR" : "时间片段";
        String ocrText = String.join(" ", ocrTexts);
        String preferred = result.visualScore() > result.transcriptScore() ? ocrText : segment.transcript();
        if (preferred.isBlank()) preferred = hasOcr ? ocrText : segment.transcript();
        if (preferred.isBlank()) preferred = "该时间段暂无可展示文本";
        return new VideoEvidenceHit(
                segment.startMs(),
                segment.endMs(),
                source,
                abbreviate(preferred),
                segment.transcript(),
                ocrTexts);
    }

    private String searchableText(VideoChunk chunk) {
        return String.join(" ",
                chunk.segmentSummary(),
                String.join(" ", chunk.keywords()),
                chunk.rawSegments().stream()
                        .map(VideoContext.VideoSegment::transcript)
                        .collect(java.util.stream.Collectors.joining(" ")));
    }

    private String visualText(VideoChunk chunk) {
        return chunk.rawSegments().stream()
                .flatMap(segment -> normalizedOcrTexts(segment).stream())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private List<String> normalizedOcrTexts(VideoContext.VideoSegment segment) {
        return segment.ocrTexts().stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .distinct()
                .toList();
    }

    private double termScore(List<String> terms, String content) {
        String normalizedContent = normalize(content);
        List<String> normalizedTerms = terms.stream()
                .map(this::normalize)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
        long matched = normalizedTerms.stream().filter(normalizedContent::contains).count();
        return normalizedTerms.isEmpty() ? 0 : (double) matched / normalizedTerms.size();
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) return 0;
        double dot = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int i = 0; i < left.size(); i++) {
            dot += left.get(i) * right.get(i);
            leftLength += left.get(i) * left.get(i);
            rightLength += right.get(i) * right.get(i);
        }
        if (leftLength == 0 || rightLength == 0) return 0;
        return dot / (Math.sqrt(leftLength) * Math.sqrt(rightLength));
    }

    private VideoRetrievalIntent retrievalIntent(String goal, List<String> fallbacks) {
        try {
            VideoRetrievalIntent intent = deepSeekUtils.planRetrieval(goal);
            if (!intent.semanticQuery().isBlank()) return intent;
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("retrievalIntentFallbacks", 1);
        }
        fallbacks.add("检索问题改写不可用，使用原始问题");
        return new VideoRetrievalIntent(goal, fallbackTerms(goal), fallbackTerms(goal));
    }

    private List<Double> embed(String text, List<String> fallbacks) {
        try {
            List<Double> vector = embeddingUtils.embed(text);
            if (vector.isEmpty()) fallbacks.add("Embedding 为空，语义分数为 0");
            return vector;
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("embeddingFallbacks", 1);
            fallbacks.add("Embedding 不可用，语义分数为 0");
            return List.of();
        }
    }

    private String chunkKey(VideoChunk chunk) {
        return chunk.startTime() + ":" + chunk.endTime();
    }

    private List<String> fallbackTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        List<String> terms = java.util.Arrays.stream(query.trim().split("[\\s，。！？、,.;:：；!?]+"))
                .map(String::trim)
                .filter(term -> term.length() >= 2)
                .distinct()
                .limit(8)
                .toList();
        return terms.isEmpty() ? List.of(query.trim()) : terms;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }

    private String abbreviate(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_SNIPPET_LENGTH
                ? normalized
                : normalized.substring(0, MAX_SNIPPET_LENGTH) + "...";
    }

    private record Ranking(VideoRetrievalIntent intent, List<ScoredSegment> segments) { }

    private record ScoredChunk(VideoChunk chunk, double score, double semantic, String vectorSource) {
    }

    private record ScoredSegment(
            VideoContext.VideoSegment segment,
            double score,
            double transcriptScore,
            double visualScore,
            ScoredChunk chunk
    ) {
    }
}
