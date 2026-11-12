package com.checkupai.report.followup;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 글자 2-gram BM25 (ADR 0011 결과, 2026-11-12 사용자 결정). 형태소 분석 없이 공백을 지운 글을 두 글자씩 잘라 비교한다.
 * 한국어는 조사가 붙어("검사를", "검사가") 낱말째로 비교하면 맞지 않는다. 두 글자씩 자르면 "검사"가 그대로 겹친다.
 *
 * <p>평가 스크립트(eval/rag/run_followup_context_eval.py의 bm25_rank)와 같은 식이다: k1 = 1.2, b = 0.75,
 * idf = ln(1 + (n - df + 0.5) / (df + 0.5)). 질의의 2-gram은 한 번씩만 센다.
 */
final class CharBigramBm25 {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private CharBigramBm25() {
    }

    /** 문서마다 질의에 대한 점수 (입력 순서 그대로) */
    static double[] scores(String query, List<String> documents) {
        List<Map<String, Integer>> docs = documents.stream().map(CharBigramBm25::counts).toList();
        int n = docs.size();
        double avg = docs.stream().mapToInt(CharBigramBm25::length).average().orElse(1.0);
        Map<String, Integer> df = new HashMap<>();
        docs.forEach(doc -> doc.keySet().forEach(term -> df.merge(term, 1, Integer::sum)));
        Set<String> terms = new HashSet<>(counts(query).keySet());
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Integer> doc = docs.get(i);
            int length = length(doc);
            double score = 0;
            for (String term : terms) {
                int tf = doc.getOrDefault(term, 0);
                if (tf > 0) {
                    int d = df.get(term);
                    double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
                    score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * length / avg));
                }
            }
            out[i] = score;
        }
        return out;
    }

    static Map<String, Integer> counts(String text) {
        String squashed = text == null ? "" : text.replaceAll("\\s+", "");
        Map<String, Integer> out = new HashMap<>();
        // 코드 포인트 단위로 자른다 (Python의 문자열 자르기와 같게)
        int[] cps = squashed.codePoints().toArray();
        for (int i = 0; i + 1 < cps.length; i++) {
            out.merge(new String(cps, i, 2), 1, Integer::sum);
        }
        return out;
    }

    private static int length(Map<String, Integer> doc) {
        return doc.values().stream().mapToInt(Integer::intValue).sum();
    }
}
