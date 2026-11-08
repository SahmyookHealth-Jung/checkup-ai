package com.checkupai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 실행 모듈. {@code com.checkupai} 하위의 모든 모듈(core, report, ...)을 스캔해 조립한다.
 */
@SpringBootApplication
public class CheckupAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckupAiApplication.class, args);
    }
}
