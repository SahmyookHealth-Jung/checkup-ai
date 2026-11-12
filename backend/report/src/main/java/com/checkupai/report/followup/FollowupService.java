package com.checkupai.report.followup;

import com.checkupai.core.llm.LlmCallContext;
import com.checkupai.report.explanation.CriteriaJudgment;
import com.checkupai.report.explanation.ExplanationGuardrails;
import com.checkupai.report.explanation.ExplanationValidator;
import com.checkupai.report.explanation.ScreeningCriteria;
import com.checkupai.report.extraction.CheckupReport;
import com.checkupai.report.extraction.ItemCode;
import com.checkupai.report.extraction.ReportItem;
import com.checkupai.report.followup.FollowupContextBuilder.ContextUnit;
import com.checkupai.report.followup.FollowupContextBuilder.FollowupContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 후속 질문 하나에 답한다 (ADR 0011 3절). 추출·해설과 같은 원칙이다: LLM은 후보를 내고 코드가 받아들일지 정한다.
 * <ol>
 *   <li>문맥: 코드가 근거를 고른다 ({@link FollowupContextBuilder})</li>
 *   <li>LLM 1회: 처리 구분과 문장별 근거 번호 (구조화 출력)</li>
 *   <li>코드 강제: 거절·악성은 LLM 문장을 버리고 고정 문구. 답이어도 인용·숫자 검증을 통과한 문장이 0개면 고정 "근거 없음".
 *       금지 표현은 세어서 기록한다. 고지문은 늘 붙인다</li>
 *   <li>약·진단 패턴 규칙은 기록만 한다 ({@link FollowupRules})</li>
 * </ol>
 */
@Service
public class FollowupService {

    static final String FEATURE = "report.followup";
    static final String PROMPT_VERSION = "followup-v1";
    private static final int MAX_TOKENS = 1500;
    private static final String USER_TEMPLATE = """
            <결과지>
            {report}
            </결과지>
            <질문>
            {question}
            </질문>
            """;

    /** 최종 처리 */
    public enum Decision { ANSWER, NO_EVIDENCE, REFUSE_DIAGNOSIS, REFUSE_TREATMENT, MALICIOUS }

    /** 고정 문구 (코드가 붙인다, CLAUDE.md 의료 가드레일 원칙 1·2·5) */
    static final Map<Decision, String> FIXED = Map.of(
            Decision.NO_EVIDENCE, "이 질문에 답할 근거를 공개 문서에서 찾지 못했습니다. 의료진이나 검진기관에 문의해 주세요.",
            Decision.REFUSE_DIAGNOSIS, "어떤 병이 있는지는 이 서비스가 판단할 수 없습니다. 정확한 진단은 의료진과 상담하세요.",
            Decision.REFUSE_TREATMENT, "약이나 치료 방법은 이 서비스가 안내할 수 없습니다. 복용 중인 약이나 치료는 담당 의료진과 상담하세요.",
            Decision.MALICIOUS, "이 요청에는 답할 수 없습니다. 본인의 검진 결과에 대해 궁금한 점을 물어봐 주세요.");
    static final String UNCOVERED_NOTICE = "질문의 일부는 근거 문서에서 찾지 못해 답하지 않았습니다.";

    public record Sentence(String text, List<Integer> citations) {
    }

    public record Citation(int number, String unitId, String title, String section) {
    }

    /**
     * @param llmDecision LLM이 낸 처리 구분 (코드가 바꿨으면 decision과 다르다)
     * @param ruleTreatment 약·치료 패턴 규칙이 걸렸는가 (기록만)
     * @param ruleDiagnosis 진단 요청 패턴 규칙이 걸렸는가 (기록만)
     */
    public record Metrics(String itemSource, int contextChars, int contextUnits, int sentencesGenerated,
                          int sentencesKept, int invalidCitations, int uncitedSentences, int ungroundedNumberSentences,
                          int forbiddenHits, long llmMs, long totalMs, String llmError) {
    }

    public record FollowupResult(String question, Decision decision, String llmDecision, boolean ruleTreatment,
                                 boolean ruleDiagnosis, List<Sentence> sentences, String notice, List<Citation> citations,
                                 String disclaimer, Metrics metrics) {
    }

    private final ChatClient chatClient;
    private final FollowupContextBuilder contextBuilder;
    private final ScreeningCriteria criteria;
    private final FollowupProperties properties;
    private final ExplanationValidator validator = new ExplanationValidator();
    private final String systemPrompt;

    public FollowupService(ChatClient.Builder chatClientBuilder, FollowupContextBuilder contextBuilder,
                           ScreeningCriteria criteria, FollowupProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.contextBuilder = contextBuilder;
        this.criteria = criteria;
        this.properties = properties;
        try {
            this.systemPrompt = new ClassPathResource("prompts/report-followup-v1.st").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public FollowupResult answer(CheckupReport report, String question, Collection<ItemCode> selected) {
        long started = System.nanoTime();
        FollowupContext context = contextBuilder.build(report, question, selected, properties.budgetChars());
        FollowupRules.Flags flags = FollowupRules.check(question);

        Map<String, ContextUnit> byLabel = new LinkedHashMap<>();
        for (ContextUnit unit : context.units()) {
            byLabel.put("S" + (byLabel.size() + 1), unit);
        }
        String reportLines = reportLines(report, context.rowItems());

        FollowupDraft draft = null;
        String llmError = null;
        long llmStarted = System.nanoTime();
        try {
            draft = generate(byLabel, reportLines, question);
        } catch (RuntimeException e) {
            llmError = e.getClass().getSimpleName();  // 실패하면 "근거 없음"으로 답한다 (답을 지어내지 않는다)
        }
        long llmMs = (System.nanoTime() - llmStarted) / 1_000_000;

        Decision llmDecision = parse(draft == null ? null : draft.decision());
        Map<String, String> allowed = new LinkedHashMap<>();
        byLabel.forEach((label, unit) -> allowed.put(label, unit.text()));
        ExplanationValidator.Outcome outcome = validator.check(
                draft == null || draft.sentences() == null ? List.of() : draft.sentences(), allowed, reportLines);

        Decision decision;
        List<Sentence> sentences = new ArrayList<>();
        Map<String, Integer> numbers = new LinkedHashMap<>();
        String notice;
        if (llmDecision == null) {
            decision = Decision.NO_EVIDENCE;
        } else if (llmDecision == Decision.ANSWER && outcome.kept().isEmpty()) {
            decision = Decision.NO_EVIDENCE;  // 답이라고 했지만 근거로 검증된 문장이 없다 (가드레일 원칙 2)
        } else {
            decision = llmDecision;
        }
        if (decision == Decision.ANSWER) {
            for (ExplanationValidator.CheckedSentence kept : outcome.kept()) {
                sentences.add(new Sentence(kept.text(), kept.sources().stream()
                        .map(label -> numbers.computeIfAbsent(label, l -> numbers.size() + 1)).toList()));
            }
            notice = draft.uncovered() == null || draft.uncovered().isBlank() ? null : UNCOVERED_NOTICE;
        } else {
            notice = FIXED.get(decision);  // 거절·악성·근거 없음은 LLM 문장을 쓰지 않는다
        }
        List<Citation> citations = numbers.entrySet().stream()
                .map(e -> {
                    ContextUnit unit = byLabel.get(e.getKey());
                    return new Citation(e.getValue(), unit.id(), unit.title(), unit.section());
                })
                .toList();
        var metrics = new Metrics(context.itemSource().name(), context.totalChars(), byLabel.size(),
                outcome.generated(), decision == Decision.ANSWER ? outcome.kept().size() : 0,
                outcome.invalidCitations(), outcome.uncited(), outcome.ungroundedNumbers(), outcome.forbiddenHits(),
                llmMs, (System.nanoTime() - started) / 1_000_000, llmError);
        return new FollowupResult(question, decision, llmDecision == null ? null : llmDecision.name(),
                flags.treatment(), flags.diagnosis(), sentences, notice, citations, ExplanationGuardrails.DISCLAIMER,
                metrics);
    }

    private FollowupDraft generate(Map<String, ContextUnit> byLabel, String reportLines, String question) {
        String sources = byLabel.entrySet().stream()
                .map(e -> "[" + e.getKey() + "] " + e.getValue().text())
                .collect(Collectors.joining("\n"));
        // 근거 문단은 시스템 메시지로 보낸다 (식별정보 제거 Advisor가 근거 원문을 바꾸지 않게, 해설과 같음)
        return chatClient.prompt()
                .system(system -> system.text(systemPrompt).param("sources", sources))
                .user(user -> user.text(USER_TEMPLATE).param("report", reportLines).param("question", question))
                .options(ChatOptions.builder().maxTokens(MAX_TOKENS))
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .advisors(advisor -> advisor.param(LlmCallContext.FEATURE, FEATURE))
                .call()
                .entity(new BeanOutputConverter<>(FollowupDraft.class));
    }

    /** 결과지 맥락: 질문 항목과 주의 항목의 결과값·판정·기준 (여기 있는 숫자는 답에 쓸 수 있다) */
    private String reportLines(CheckupReport report, List<ItemCode> rowItems) {
        if (report == null || report.items() == null) {
            return "(결과지 없음)";
        }
        Set<ItemCode> wanted = Set.copyOf(rowItems);
        List<String> lines = new ArrayList<>();
        for (ReportItem item : report.items()) {
            if (item.code() == null || !wanted.contains(item.code())) {
                continue;
            }
            CriteriaJudgment judgment = criteria.judge(item.code(), item.value(), item.unit(), report.sex());
            lines.add("- " + item.code().koreanName() + ": " + item.value() + (item.unit() == null ? "" : " " + item.unit())
                    + " | 판정: " + judgment.judgment().label()
                    + (judgment.criterion() == null ? "" : " | 기준: " + judgment.criterion()));
        }
        return lines.isEmpty() ? "(질문과 관련된 항목 없음)" : String.join("\n", lines);
    }

    private static Decision parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Decision.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
