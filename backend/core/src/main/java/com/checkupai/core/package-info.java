/**
 * 공통 기반 모듈.
 *
 * <p>기능 1(결과 해설)과 기능 2(Text-to-SQL)가 함께 쓰는 것만 둔다.
 * <ul>
 *   <li>AI 호출 관측: 토큰, 비용, 지연시간 기록</li>
 *   <li>공통 가드레일: "의료 진단이 아님" 원칙, 금지 표현 검사</li>
 *   <li>공통 예외와 응답 형식</li>
 * </ul>
 *
 * <p>규칙: 이 모듈은 다른 기능 모듈({@code report}, {@code stats})에 의존하지 않는다.
 * 특정 모델 공급자(Ollama, Anthropic) 구현 클래스를 직접 참조하지 않는다.
 */
package com.checkupai.core;
