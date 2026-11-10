package com.checkupai.core.privacy;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 규칙 기반 개인 식별정보 제거기 (ADR 0006). 외부 LLM으로 보내는 글에서 이름, 생년월일, 주민등록번호,
 * 수검번호류, 전화번호, 이메일, 주소를 표시({@code [이름]} 등)로 바꾼다.
 *
 * <p>성별, 나이, 검진일은 남긴다. 결과지 추출에 필요하고, 그것만으로는 개인을 특정하기 어렵다.
 * 검사 수치도 남는다. 그래서 이 제거는 "반출 최소화"이지 비식별 처리의 법적 요건 충족을 뜻하지 않는다.
 *
 * <p>규칙은 라벨(이름:, 수검번호 등)과 모양(가린 이름 최*준, 가린 생년월일 1965-**-**, 전화번호, 도로명 주소)으로 찾는다.
 * 한계: 라벨도 가림표도 없는 실명은 찾지 못한다. 그런 이름을 찾으려면 NER 모델이 필요하다(이 맥에서는 무거워 기각).
 */
public final class PersonalInfoRedactor {

    /** group이 0이면 일치 전체를, 아니면 그 그룹만 바꾼다 (라벨은 남긴다) */
    private record Rule(PersonalInfoType type, Pattern pattern, int group) {
    }

    private static final List<Rule> RULES = List.of(
            // 주민등록번호: 뒷자리 첫 숫자 1~4, 나머지는 숫자나 가림표
            new Rule(PersonalInfoType.RESIDENT_NUMBER,
                    Pattern.compile("(?<!\\d)\\d{6}\\s*-\\s*[1-4][\\d*]{6}(?![\\d*])"), 0),
            new Rule(PersonalInfoType.EMAIL, Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"), 0),
            // 전화번호 (02-123-4567, 010-1234-5678) 와 대표번호 (1588-0000)
            new Rule(PersonalInfoType.PHONE,
                    Pattern.compile("(?<![\\d.])(?:\\d{2,4}-\\d{3,4}-\\d{4}|1[5-9]\\d{2}-\\d{4})(?![\\d-])"), 0),
            // 라벨 뒤의 번호 (수검번호: SYN-2026-6245, 고객번호 C-123456). 숫자가 하나는 있어야 번호로 본다
            new Rule(PersonalInfoType.RECORD_NUMBER, Pattern.compile(
                    "(?:수검번호|등록번호|고객번호|환자번호|차트번호|검진번호|접수번호)\\s*[:：]?\\s*"
                            + "([A-Za-z0-9][A-Za-z0-9-]*\\d[A-Za-z0-9-]*)"), 1),
            // 라벨 뒤의 생년월일
            new Rule(PersonalInfoType.BIRTH_DATE, Pattern.compile(
                    "(?:생년월일|생일)\\s*[:：]?\\s*(\\d{4}\\s*[-./년]\\s*[\\d*]{1,2}\\s*[-./월]\\s*[\\d*]{1,2}일?)"), 1),
            // 일부를 가린 생년월일 (1965-**-**). 표에서는 라벨과 값이 다른 줄에 있어도 잡는다
            new Rule(PersonalInfoType.BIRTH_DATE, Pattern.compile("(?<!\\d)\\d{4}-\\*{2}-\\*{2}"), 0),
            // 라벨 뒤의 이름 ("수검자"는 "수검자 정보" 같은 제목과 구분하려고 콜론이 있을 때만).
            // PDF에서 표 구분선이 사라지면 머리 줄이 "성명 생년월일 성별 …"이 되므로, 다음 칸 이름은 이름으로 보지 않는다.
            // 라벨과 값 사이에는 콜론이나 공백이 있어야 한다 (2026-11-10 근거 문서 "…붙은 이름입니다"를 이름으로 본 오탐)
            new Rule(PersonalInfoType.NAME, Pattern.compile(
                    "(?:이름|성명|성함|환자명|수검자명|수검자\\s*[:：])(?:\\s*[:：]\\s*|\\s+)"
                            + "(?!생년월일|성별|나이|연령|검진|검사|주민|연락처|전화|주소|수검)([가-힣][가-힣*]{1,4})"), 1),
            // 일부를 가린 이름 (최*준). 표에서는 라벨과 값이 다른 줄에 있어도 잡는다
            new Rule(PersonalInfoType.NAME, Pattern.compile("(?<![가-힣*])[가-힣]\\*{1,2}[가-힣]{0,2}(?![가-힣*])"), 0),
            // 호칭 앞의 이름 (홍길동 님). 선생님·고객님 같은 일반 호칭은 뺀다
            new Rule(PersonalInfoType.NAME, Pattern.compile(
                    "(?<![가-힣])(?!(?:선생|고객|환자|수검자|회원|보호자|담당자)\\s*님)([가-힣][가-힣*]{1,3})(?=\\s*님(?![가-힣]))"), 1),
            // 라벨 뒤의 주소 (다음 칸 구분자나 줄 끝까지)
            new Rule(PersonalInfoType.ADDRESS, Pattern.compile(
                    "주소\\s*[:：]\\s*([^|\\n]+?)(?=\\s{2,}|\\s*[|·]|$)", Pattern.MULTILINE), 1),
            // 라벨 없는 도로명 주소 (가상시 가상구 가상로 123, 4층)
            new Rule(PersonalInfoType.ADDRESS, Pattern.compile(
                    "[가-힣]+(?:특별시|광역시|특별자치시|도|시)\\s+[가-힣]+(?:시|군|구)\\s+[가-힣0-9]+(?:로|길)\\s*\\d+(?:-\\d+)?"
                            + "(?:\\s*,\\s*\\d+층)?"), 0));

    /**
     * @param text 지운 글
     * @param counts 종류별로 지운 개수 (지운 것이 없으면 빈 맵)
     */
    public record Redaction(String text, Map<PersonalInfoType, Integer> counts) {

        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public Redaction redact(String text) {
        if (text == null || text.isEmpty()) {
            return new Redaction(text, Map.of());
        }
        Map<PersonalInfoType, Integer> counts = new EnumMap<>(PersonalInfoType.class);
        String result = text;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(result);
            StringBuilder out = new StringBuilder();
            int last = 0;
            while (matcher.find()) {
                int start = matcher.start(rule.group());
                int end = matcher.end(rule.group());
                out.append(result, last, start).append(rule.type().placeholder());
                last = end;
                counts.merge(rule.type(), 1, Integer::sum);
            }
            out.append(result, last, result.length());
            result = out.toString();
        }
        return new Redaction(result, Collections.unmodifiableMap(counts));
    }
}
