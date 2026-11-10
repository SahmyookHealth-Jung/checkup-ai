package com.checkupai.report.explanation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 화면 데모 설정 (ADR 0007 7절).
 *
 * @param samplesDir 합성 샘플 결과지 PDF 폴더. 공개 데모는 샘플만 쓰게 해서 실제 결과지가 외부 API로 나가지 않게 한다
 */
@ConfigurationProperties("checkupai.demo")
public record DemoProperties(@DefaultValue("../../data/synthetic/extraction/v1") String samplesDir) {
}
