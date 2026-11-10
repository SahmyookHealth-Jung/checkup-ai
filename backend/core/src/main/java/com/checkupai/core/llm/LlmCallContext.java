package com.checkupai.core.llm;

/**
 * ChatClient 요청 문맥(advisor param)에 쓰는 키. 기능 코드는 호출마다 기능 이름을 붙인다:
 * {@code .advisors(a -> a.param(LlmCallContext.FEATURE, "report.extraction.first-pass"))}
 */
public final class LlmCallContext {

    /** 호출한 기능 이름 (예: report.extraction.first-pass). 비용·지연을 기능별로 나눠 보는 데 쓴다 */
    public static final String FEATURE = "checkupai.llm.feature";

    /** 식별정보 제거 Advisor가 남기는 종류별 제거 개수 (Map&lt;PersonalInfoType, Integer&gt;) */
    public static final String REDACTIONS = "checkupai.llm.redactions";

    static final String UNKNOWN_FEATURE = "unknown";

    private LlmCallContext() {
    }
}
