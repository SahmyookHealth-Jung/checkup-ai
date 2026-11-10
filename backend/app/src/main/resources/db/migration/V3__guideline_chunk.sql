-- 해설 근거 청크 (ADR 0007 3절). Spring AI PgVectorStore가 읽고 쓰는 형식을 따른다:
-- id(청크 id, 사람이 읽을 수 있는 문자열), content(본문), metadata(문서명·기관·판·절·라이선스·관련 검진 항목), embedding.
-- 임베딩은 로컬 bge-m3(1024차원), 거리는 코사인. 검색 기준선은 벡터 상위 4개이고, 키워드·필터 검색은 5단계에서 비교한다.
CREATE TABLE guideline_chunk (
    id        TEXT PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding vector(1024)
);

CREATE INDEX guideline_chunk_embedding_idx ON guideline_chunk USING hnsw (embedding vector_cosine_ops);
