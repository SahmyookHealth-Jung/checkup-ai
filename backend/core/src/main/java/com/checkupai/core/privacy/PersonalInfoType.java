package com.checkupai.core.privacy;

/** 외부로 보내기 전에 지우는 개인 식별정보의 종류와, 그 자리에 넣는 표시 (ADR 0006) */
public enum PersonalInfoType {
    NAME("[이름]"),
    BIRTH_DATE("[생년월일]"),
    RESIDENT_NUMBER("[주민등록번호]"),
    RECORD_NUMBER("[수검번호]"),
    PHONE("[전화번호]"),
    EMAIL("[이메일]"),
    ADDRESS("[주소]");

    private final String placeholder;

    PersonalInfoType(String placeholder) {
        this.placeholder = placeholder;
    }

    public String placeholder() {
        return placeholder;
    }
}
