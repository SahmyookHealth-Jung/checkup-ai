package com.checkupai.report.followup;

import com.checkupai.core.llm.LlmCallContext;
import com.checkupai.report.explanation.CriteriaJudgment;
import com.checkupai.report.explanation.ExplanationDraft;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>코드 강제: 거절·악성은 LLM 문장을 버리고 고정 문구. 답이어도 검증을 통과한 문장이 0개면 고정 "근거 없음".
 *       검증은 세 가지다. 근거 구절이 인용한 근거 문단에 글자 그대로 있는가(followup-v2), 인용 번호가 허락된 것인가,
 *       숫자가 결과지나 인용한 근거에 있는가(ExplanationValidator).
 *       금지 표현은 세어서 기록한다. 고지문은 늘 붙인다</li>
 *   <li>진단 거절은 "규칙 또는 질문 분류 또는 LLM"이고, 답이어도 진단을 확인해 주는 답이면 막는다 (ADR 0014).
 *       약 패턴 규칙은 기록만 한다 ({@link FollowupRules})</li>
 * </ol>
 */
@Service
public class FollowupService {

    private static final Logger log = LoggerFactory.getLogger(FollowupService.class);

    static final String FEATURE = "report.followup";
    /**
     * v2 (2026-11-12): 문장마다 근거 구절(quote)을 받아 코드가 대조한다. v1은 인용 번호와 숫자만 확인해, 근거에 없는
     * 비수치 주장도 그럴듯한 번호를 붙이면 통과했다 (Codex 적대적 리뷰, 사용자 결정 A안)
     */
    static final String PROMPT_VERSION = "followup-v3";
    /** 질문 분류 프롬프트 (ADR 0014). 답 프롬프트와 따로 둬서, 답을 쓰는 모델이 설명 쪽으로 기우는 것을 피한다 */
    static final String INTENT_VERSION = "followup-intent-v1";
    static final String FEATURE_INTENT = "report.followup.intent";
    /**
     * "근거 없음" 재확인 (2026-11-13, 사용자 결정): 첫 답이 근거 없음이면, 문맥 중 질문과 가장 많이 겹치는 절 몇 개만 남겨
     * "그중 직접 답하는 문장이 있는가"만 다시 묻는다. 시험에서 정답 절이 문맥에 있는데도 근거 없음으로 답한 경우가 가장 많았다.
     * 답이 나와도 같은 구절·인용·숫자 검증을 통과해야 남는다.
     */
    static final String RECHECK_VERSION = "followup-recheck-v1";
    static final String FEATURE_RECHECK = "report.followup.recheck";
    static final int RECHECK_UNITS = 3;
    private static final int INTENT_MAX_TOKENS = 100;
    /** 근거 구절의 최소 길이 (공백을 뺀 글자 수). 너무 짧은 구절("입니다")로 대조를 피하지 못하게 한다 */
    static final int MIN_QUOTE_CHARS = 10;
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

    /** 질문 분류 출력: 진단 요청인가 */
    record IntentDraft(boolean diagnosis) {
    }

    public record Citation(int number, String unitId, String title, String section) {
    }

    /**
     * @param llmDecision LLM이 낸 처리 구분 (코드가 바꿨으면 decision과 다르다)
     * @param ruleTreatment 약·치료 패턴 규칙이 걸렸는가 (기록만)
     * @param ruleDiagnosis 진단 요청 패턴 규칙이 걸렸는가
     * @param intentDiagnosis 질문 분류가 진단 요청이라고 했는가 (분류 호출이 실패했으면 null)
     * @param answerBlocked 답 검사가 진단을 확인해 주는 답이라 막았는가
     * @param recheck 근거 없음 재확인 결과 (ANSWER, NO_EVIDENCE, FAILED). 하지 않았으면 null
     */
    /**
     * @param unsupportedQuotes 근거 구절이 없거나 짧거나 인용한 근거 문단에 없어 지운 문장 수
     */
    public record Metrics(String promptVersion, String itemSource, int contextChars, int contextUnits,
                          int sentencesGenerated, int sentencesKept, int unsupportedQuotes, int invalidCitations,
                          int uncitedSentences, int ungroundedNumberSentences, int forbiddenHits, long llmMs,
                          long totalMs, String llmError) {
    }

    /**
     * @param reportLines LLM에 보낸 결과지 맥락 (항목별 결과값·판정·기준). 충실도 채점이 판정 구간을 비교할 때 쓴다
     */
    public record FollowupResult(String question, Decision decision, String llmDecision, boolean ruleTreatment,
                                 boolean ruleDiagnosis, Boolean intentDiagnosis, boolean answerBlocked, String recheck, List<Sentence> sentences, String notice, List<Citation> citations,
                                 String disclaimer, String reportLines, Metrics metrics) {
    }

    private final ChatClient chatClient;
    private final FollowupContextBuilder contextBuilder;
    private final ScreeningCriteria criteria;
    private final FollowupProperties properties;
    private final ExplanationValidator validator = new ExplanationValidator();
    private final String systemPrompt;
    private final String intentPrompt;
    private final String recheckPrompt;

    public FollowupService(ChatClient.Builder chatClientBuilder, FollowupContextBuilder contextBuilder,
                           ScreeningCriteria criteria, FollowupProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.contextBuilder = contextBuilder;
        this.criteria = criteria;
        this.properties = properties;
        try {
            this.systemPrompt = new ClassPathResource("prompts/report-" + PROMPT_VERSION + ".st")
                    .getContentAsString(StandardCharsets.UTF_8);
            this.intentPrompt = new ClassPathResource("prompts/report-" + INTENT_VERSION + ".st")
                    .getContentAsString(StandardCharsets.UTF_8);
            this.recheckPrompt = new ClassPathResource("prompts/report-" + RECHECK_VERSION + ".st")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public FollowupResult answer(CheckupReport report, String question, Collection<ItemCode> selected) {
        long started = System.nanoTime();
        FollowupContext context = contextBuilder.build(report, question, selected, properties.budgetChars());
        FollowupRules.Flags flags = FollowupRules.check(question);
        Boolean intentDiagnosis = null;
        try {
            intentDiagnosis = classify(question);
        } catch (RuntimeException e) {
            // 분류를 못 하면 진단 질문으로 본다(보수적 실패). 실패한 층을 조용히 건너뛰지 않는다 (Codex 리뷰 P1)
            log.warn("질문 분류 실패, 진단 질문으로 처리: {}", e.getClass().getSimpleName());
        }
        boolean diagnosisAsked = flags.diagnosis() || !Boolean.FALSE.equals(intentDiagnosis);

        Map<String, ContextUnit> byLabel = new LinkedHashMap<>();
        for (ContextUnit unit : context.units()) {
            byLabel.put("S" + (byLabel.size() + 1), unit);
        }
        String reportLines = reportLines(report, context.rowItems());

        FollowupDraft draft = null;
        String llmError = null;
        long llmStarted = System.nanoTime();
        try {
            draft = generate(systemPrompt, FEATURE, byLabel, reportLines, question);
        } catch (RuntimeException e) {
            llmError = e.getClass().getSimpleName();  // 실패하면 "근거 없음"으로 답한다 (답을 지어내지 않는다)
        }
        Decision llmDecision = parse(draft == null ? null : draft.decision());
        Checked checked = check(draft, byLabel, reportLines);

        String recheck = null;
        boolean recheckAnswered = false;
        if (llmDecision == Decision.NO_EVIDENCE && !diagnosisAsked && !flags.treatment() && !context.rankedUnits().isEmpty()) {
            Map<String, ContextUnit> narrow = narrow(byLabel, context);
            try {
                FollowupDraft second = generate(recheckPrompt, FEATURE_RECHECK, narrow, reportLines, question);
                Checked again = check(second, narrow, reportLines);
                recheckAnswered = parse(second.decision()) == Decision.ANSWER && !again.outcome().kept().isEmpty();
                recheck = recheckAnswered ? Decision.ANSWER.name() : Decision.NO_EVIDENCE.name();
                if (recheckAnswered) {
                    draft = second;
                    checked = again;
                }
            } catch (RuntimeException e) {
                recheck = "FAILED";  // 재확인이 실패하면 첫 판단(근거 없음)을 그대로 둔다
            }
        }
        long llmMs = (System.nanoTime() - llmStarted) / 1_000_000;
        List<FollowupDraft.SentenceDraft> drafts = checked.drafts();
        int unsupportedQuotes = checked.unsupportedQuotes();
        ExplanationValidator.Outcome outcome = checked.outcome();

        Decision decision;
        List<Sentence> sentences = new ArrayList<>();
        Map<String, Integer> numbers = new LinkedHashMap<>();
        String notice;
        if (llmDecision == null) {
            decision = Decision.NO_EVIDENCE;
        } else if (recheckAnswered) {
            decision = Decision.ANSWER;  // 재확인에서 근거로 검증된 문장이 나왔다
        } else if (llmDecision == Decision.ANSWER && outcome.kept().isEmpty()) {
            decision = Decision.NO_EVIDENCE;  // 답이라고 했지만 근거로 검증된 문장이 없다 (가드레일 원칙 2)
        } else {
            decision = llmDecision;
        }
        if (diagnosisAsked && (decision == Decision.ANSWER || decision == Decision.NO_EVIDENCE)) {
            decision = Decision.REFUSE_DIAGNOSIS;  // 진단 거절은 규칙 또는 질문 분류 또는 LLM (ADR 0011 결과, ADR 0014)
        }
        boolean answerBlocked = decision == Decision.ANSWER && FollowupRules.answerConfirmsDiagnosis(question,
                outcome.kept().stream().map(ExplanationValidator.CheckedSentence::text).toList());
        if (answerBlocked) {
            decision = Decision.REFUSE_DIAGNOSIS;  // 답 검사: 앞 단계가 모두 놓친 진단 답을 내보내지 않는다 (ADR 0014)
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
        var metrics = new Metrics(PROMPT_VERSION + "+" + INTENT_VERSION + "+" + RECHECK_VERSION, context.itemSource().name(), context.totalChars(), byLabel.size(),
                drafts.size(), decision == Decision.ANSWER ? outcome.kept().size() : 0, unsupportedQuotes,
                outcome.invalidCitations(), outcome.uncited(), outcome.ungroundedNumbers(), outcome.forbiddenHits(),
                llmMs, (System.nanoTime() - started) / 1_000_000, llmError);
        return new FollowupResult(question, decision, llmDecision == null ? null : llmDecision.name(),
                flags.treatment(), flags.diagnosis(), intentDiagnosis, answerBlocked, recheck, sentences, notice, citations, ExplanationGuardrails.DISCLAIMER,
                reportLines, metrics);
    }

    /** 질문 분류 (ADR 0014): 질문만 보낸다. 결과지와 근거는 보내지 않는다 */
    private boolean classify(String question) {
        IntentDraft intent = chatClient.prompt()
                .system(intentPrompt)
                .user(user -> user.text("<질문>\n{question}\n</질문>").param("question", question))
                .options(ChatOptions.builder().maxTokens(INTENT_MAX_TOKENS))
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .advisors(advisor -> advisor.param(LlmCallContext.FEATURE, FEATURE_INTENT))
                .call()
                .entity(new BeanOutputConverter<>(IntentDraft.class));
        return intent != null && intent.diagnosis();
    }

    /** 검증을 거친 답 초안: 구절 대조로 남은 문장의 인용·숫자 검증 결과 */
    private record Checked(List<FollowupDraft.SentenceDraft> drafts, int unsupportedQuotes, ExplanationValidator.Outcome outcome) {
    }

    /** 1) 근거 구절 대조: 인용한 근거 문단에 구절이 글자 그대로 있는 문장만 남긴다 2) 인용 번호·숫자 검증 */
    private Checked check(FollowupDraft draft, Map<String, ContextUnit> byLabel, String reportLines) {
        Map<String, String> allowed = new LinkedHashMap<>();
        byLabel.forEach((label, unit) -> allowed.put(label, unit.text()));
        List<FollowupDraft.SentenceDraft> drafts = draft == null || draft.sentences() == null ? List.of() : draft.sentences();
        List<ExplanationDraft.SentenceDraft> quoted = new ArrayList<>();
        int unsupportedQuotes = 0;
        for (FollowupDraft.SentenceDraft sentence : drafts) {
            if (sentence == null || sentence.text() == null || sentence.text().isBlank()) {
                continue;
            }
            if (quoteSupported(sentence, allowed)) {
                quoted.add(new ExplanationDraft.SentenceDraft(sentence.text(), sentence.sources()));
            } else {
                unsupportedQuotes++;
                // 근거 구절은 공개 문서에서 베낀 것이어야 하지만 결과지 값이 섞일 수 있어 DEBUG로만 남긴다
                log.debug("근거 구절 불일치 sources={} quote={}", sentence.sources(), sentence.quote());
            }
        }
        return new Checked(drafts, unsupportedQuotes, validator.check(quoted, allowed, reportLines));
    }

    /** 재확인 문맥: 고정 근거(판정기준표 행·판정 정의)와 순위 상위 절 몇 개. 번호는 첫 답과 같게 둔다 */
    private static Map<String, ContextUnit> narrow(Map<String, ContextUnit> byLabel, FollowupContext context) {
        Set<String> keep = new java.util.HashSet<>();
        context.fixedUnits().forEach(u -> keep.add(u.id()));
        context.rankedUnits().stream().limit(RECHECK_UNITS).forEach(u -> keep.add(u.id()));
        Map<String, ContextUnit> out = new LinkedHashMap<>();
        byLabel.forEach((label, unit) -> {
            if (keep.contains(unit.id())) {
                out.put(label, unit);
            }
        });
        return out;
    }

    private FollowupDraft generate(String prompt, String feature, Map<String, ContextUnit> byLabel, String reportLines,
                                   String question) {
        String sources = byLabel.entrySet().stream()
                .map(e -> "[" + e.getKey() + "] " + e.getValue().text())
                .collect(Collectors.joining("\n"));
        // 근거 문단은 시스템 메시지로 보낸다 (식별정보 제거 Advisor가 근거 원문을 바꾸지 않게, 해설과 같음)
        return chatClient.prompt()
                .system(system -> system.text(prompt).param("sources", sources))
                .user(user -> user.text(USER_TEMPLATE).param("report", reportLines).param("question", question))
                .options(ChatOptions.builder().maxTokens(MAX_TOKENS))
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .advisors(advisor -> advisor.param(LlmCallContext.FEATURE, feature))
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

    /**
     * 근거 구절이 인용한 근거 문단 중 하나에 글자 그대로 있는가. 공백과 앞뒤 따옴표·문장부호는 무시한다.
     * 구절이 있다고 문장이 구절과 같은 말이라는 보장은 없다. 그래도 답이 근거의 특정 문장에 묶인다 (충실도는 채점기로 잰다).
     */
    static boolean quoteSupported(FollowupDraft.SentenceDraft sentence, Map<String, String> allowed) {
        String quote = normalize(sentence.quote());
        if (quote.length() < MIN_QUOTE_CHARS || sentence.sources() == null) {
            return false;
        }
        return sentence.sources().stream()
                .map(source -> source == null ? "" : source.strip().replaceAll("[\\[\\]]", ""))
                .map(allowed::get)
                .anyMatch(text -> text != null && normalize(text).contains(quote));
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", "").replaceAll("^[\"'“”‘’.,…]+|[\"'“”‘’.,…]+$", "");
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
