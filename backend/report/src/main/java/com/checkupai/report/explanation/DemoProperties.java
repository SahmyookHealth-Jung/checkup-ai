package com.checkupai.report.explanation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 화면 데모 설정 (ADR 0007 7절).
 *
 * @param samplesDir 합성 샘플 결과지 PDF 폴더. 화면에서는 가상 결과지도 올릴 수 있다(ADR 0012 보완). 실제 결과지는 올리지 않는다
 */
@ConfigurationProperties("checkupai.demo")
public record DemoProperties(@DefaultValue("../../data/synthetic/extraction/v1") String samplesDir) {
}
