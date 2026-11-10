package com.checkupai.report.retrieval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 근거 문서 적재와 검색 API (ADR 0007).
 *
 * <pre>
 * curl -X POST localhost:8080/api/guidelines/reload
 * curl -G localhost:8080/api/guidelines/search --data-urlencode 'q=공복혈당(Glucose) 질환의심'
 * </pre>
 */
@RestController
@RequestMapping("/api/guidelines")
@EnableConfigurationProperties(GuidelineProperties.class)
public class GuidelineController {

    private final GuidelineLoader loader;
    private final GuidelineSearch search;

    public GuidelineController(GuidelineLoader loader, GuidelineSearch search) {
        this.loader = loader;
        this.search = search;
    }

    /** 근거 청크 파일을 다시 적재한다 (같은 문서의 이전 청크는 지운다) */
    @PostMapping("/reload")
    public GuidelineLoader.LoadResult reload() {
        return loader.load();
    }

    /**
     * 검색 기준선 확인용 (검색 평가 스크립트 eval/rag/run_retrieval_eval.py가 쓴다).
     * itemCode를 주면 해설과 같은 항목 필터를 건다 (ADR 0008 4절 4번의 1. 이때 k는 설정값이다)
     */
    @GetMapping("/search")
    public SearchResult search(@RequestParam("q") String query, @RequestParam(name = "k", required = false) Integer topK,
                               @RequestParam(name = "itemCode", required = false) String itemCode) {
        if (itemCode != null && !itemCode.isBlank()) {
            return search.searchForItem(query, itemCode);
        }
        return topK == null ? search.search(query) : search.search(query, topK);
    }
}
