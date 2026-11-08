-- pgvector 확장을 켠다.
-- 벡터 테이블 설계(메타데이터, 키워드 검색 컬럼, 인덱스)는 2주차 ADR에서 정한 뒤 별도 마이그레이션으로 추가한다.
CREATE EXTENSION IF NOT EXISTS vector;
