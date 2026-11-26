package com.example.server.dto;

import java.util.List;

public record RetrievalReport(String query, VideoRetrievalIntent intent, Strategy strategy,
                              List<String> fallbacks, List<ScoredHit> candidates) {
    public enum Strategy { KEYWORD, VECTOR, HYBRID }

    public record ScoredHit(VideoEvidenceHit evidence, double score, double chunkScore, double semanticScore,
                            double keywordScore, double ocrScore, String vectorSource) { }

    public List<VideoEvidenceHit> hits() {
        return candidates.stream().map(ScoredHit::evidence).toList();
    }
}
