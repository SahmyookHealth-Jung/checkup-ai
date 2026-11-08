package com.checkupai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실행 전제: {@code docker compose up -d}로 PostgreSQL(pgvector)이 떠 있어야 한다.
 * 실행: {@code ./gradlew :app:integrationTest}
 */
@Tag("integration")
@SpringBootTest
class CheckupAiApplicationIT {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("애플리케이션이 뜨고 Flyway가 pgvector 확장을 켠다")
    void contextLoadsAndPgvectorIsEnabled() {
        String version = jdbcTemplate.queryForObject(
                "select extversion from pg_extension where extname = 'vector'", String.class);

        assertThat(version).isNotBlank();
    }
}
