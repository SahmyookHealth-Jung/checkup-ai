rootProject.name = "checkup-ai"

// core   : 공통 기반 (AI 모델 추상화, 호출 관측·비용 기록, 가드레일 공통, 예외)
// report : 기능 1 - 건강검진 결과 해설 (결과지 추출 + RAG)
// app    : 실행 모듈 - 기능 모듈을 조립하고 모델/DB 설정을 가진 Spring Boot 앱
// stats  : 기능 2 - Text-to-SQL 통계 어시스턴트 (7주차에 추가 예정)
include("core", "report", "app")
