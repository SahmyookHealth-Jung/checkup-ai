package com.checkupai.report.followup;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkupai.report.extraction.ItemCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ItemMentionsTest {

    private final ItemMentions mentions = new ItemMentions();

    @Test
    @DisplayName("질문에 나온 항목 이름과 동의어로 항목을 찾는다. 영문은 대소문자를 가리지 않는다")
    void findsItemsByAlias() {
        assertThat(mentions.find("egfr 44면 무슨 뜻이에요?")).containsExactly(ItemCode.EGFR);
        assertThat(mentions.find("혈압이 높고 간수치도 안 좋은 거지")).containsExactly(ItemCode.SYSTOLIC_BP,
                ItemCode.DIASTOLIC_BP, ItemCode.AST, ItemCode.ALT, ItemCode.GAMMA_GTP);
        assertThat(mentions.find("빈혈이면 뭘 먹어야 해요")).containsExactly(ItemCode.HEMOGLOBIN);
    }

    @Test
    @DisplayName("이름이 없거나 헷갈리는 말('신장'은 키와 같은 말)로는 항목을 정하지 않는다")
    void ignoresAmbiguousWords() {
        assertThat(mentions.find("결과값이 어려운데 뭔가요")).isEmpty();
        assertThat(mentions.find("신장이 안 좋은 거지?")).isEmpty();
        assertThat(mentions.find(null)).isEmpty();
    }
}
