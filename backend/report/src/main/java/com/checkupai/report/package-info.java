/**
 * 기능 1: 건강검진 결과 해설.
 *
 * <ol>
 *   <li>결과지(PDF/이미지/텍스트)에서 수치를 추출해 JSON으로 구조화한다 (Structured Output).</li>
 *   <li>공개 가이드라인 문서를 검색해 근거를 찾는다 (RAG, 하이브리드 검색, 리랭킹).</li>
 *   <li>출처를 인용해 해설하고, 위험 수치는 진료를 권고한다. 진단은 하지 않는다.</li>
 * </ol>
 *
 * <p>규칙: {@code core}에만 의존한다. 다른 기능 모듈({@code stats})을 참조하지 않는다.
 */
package com.checkupai.report;
