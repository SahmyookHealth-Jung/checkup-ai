-- 기능 2 통계 데이터 (ADR 0013). 출처: 공공데이터포털 "국민건강보험공단_건강검진정보" 2023 (이용허락 제한 없음).
-- 코드값 뜻은 함께 배포된 "건강검진정보 사용자 매뉴얼_2023"에서 옮겼다. 데이터는 eval/stats/load_checkup.py가 적재한다.
-- 가입자 일련번호는 적재하지 않는다(개인 단위 조회를 막는다). 2023년에 비어 있는 결손치·치아마모·사랑니 항목도 뺐다.
-- 컬럼 설명(COMMENT)은 LLM에 주는 스키마 설명으로도 쓴다.
CREATE SCHEMA stats;

CREATE TABLE stats.region (
    code  SMALLINT PRIMARY KEY,
    name  TEXT NOT NULL
);
COMMENT ON TABLE stats.region IS '시도 코드표 (17개 시도)';
COMMENT ON COLUMN stats.region.code IS '시도코드';
COMMENT ON COLUMN stats.region.name IS '시도 이름 (예: 서울특별시, 경기도)';
INSERT INTO stats.region (code, name) VALUES
    (11, '서울특별시'), (26, '부산광역시'), (27, '대구광역시'), (28, '인천광역시'), (29, '광주광역시'),
    (30, '대전광역시'), (31, '울산광역시'), (36, '세종특별자치시'), (41, '경기도'), (42, '강원도'),
    (43, '충청북도'), (44, '충청남도'), (45, '전라북도'), (46, '전라남도'), (47, '경상북도'),
    (48, '경상남도'), (49, '제주특별자치도');

CREATE TABLE stats.age_group (
    code     SMALLINT PRIMARY KEY,
    label    TEXT NOT NULL,
    min_age  SMALLINT NOT NULL,
    max_age  SMALLINT
);
COMMENT ON TABLE stats.age_group IS '연령대 코드표 (5세 단위, 2014년 이후 체계). 검진 대상이 20세 이상이라 데이터에는 5(20~24세)부터 있다';
COMMENT ON COLUMN stats.age_group.code IS '연령대 코드';
COMMENT ON COLUMN stats.age_group.label IS '연령대 이름 (예: 40~44세, 85세 이상)';
COMMENT ON COLUMN stats.age_group.min_age IS '연령대의 가장 어린 나이';
COMMENT ON COLUMN stats.age_group.max_age IS '연령대의 가장 많은 나이 (85세 이상은 NULL)';
INSERT INTO stats.age_group (code, label, min_age, max_age) VALUES
    (1, '0~4세', 0, 4), (2, '5~9세', 5, 9), (3, '10~14세', 10, 14), (4, '15~19세', 15, 19),
    (5, '20~24세', 20, 24), (6, '25~29세', 25, 29), (7, '30~34세', 30, 34), (8, '35~39세', 35, 39),
    (9, '40~44세', 40, 44), (10, '45~49세', 45, 49), (11, '50~54세', 50, 54), (12, '55~59세', 55, 59),
    (13, '60~64세', 60, 64), (14, '65~69세', 65, 69), (15, '70~74세', 70, 74), (16, '75~79세', 75, 79),
    (17, '80~84세', 80, 84), (18, '85세 이상', 85, NULL);

CREATE TABLE stats.checkup (
    year             SMALLINT NOT NULL,
    region_code      SMALLINT NOT NULL REFERENCES stats.region (code),
    sex              SMALLINT NOT NULL,
    age_group        SMALLINT NOT NULL REFERENCES stats.age_group (code),
    height_cm        SMALLINT,
    weight_kg        SMALLINT,
    waist_cm         NUMERIC(5, 1),
    vision_left      NUMERIC(3, 1),
    vision_right     NUMERIC(3, 1),
    hearing_left     SMALLINT,
    hearing_right    SMALLINT,
    systolic_bp      SMALLINT,
    diastolic_bp     SMALLINT,
    fasting_glucose  SMALLINT,
    total_chol       SMALLINT,
    triglycerides    NUMERIC(6, 1),
    hdl              NUMERIC(6, 1),
    ldl              SMALLINT,
    hemoglobin       NUMERIC(4, 1),
    urine_protein    SMALLINT,
    creatinine       NUMERIC(4, 1),
    ast              SMALLINT,
    alt              SMALLINT,
    ggt              SMALLINT,
    smoking          SMALLINT,
    drinking         SMALLINT,
    oral_exam        SMALLINT,
    dental_caries    SMALLINT,
    tartar           SMALLINT
);
COMMENT ON TABLE stats.checkup IS '2023년 일반건강검진 수검자 100만 명 무작위 표본. 한 행이 한 사람이다. 20세 이상';
COMMENT ON COLUMN stats.checkup.year IS '기준년도 (모두 2023)';
COMMENT ON COLUMN stats.checkup.region_code IS '거주지 시도코드 (stats.region.code)';
COMMENT ON COLUMN stats.checkup.sex IS '성별: 1 남자, 2 여자';
COMMENT ON COLUMN stats.checkup.age_group IS '연령대 코드 (stats.age_group.code)';
COMMENT ON COLUMN stats.checkup.height_cm IS '키, 5cm 단위로 내림 (예: 170~174cm → 170)';
COMMENT ON COLUMN stats.checkup.weight_kg IS '몸무게, 5kg 단위로 내림 (예: 65~69kg → 65)';
COMMENT ON COLUMN stats.checkup.waist_cm IS '허리둘레 cm (999는 측정값이 아닌 것으로 보인다)';
COMMENT ON COLUMN stats.checkup.vision_left IS '왼쪽 시력 0.1~2.5 (0.1 이하는 0.1, 실명은 9.9)';
COMMENT ON COLUMN stats.checkup.vision_right IS '오른쪽 시력 0.1~2.5 (0.1 이하는 0.1, 실명은 9.9)';
COMMENT ON COLUMN stats.checkup.hearing_left IS '왼쪽 청력: 1 정상, 2 비정상 (3도 소수 있으나 매뉴얼에 뜻이 없다)';
COMMENT ON COLUMN stats.checkup.hearing_right IS '오른쪽 청력: 1 정상, 2 비정상 (3도 소수 있으나 매뉴얼에 뜻이 없다)';
COMMENT ON COLUMN stats.checkup.systolic_bp IS '수축기 혈압 mmHg';
COMMENT ON COLUMN stats.checkup.diastolic_bp IS '이완기 혈압 mmHg';
COMMENT ON COLUMN stats.checkup.fasting_glucose IS '공복혈당(식전혈당) mg/dL';
COMMENT ON COLUMN stats.checkup.total_chol IS '총콜레스테롤 mg/dL. 4년마다 검사하는 항목이라 약 34%만 값이 있다';
COMMENT ON COLUMN stats.checkup.triglycerides IS '중성지방(트리글리세라이드) mg/dL. 약 34%만 값이 있다';
COMMENT ON COLUMN stats.checkup.hdl IS 'HDL 콜레스테롤 mg/dL. 약 34%만 값이 있다';
COMMENT ON COLUMN stats.checkup.ldl IS 'LDL 콜레스테롤 mg/dL. 약 33%만 값이 있다';
COMMENT ON COLUMN stats.checkup.hemoglobin IS '혈색소 g/dL';
COMMENT ON COLUMN stats.checkup.urine_protein IS '요단백: 1 음성(-), 2 약양성(±), 3 양성(+1), 4 (+2), 5 (+3), 6 (+4)';
COMMENT ON COLUMN stats.checkup.creatinine IS '혈청 크레아티닌 mg/dL';
COMMENT ON COLUMN stats.checkup.ast IS 'AST(혈청지오티) IU/L';
COMMENT ON COLUMN stats.checkup.alt IS 'ALT(혈청지피티) IU/L';
COMMENT ON COLUMN stats.checkup.ggt IS '감마지티피 IU/L';
COMMENT ON COLUMN stats.checkup.smoking IS '흡연 상태: 1 피우지 않는다, 2 이전에 피웠으나 끊었다, 3 현재도 피운다';
COMMENT ON COLUMN stats.checkup.drinking IS '음주 여부: 0 마시지 않는다, 1 마신다';
COMMENT ON COLUMN stats.checkup.oral_exam IS '구강검진 수검 여부: 0 미수검, 1 수검';
COMMENT ON COLUMN stats.checkup.dental_caries IS '치아우식증(충치): 0 없음, 1 있음. 구강검진 수검자만 값이 있다';
COMMENT ON COLUMN stats.checkup.tartar IS '치석: 0 없음, 1 있음 (2도 소수 있으나 매뉴얼에 뜻이 없다). 구강검진 수검자만 값이 있다';

-- 읽기 전용 계정 (ADR 0013 1절). stats 스키마만 SELECT할 수 있다. 앱의 다른 테이블(llm_call_log 등)은 볼 수 없다.
-- 비밀번호는 Flyway 자리표시자로 .env의 STATS_READER_PASSWORD에서 받는다 (파일에 적지 않는다).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stats_reader') THEN
        CREATE ROLE stats_reader LOGIN PASSWORD '${statsReaderPassword}';
    ELSE
        ALTER ROLE stats_reader LOGIN PASSWORD '${statsReaderPassword}';
    END IF;
END
$$;
ALTER ROLE stats_reader SET default_transaction_read_only = on;
ALTER ROLE stats_reader SET statement_timeout = '5s';
ALTER ROLE stats_reader SET search_path = stats;
GRANT CONNECT ON DATABASE ${flyway:database} TO stats_reader;
GRANT USAGE ON SCHEMA stats TO stats_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA stats TO stats_reader;
-- public 스키마의 테이블(llm_call_log, guideline_chunk 등)에는 권한을 주지 않으므로 이 계정은 읽을 수 없다.
