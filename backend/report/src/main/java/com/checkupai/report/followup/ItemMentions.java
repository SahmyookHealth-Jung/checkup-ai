package com.checkupai.report.followup;

import com.checkupai.report.extraction.ItemCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 후속 질문 문장에서 검진 항목을 찾는다 (ADR 0011 1절, 자유 입력창). 이름 사전({@value #RESOURCE})의 부분 문자열로 찾는다.
 * 정해진 규칙이라 결과를 설명할 수 있다. 찾지 못하면 문맥 만들기가 결과지의 주의 항목으로 대신한다.
 */
@Component
public class ItemMentions {

    static final String RESOURCE = "followup/item-aliases.json";

    record Dictionary(String description, Map<ItemCode, List<String>> aliases) {
    }

    private final Map<ItemCode, List<String>> aliases;

    public ItemMentions() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            this.aliases = JsonMapper.builder().build().readValue(in, Dictionary.class).aliases();
        } catch (IOException e) {
            throw new UncheckedIOException("항목 이름 사전을 읽지 못했다: " + RESOURCE, e);
        }
    }

    /** 질문에 이름이 나온 항목 (ItemCode 선언 순서) */
    public Set<ItemCode> find(String question) {
        Set<ItemCode> found = EnumSet.noneOf(ItemCode.class);
        if (question == null) {
            return found;
        }
        String text = question.toLowerCase(Locale.ROOT);
        aliases.forEach((code, names) -> {
            if (names.stream().anyMatch(name -> text.contains(name.toLowerCase(Locale.ROOT)))) {
                found.add(code);
            }
        });
        return found;
    }
}
