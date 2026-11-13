package com.checkupai.report.followup;

import com.checkupai.report.explanation.Judgment;
import com.checkupai.report.explanation.ScreeningCriteria;
import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.extraction.ReportItem;
import com.checkupai.report.retrieval.GuidelineCorpus;
import com.checkupai.report.retrieval.GuidelineSearch;
import com.checkupai.report.retrieval.RetrievedChunk;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 후속 질문의 문맥(LLM에 보낼 근거)을 코드가 정한다 (ADR 0011 2절). LLM을 부르지 않는다.
 * <ol>
 *   <li>항목: 화면에서 고른 항목 → 질문에 이름이 나온 항목 → 결과지의 정상B·질환의심 항목 순</li>
 *   <li>고정 근거(예산 밖): 질문 항목과 결과지 주의 항목의 판정기준표 행, 그 판정에 해당하는 판정 구분 정의</li>
 *   <li>글 범위: 질문 항목이 붙은 문서 절. 항목이 없으면 전체 문서</li>
 *   <li>범위 안 순위: 질의(질문 + 항목 이름)와 절의 글자 2-gram BM25 점수({@link CharBigramBm25}). 같으면 벡터 유사도
 *       (절 점수는 그 절 청크 점수의 최댓값). 처음 계획은 벡터였지만, 개발 몫에서 BM25가 모호한 질문의 근거를 더 잘 담고
 *       잃는 문항이 없어 바꿨다 (ADR 0011 결과, 2026-11-12 사용자 결정). 평가를 위해 벡터 순위도 고를 수 있다</li>
 *   <li>절 채우기: 순위대로 절을 통째로 넣되 예산(글자 수)을 넘으면 그 절은 건너뛴다.
 *       질문에 항목이 둘 이상이면(고름·질문 속 항목) 항목마다 돌아가며 그 항목의 다음 순위 절을 넣는다.
 *       한 항목의 절이 예산을 다 차지해 다른 항목의 근거가 빠지지 않게 한다 (2026-11-13, 두 항목의 관계 질문에서 정답 절이 빠짐)</li>
 * </ol>
 * 근거 단위는 정답셋의 근거 라벨과 같다: 고시는 행, 나머지는 "문서#절" ({@link GuidelineCorpus#unitOf}).
 */
@Component
public class FollowupContextBuilder {

    /** 범위 안 절 순위 방식. 서비스는 BM25, 벡터는 비교 평가용이다 */
    public enum Ranking { BM25, VECTOR }

    /** 항목을 어디서 정했는가 (ADR 0011 1절: 항목 고름 / 안 고름을 따로 잰다) */
    public enum ItemSource { SELECTED, MENTIONED, FLAGGED, NONE }

    /** 고혈압·당뇨병·이상지질혈증 질환의심 정의를 쓰는 항목 (고시 판정 구분) */
    private static final Set<ItemCode> CHRONIC = EnumSet.of(ItemCode.SYSTOLIC_BP, ItemCode.DIASTOLIC_BP,
            ItemCode.FASTING_GLUCOSE, ItemCode.TOTAL_CHOLESTEROL, ItemCode.HDL_CHOLESTEROL, ItemCode.TRIGLYCERIDES,
            ItemCode.LDL_CHOLESTEROL);
    private static final String DEF_NORMAL_B = "mohw-annex4-def-normal-b";
    private static final String DEF_SUSPECT = "mohw-annex4-def-suspect";
    private static final String DEF_SUSPECT_CHRONIC = "mohw-annex4-def-suspect-chronic";

    /**
     * @param id 근거 단위 (고시는 청크 id, 나머지는 "문서#절")
     * @param score 범위 안 BM25 점수 (고정 근거는 0)
     * @param vectorScore 범위 안 벡터 유사도 (절의 청크 중 최댓값, 고정 근거는 0)
     */
    public record ContextUnit(String id, String docId, String title, String section, double score,
                              double vectorScore, List<String> chunkIds, String text) {
    }

    /**
     * @param fixedUnits 예산 밖에서 늘 넣는 근거 (판정기준표 행, 판정 구분 정의)
     * @param rankedUnits 순위대로 예산 안에 채운 절
     * @param rankedChars 채운 절의 글자 수 (예산과 비교하는 값)
     */
    public record FollowupContext(ItemSource itemSource, List<ItemCode> textItems, List<ItemCode> rowItems,
                                  String query, Ranking ranking, int budgetChars, List<ContextUnit> fixedUnits,
                                  List<ContextUnit> rankedUnits, int rankedChars, int totalChars) {
        public List<ContextUnit> units() {
            List<ContextUnit> all = new ArrayList<>(fixedUnits);
            all.addAll(rankedUnits);
            return all;
        }
    }

    private final ScreeningCriteria criteria;
    private final ItemMentions mentions;
    private final GuidelineCorpus corpus;
    private final GuidelineSearch search;
    private final FollowupProperties properties;

    public FollowupContextBuilder(ScreeningCriteria criteria, ItemMentions mentions, GuidelineCorpus corpus,
                                  GuidelineSearch search, FollowupProperties properties) {
        this.criteria = criteria;
        this.mentions = mentions;
        this.corpus = corpus;
        this.search = search;
        this.properties = properties;
    }

    /**
     * @param selected 화면에서 고른 항목 ("이 항목 질문하기"). 없으면 null이나 빈 목록
     */
    public FollowupContext build(CheckupReport report, String question, Collection<ItemCode> selected, int budgetChars) {
        return build(report, question, selected, budgetChars, Ranking.BM25);
    }

    public FollowupContext build(CheckupReport report, String question, Collection<ItemCode> selected, int budgetChars,
                                 Ranking ranking) {
        Map<ItemCode, Judgment> judgments = judgments(report);
        Set<ItemCode> flagged = judgments.entrySet().stream()
                .filter(e -> e.getValue() == Judgment.SUSPECT || e.getValue() == Judgment.NORMAL_B)
                .map(Map.Entry::getKey).collect(Collectors.toCollection(() -> EnumSet.noneOf(ItemCode.class)));

        ItemSource source;
        Set<ItemCode> textItems;
        Set<ItemCode> mentioned = mentions.find(question);
        if (selected != null && !selected.isEmpty()) {
            source = ItemSource.SELECTED;
            textItems = EnumSet.copyOf(selected);
        } else if (!mentioned.isEmpty()) {
            source = ItemSource.MENTIONED;
            textItems = mentioned;
        } else if (!flagged.isEmpty()) {
            source = ItemSource.FLAGGED;
            textItems = flagged;
        } else {
            source = ItemSource.NONE;
            textItems = EnumSet.noneOf(ItemCode.class);
        }
        // 판정기준표 행은 질문 항목과 결과지 주의 항목을 함께 넣는다 ("나머지는 뭐가 안 좋은 거지" 같은 질문)
        Set<ItemCode> rowItems = EnumSet.noneOf(ItemCode.class);
        rowItems.addAll(textItems);
        rowItems.addAll(flagged);

        List<RetrievedChunk> chunks = corpus.chunks();
        List<ContextUnit> fixed = fixedUnits(chunks, rowItems, judgments);
        String query = (question.strip() + " " + textItems.stream().map(ItemCode::koreanName)
                .collect(Collectors.joining(" "))).strip();
        boolean balance = (source == ItemSource.SELECTED || source == ItemSource.MENTIONED) && textItems.size() >= 2;
        List<ContextUnit> ranked = rankedUnits(chunks, textItems, query, budgetChars, ranking, balance);
        int rankedChars = ranked.stream().mapToInt(u -> u.text().length()).sum();
        int fixedChars = fixed.stream().mapToInt(u -> u.text().length()).sum();
        return new FollowupContext(source, List.copyOf(textItems), List.copyOf(rowItems), query, ranking, budgetChars, fixed,
                ranked, rankedChars, rankedChars + fixedChars);
    }

    private Map<ItemCode, Judgment> judgments(CheckupReport report) {
        Map<ItemCode, Judgment> out = new EnumMap<>(ItemCode.class);
        if (report == null || report.items() == null) {
            return out;
        }
        for (ReportItem item : report.items()) {
            if (item.code() == null || item.code() == ItemCode.OTHER || out.containsKey(item.code())) {
                continue;
            }
            out.put(item.code(), criteria.judge(item.code(), item.value(), item.unit(), report.sex()).judgment());
        }
        return out;
    }

    private static List<ContextUnit> fixedUnits(List<RetrievedChunk> chunks, Set<ItemCode> rowItems,
                                                Map<ItemCode, Judgment> judgments) {
        Set<String> definitions = new LinkedHashSet<>();
        for (ItemCode code : rowItems) {
            Judgment judgment = judgments.get(code);
            if (judgment == Judgment.NORMAL_B) {
                definitions.add(DEF_NORMAL_B);
            } else if (judgment == Judgment.SUSPECT) {
                definitions.add(CHRONIC.contains(code) ? DEF_SUSPECT_CHRONIC : DEF_SUSPECT);
            }
        }
        List<ContextUnit> out = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            if (!GuidelineCorpus.CRITERIA_DOC.equals(chunk.metadataText("doc_id"))) {
                continue;
            }
            boolean row = !GuidelineCorpus.JUDGMENT_DEFINITION_SECTION.equals(chunk.metadataText("section"))
                    && itemCodes(chunk).stream().anyMatch(code -> rowItems.contains(code));
            if (row || definitions.contains(chunk.id())) {
                out.add(unit(GuidelineCorpus.unitOf(chunk), List.of(chunk), 0.0, 0.0));
            }
        }
        return out;
    }

    private List<ContextUnit> rankedUnits(List<RetrievedChunk> chunks, Set<ItemCode> textItems, String query,
                                          int budgetChars, Ranking ranking, boolean balance) {
        // 범위: 고시가 아닌 문서의 청크 중 질문 항목이 붙은 것. 항목이 없으면 전체
        Map<String, List<RetrievedChunk>> sections = new LinkedHashMap<>();
        Map<String, Set<ItemCode>> sectionItems = new HashMap<>();
        for (RetrievedChunk chunk : chunks) {
            if (GuidelineCorpus.CRITERIA_DOC.equals(chunk.metadataText("doc_id"))) {
                continue;
            }
            if (!textItems.isEmpty() && itemCodes(chunk).stream().noneMatch(textItems::contains)) {
                continue;
            }
            sections.computeIfAbsent(GuidelineCorpus.unitOf(chunk), k -> new ArrayList<>()).add(chunk);
            itemCodes(chunk).stream().filter(textItems::contains).forEach(code ->
                    sectionItems.computeIfAbsent(GuidelineCorpus.unitOf(chunk), k -> EnumSet.noneOf(ItemCode.class)).add(code));
        }
        if (sections.isEmpty()) {
            return List.of();
        }
        Map<String, Double> scores = new HashMap<>();
        if (properties.vectorTiebreak()) {
            search.search(query, chunks.size()).chunks().forEach(c -> scores.put(c.id(), c.score()));
        }
        List<ContextUnit> byVector = sections.entrySet().stream()
                .map(e -> unit(e.getKey(), e.getValue(), 0.0,
                        e.getValue().stream().mapToDouble(c -> scores.getOrDefault(c.id(), 0.0)).max().orElse(0.0)))
                .sorted(Comparator.comparingDouble(ContextUnit::vectorScore).reversed())
                .toList();
        List<ContextUnit> candidates = byVector;
        if (ranking == Ranking.BM25) {
            double[] bm25 = CharBigramBm25.scores(query, byVector.stream().map(ContextUnit::text).toList());
            List<ContextUnit> scored = new ArrayList<>();
            for (int i = 0; i < byVector.size(); i++) {
                ContextUnit u = byVector.get(i);
                scored.add(new ContextUnit(u.id(), u.docId(), u.title(), u.section(), bm25[i], u.vectorScore(),
                        u.chunkIds(), u.text()));
            }
            // 안정 정렬이라 BM25 점수가 같으면 벡터 순서가 남는다 (질문과 글자가 하나도 겹치지 않을 때)
            scored.sort(Comparator.comparingDouble(ContextUnit::score).reversed());
            candidates = scored;
        }
        if (balance) {
            candidates = roundRobin(candidates, textItems, sectionItems);
        }
        List<ContextUnit> packed = new ArrayList<>();
        int used = 0;
        for (ContextUnit candidate : candidates) {
            if (used + candidate.text().length() <= budgetChars) {
                packed.add(candidate);
                used += candidate.text().length();
            }
        }
        return packed;
    }

    /** 항목마다 돌아가며 그 항목이 붙은 다음 순위 절을 고른다. 순위는 원래 순서를 따른다 */
    static List<ContextUnit> roundRobin(List<ContextUnit> ranked, Set<ItemCode> items, Map<String, Set<ItemCode>> sectionItems) {
        Set<String> chosen = new LinkedHashSet<>();
        List<ContextUnit> out = new ArrayList<>();
        Map<ItemCode, Integer> next = new EnumMap<>(ItemCode.class);
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (ItemCode item : items) {
                for (int i = next.getOrDefault(item, 0); i < ranked.size(); i++) {
                    ContextUnit unit = ranked.get(i);
                    if (sectionItems.getOrDefault(unit.id(), Set.of()).contains(item) && chosen.add(unit.id())) {
                        out.add(unit);
                        next.put(item, i + 1);
                        progressed = true;
                        break;
                    }
                    next.put(item, i + 1);
                }
            }
        }
        ranked.stream().filter(u -> !chosen.contains(u.id())).forEach(out::add);  // 어느 항목에도 안 붙은 절 (없어야 정상)
        return out;
    }

    private static ContextUnit unit(String id, List<RetrievedChunk> chunks, double score, double vectorScore) {
        RetrievedChunk first = chunks.getFirst();
        // 청크마다 붙은 "[문서 | 절] " 머리말은 첫 청크에만 남긴다 (같은 절이므로 반복할 필요가 없다)
        String text = first.text() + chunks.stream().skip(1)
                .map(c -> " " + c.text().replaceFirst("^\\[[^\\]]*\\]\\s*", ""))
                .collect(Collectors.joining());
        return new ContextUnit(id, first.metadataText("doc_id"), first.metadataText("title"),
                first.metadataText("section"), score, vectorScore, chunks.stream().map(RetrievedChunk::id).toList(), text);
    }

    private static List<ItemCode> itemCodes(RetrievedChunk chunk) {
        if (!(chunk.metadata().get("item_codes") instanceof Collection<?> codes)) {
            return List.of();
        }
        List<ItemCode> out = new ArrayList<>();
        for (Object code : codes) {
            try {
                out.add(ItemCode.valueOf(String.valueOf(code)));
            } catch (IllegalArgumentException ignored) {
                // 모르는 코드는 범위에 쓰지 않는다
            }
        }
        return out;
    }
}
