package com.checkupai.report.explanation;

import com.checkupai.core.llm.LlmCallContext;
import com.checkupai.report.explanation.ExplanationResult.Citation;
import com.checkupai.report.explanation.ExplanationResult.ItemExplanation;
import com.checkupai.report.explanation.ExplanationResult.ItemJudgment;
import com.checkupai.report.explanation.ExplanationResult.Origin;
import com.checkupai.report.explanation.ExplanationResult.ReviewNotice;
import com.checkupai.report.explanation.ExplanationResult.Sentence;
import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ExtractionResult;
import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.extraction.ReportItem;
import com.checkupai.report.extraction.SalvagingOutputConverter;
import com.checkupai.report.retrieval.GuidelineSearch;
import com.checkupai.report.retrieval.RetrievedChunk;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 출처가 붙은 결과 해설 (ADR 0007). 추출과 같은 원칙이다: LLM은 후보 문장을 내고, 받아들일지는 코드가 정한다.
 * <ol>
 *   <li>판정: 코드가 국가건강검진 판정기준으로 항목마다 판정한다 ({@link ScreeningCriteria})</li>
 *   <li>대상: 정상B(경계)·질환의심 항목만 해설한다 (질환의심 먼저, 상한 {@code max-items})</li>
 *   <li>검색: 항목마다 "항목명 + 판정 구분"으로 근거를 찾고, 유사도 임계 미만은 버린다</li>
 *   <li>생성: LLM은 항목별 문장과 근거 번호만 낸다. 근거 문단은 시스템 메시지로 보낸다
 *       (식별정보 제거 Advisor가 근거 원문을 바꾸지 않게. 2026-11-10 적재 때 근거 1건이 제거 규칙에 걸렸다)</li>
 *   <li>검증: 인용·숫자 근거를 코드가 확인하고, 고지문과 진료 권고는 코드가 붙인다 ({@link ExplanationGuardrails})</li>
 * </ol>
 * 5단계 개선 두 가지는 켜고 끌 수 있다 ({@link Options}, ADR 0008 4절 4번). 하나씩 짝비교하려고 기본값은 끔이다.
 */
@Service
public class ExplanationService {

    static final String FEATURE = "report.explanation";
    static final String PROMPT_VERSION = "explanation-v1";
    /**
     * 근거가 판정기준표 1행뿐인 항목 (ADR 0008 1절, 2026-11-10 기준선 분석). 코드 문구를 켜면 LLM 대신 코드가 답한다.
     * 포털 글을 더하면 그 항목을 여기서 빼고 "코드 문구 → LLM 문장" 전후를 충실도로 잰다.
     */
    static final Set<ItemCode> CODE_TEMPLATE_ITEMS = EnumSet.of(ItemCode.BMI, ItemCode.WAIST_CIRCUMFERENCE,
            ItemCode.HEMOGLOBIN, ItemCode.AST, ItemCode.ALT, ItemCode.GAMMA_GTP, ItemCode.URINE_PROTEIN,
            ItemCode.SERUM_CREATININE, ItemCode.EGFR, ItemCode.CHEST_XRAY);
    private static final String USER_TEMPLATE = """
            아래 항목을 각각 설명해 주세요.
            {items}
            """;

    private final ChatClient chatClient;
    private final ScreeningCriteria criteria;
    private final GuidelineSearch search;
    private final ExplanationProperties properties;
    private final ExplanationValidator validator = new ExplanationValidator();
    private final String systemPrompt;

    public ExplanationService(ChatClient.Builder chatClientBuilder, ScreeningCriteria criteria, GuidelineSearch search,
                              ExplanationProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.criteria = criteria;
        this.search = search;
        this.properties = properties;
        this.systemPrompt = loadPrompt("prompts/report-explanation-v1.st");
    }

    /**
     * 해설 조건 (ADR 0008 4절 4번). 평가 API는 요청마다 바꿔 짝비교한다.
     *
     * @param itemFilter 그 항목의 근거와 항목 코드가 없는 근거만 쓴다
     * @param codeTemplates {@link #CODE_TEMPLATE_ITEMS}는 LLM 대신 코드 문구로 답한다
     */
    public record Options(boolean itemFilter, boolean codeTemplates) {
    }

    /** 해설 대상 항목과 그 항목에 쓸 수 있는 근거 (근거 번호 → 근거 문단). templated면 코드 문구로 답한다 */
    private record Target(ReportItem item, CriteriaJudgment judgment, Map<String, RetrievedChunk> sources,
                          boolean templated) {
    }

    public Options defaultOptions() {
        return new Options(properties.itemFilter(), properties.codeTemplates());
    }

    public ExplanationResult explain(ExtractionResult extraction) {
        return explain(extraction, defaultOptions());
    }

    public ExplanationResult explain(ExtractionResult extraction, Options options) {
        long started = System.nanoTime();
        CheckupReport report = extraction.report();
        List<ReportItem> reportItems = report == null || report.items() == null ? List.of() : report.items();
        CheckupReport.Sex sex = report == null ? null : report.sex();

        // 1~2. 코드가 판정하고 해설 대상을 고른다
        List<ItemJudgment> judgments = new ArrayList<>();
        List<Target> targets = new ArrayList<>();
        for (ReportItem item : reportItems) {
            if (item.code() == null) {
                continue;
            }
            CriteriaJudgment judgment = criteria.judge(item.code(), item.value(), sex);
            judgments.add(new ItemJudgment(item.code(), item.code().koreanName(), item.rawName(), item.value(),
                    item.unit(), judgment.judgment(), judgment.judgment().label(), judgment.criterion(),
                    item.centerJudgment()));
            if (judgment.judgment() == Judgment.SUSPECT || judgment.judgment() == Judgment.NORMAL_B) {
                boolean templated = options.codeTemplates() && CODE_TEMPLATE_ITEMS.contains(item.code());
                targets.add(new Target(item, judgment, new LinkedHashMap<>(), templated));
            }
        }
        targets.sort(Comparator.comparing(target -> target.judgment().judgment() == Judgment.SUSPECT ? 0 : 1));
        if (targets.size() > properties.maxItems()) {
            targets = new ArrayList<>(targets.subList(0, properties.maxItems()));
        }

        // 3. 항목마다 근거를 검색한다. 같은 청크는 같은 번호(S1 등)를 쓴다.
        //    코드 문구 항목은 검색하지 않고 그 항목의 판정기준표 행만 근거로 둔다
        long retrievalStarted = System.nanoTime();
        Map<String, String> labelByChunkId = new LinkedHashMap<>();
        Map<String, RetrievedChunk> chunkByLabel = new LinkedHashMap<>();
        for (Target target : targets) {
            String code = target.item().code().name();
            String query = target.item().code().koreanName() + " " + target.judgment().judgment().label();
            List<RetrievedChunk> found = target.templated()
                    ? search.criteriaRow(code, query).stream().toList()
                    : search.relevant(options.itemFilter() ? search.searchForItem(query, code) : search.search(query));
            for (RetrievedChunk chunk : found) {
                String label = labelByChunkId.computeIfAbsent(chunk.id(), id -> "S" + (labelByChunkId.size() + 1));
                chunkByLabel.put(label, chunk);
                target.sources().put(label, chunk);
            }
        }
        long retrievalMs = (System.nanoTime() - retrievalStarted) / 1_000_000;

        // 4. 근거가 있는 항목만 LLM에 보낸다 (코드 문구 항목은 보내지 않는다)
        List<Target> withEvidence = targets.stream()
                .filter(target -> !target.templated() && !target.sources().isEmpty())
                .toList();
        ExplanationDraft draft = null;
        String llmError = null;
        int salvagedItems = -1;
        long llmMs = 0;
        if (!withEvidence.isEmpty()) {
            long llmStarted = System.nanoTime();
            try {
                // 출력이 상한에서 잘리면 완성된 항목까지만 살린다 (추출과 같은 변환기). 나머지 항목은 '근거 없음'이 된다
                var converter = new SalvagingOutputConverter<>(ExplanationDraft.class);
                draft = generate(withEvidence, converter);
                salvagedItems = converter.salvagedItems();
            } catch (RuntimeException e) {
                llmError = e.getClass().getSimpleName();  // 해설이 실패해도 판정·근거 없음·고지문은 돌려준다
            }
            llmMs = (System.nanoTime() - llmStarted) / 1_000_000;
        }

        // 5. 코드가 검증하고, 인용 번호를 매기고, 고정 문구를 붙인다
        Map<String, ExplanationDraft.ItemDraft> draftByCode = draft == null || draft.items() == null ? Map.of()
                : draft.items().stream().filter(item -> item != null && item.itemCode() != null)
                        .collect(Collectors.toMap(item -> item.itemCode().strip(), item -> item, (a, b) -> a));
        Map<String, Integer> citationNumbers = new LinkedHashMap<>();
        List<ItemExplanation> items = new ArrayList<>();
        int generated = 0;
        int kept = 0;
        int invalidCitations = 0;
        int uncited = 0;
        int ungrounded = 0;
        int forbidden = 0;
        int templatedItems = 0;
        for (Target target : targets) {
            ReportItem item = target.item();
            if (target.templated()) {
                templatedItems++;
                items.add(itemExplanation(target, templateSentences(target, citationNumbers)));
                continue;
            }
            Map<String, String> allowed = new LinkedHashMap<>();
            target.sources().forEach((label, chunk) -> allowed.put(label, chunk.text()));
            ExplanationDraft.ItemDraft itemDraft = draftByCode.get(item.code().name());
            String facts = item.value() + " " + (item.unit() == null ? "" : item.unit()) + "\n"
                    + (target.judgment().criterion() == null ? "" : target.judgment().criterion());
            ExplanationValidator.Outcome outcome = validator.check(
                    itemDraft == null ? List.of() : itemDraft.sentences(), allowed, facts);
            generated += outcome.generated();
            kept += outcome.kept().size();
            invalidCitations += outcome.invalidCitations();
            uncited += outcome.uncited();
            ungrounded += outcome.ungroundedNumbers();
            forbidden += outcome.forbiddenHits();
            List<Sentence> sentences = outcome.kept().stream()
                    .map(sentence -> new Sentence(sentence.text(), sentence.sources().stream()
                            .map(label -> citationNumbers.computeIfAbsent(label, l -> citationNumbers.size() + 1))
                            .toList(), Origin.LLM))
                    .toList();
            items.add(itemExplanation(target, sentences));
        }
        List<Citation> citations = citationNumbers.entrySet().stream()
                .map(entry -> citation(entry.getValue(), chunkByLabel.get(entry.getKey())))
                .toList();
        List<ReviewNotice> needsReview = extraction.needsReview() == null ? List.of() : extraction.needsReview().stream()
                .map(review -> new ReviewNotice(review.code(), review.rawName(), review.value(),
                        ExplanationGuardrails.REVIEW_ADVICE))
                .toList();
        var metrics = new ExplanationResult.Metrics(targets.size(), withEvidence.size(), generated, kept,
                invalidCitations, uncited, ungrounded, forbidden, retrievalMs, llmMs,
                (System.nanoTime() - started) / 1_000_000, llmError, salvagedItems, templatedItems,
                options.itemFilter());
        return new ExplanationResult(judgments, items, needsReview, citations, ExplanationGuardrails.DISCLAIMER, metrics);
    }

    private static ItemExplanation itemExplanation(Target target, List<Sentence> sentences) {
        ReportItem item = target.item();
        boolean suspect = target.judgment().judgment() == Judgment.SUSPECT;
        return new ItemExplanation(item.code(), item.code().koreanName(), item.value(), item.unit(),
                target.judgment().judgment(), target.judgment().judgment().label(), target.judgment().criterion(),
                target.judgment().source(), sentences, sentences.isEmpty(),
                suspect ? ExplanationGuardrails.CARE_ADVICE : null,
                target.sources().values().stream().map(RetrievedChunk::id).toList());
    }

    /**
     * 코드 문구 (ADR 0008 1절): "{항목} 결과값({값 단위})은 국가건강검진 판정기준의 {판정} 구간({기준})에 해당합니다."
     * 출처는 그 항목의 판정기준표 행 하나다. 행을 못 찾으면 문장을 만들지 않는다 (근거 없음).
     */
    private static List<Sentence> templateSentences(Target target, Map<String, Integer> citationNumbers) {
        if (target.sources().isEmpty()) {
            return List.of();
        }
        String label = target.sources().keySet().iterator().next();
        int number = citationNumbers.computeIfAbsent(label, l -> citationNumbers.size() + 1);
        return List.of(new Sentence(templateText(target.item(), target.judgment()), List.of(number), Origin.CODE));
    }

    static String templateText(ReportItem item, CriteriaJudgment judgment) {
        String value = item.value() + (item.unit() == null || item.unit().isBlank() ? "" : " " + item.unit());
        String band = bandText(judgment.criterion(), judgment.judgment().label());
        return item.code().koreanName() + " 결과값(" + value + ")은 국가건강검진 판정기준의 "
                + judgment.judgment().label() + " 구간" + (band == null ? "" : "(" + band + ")") + "에 해당합니다.";
    }

    /**
     * 판정기준표 행에서 그 판정 구간의 기준만 잘라 낸다.
     * 예: "간장질환 · AST · U/L · 정상A 40 이하 / 정상B(경계) 41-50 / 질환의심 51 이상", "질환의심" → "51 이상"
     */
    static String bandText(String criterionRow, String judgmentLabel) {
        if (criterionRow == null) {
            return null;
        }
        String bands = criterionRow.substring(criterionRow.lastIndexOf(" · ") + 3);
        for (String segment : bands.split(" / ")) {
            if (segment.startsWith(judgmentLabel + " ")) {
                return segment.substring(judgmentLabel.length() + 1).strip();
            }
        }
        return null;
    }

    private ExplanationDraft generate(List<Target> targets, SalvagingOutputConverter<ExplanationDraft> converter) {
        // LLM에 보낼 근거는 LLM이 해설할 항목의 근거뿐이다 (코드 문구 항목의 기준표 행은 보내지 않는다)
        Map<String, RetrievedChunk> used = new LinkedHashMap<>();
        targets.forEach(target -> used.putAll(target.sources()));
        String sources = used.entrySet().stream()
                .map(entry -> "[" + entry.getKey() + "] " + entry.getValue().text())
                .collect(Collectors.joining("\n"));
        String items = targets.stream()
                .map(target -> "- itemCode=" + target.item().code().name()
                        + " | 항목: " + target.item().code().koreanName()
                        + " | 결과: " + target.item().value() + (target.item().unit() == null ? "" : " " + target.item().unit())
                        + " | 판정(국가건강검진 기준): " + target.judgment().judgment().label()
                        + " | 판정 기준: " + target.judgment().criterion()
                        + " | 쓸 수 있는 근거: " + String.join(", ", target.sources().keySet()))
                .collect(Collectors.joining("\n"));
        return chatClient.prompt()
                .system(system -> system.text(systemPrompt).param("sources", sources))
                .user(user -> user.text(USER_TEMPLATE).param("items", items))
                .options(ChatOptions.builder().maxTokens(properties.maxTokens()))
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .advisors(advisor -> advisor.param(LlmCallContext.FEATURE, FEATURE))  // 호출 기록의 기능 이름
                .call()
                .entity(converter);
    }

    private static Citation citation(int number, RetrievedChunk chunk) {
        return new Citation(number, chunk.id(), chunk.metadataText("title"), chunk.metadataText("publisher"),
                chunk.metadataText("edition"), chunk.metadataText("section"), chunk.metadataText("url"),
                chunk.metadataText("license"), chunk.text());
    }

    private static String loadPrompt(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
