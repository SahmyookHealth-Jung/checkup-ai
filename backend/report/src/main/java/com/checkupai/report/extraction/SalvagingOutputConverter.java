package com.checkupai.report.extraction;

import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.StructuredOutputConverter;

/**
 * 구조화 출력 변환기. 평소에는 {@link BeanOutputConverter}와 같고, 출력이 잘려 변환에 실패하면
 * 완성된 항목까지만 살려 다시 변환한다 ({@link TruncatedJson}).
 *
 * <p>JSON 스키마와 형식 안내는 그대로 위임하므로 네이티브 구조화 출력도 똑같이 동작한다.
 * 호출마다 새로 만든다(복구 여부를 상태로 가진다). 루트 객체의 마지막 속성이 items 배열인 출력에 쓴다
 * (추출의 CheckupReport·ItemList, 해설의 ExplanationDraft).
 */
public final class SalvagingOutputConverter<T> implements StructuredOutputConverter<T> {

    private final BeanOutputConverter<T> delegate;
    private TruncatedJson.Repair repair;

    public SalvagingOutputConverter(Class<T> type) {
        this.delegate = new BeanOutputConverter<>(type);
    }

    @Override
    public T convert(String text) {
        try {
            return delegate.convert(text);
        } catch (RuntimeException e) {
            TruncatedJson.Repair repaired = TruncatedJson.closeAfterLastCompleteItem(text);
            if (repaired == null) {
                throw e;
            }
            T result = delegate.convert(repaired.json());
            this.repair = repaired;
            return result;
        }
    }

    @Override
    public String getFormat() {
        return delegate.getFormat();
    }

    @Override
    public String getJsonSchema() {
        return delegate.getJsonSchema();
    }

    /** 잘린 출력을 살렸으면 살린 항목 수, 아니면 -1 */
    public int salvagedItems() {
        return repair == null ? -1 : repair.items();
    }
}
