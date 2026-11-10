"""explanation_metrics.py 단위 테스트.  실행 (eval/ 폴더에서): .venv/bin/python -m unittest discover -s rag -t ."""
import json
import unittest

from rag.explanation_metrics import assess_report, compact_output, miss_cause, summarize
from run_record import ROOT

CRITERIA = json.loads((ROOT / "backend/report/src/main/resources/criteria/general-screening-2026-6.json")
                      .read_text(encoding="utf-8"))


def explanation(judgments=(), items=(), citations=()):
    return {"judgments": list(judgments), "items": list(items), "citations": list(citations), "disclaimer": "고지",
            "metrics": {"targets": len(items), "targetsWithEvidence": len(items), "sentencesGenerated": 2,
                        "sentencesKept": 1, "invalidCitations": 0, "uncitedSentences": 1,
                        "ungroundedNumberSentences": 0, "forbiddenHits": 0, "retrievalMs": 10, "llmMs": 100,
                        "totalMs": 120, "llmError": None, "salvagedItems": -1}}


def judged(code, value, judgment):
    return {"code": code, "value": value, "judgment": judgment}


def explained(code, value="131", advice=True, sentences=()):
    return {"code": code, "value": value, "unit": "mg/dL", "judgment": "SUSPECT", "criterion": "126 이상",
            "noEvidence": not sentences, "sentences": list(sentences), "careAdvice": "진료" if advice else None}


class MissCauseTest(unittest.TestCase):
    report = {"sex": "MALE"}

    def test_item_not_extracted_is_extraction(self):
        self.assertEqual(miss_cause("FASTING_GLUCOSE", "131", "MALE", self.report, [], explanation()), "extraction")

    def test_wrong_value_is_extraction(self):
        x = explanation(judgments=[judged("FASTING_GLUCOSE", "113", "NORMAL_B")])
        self.assertEqual(miss_cause("FASTING_GLUCOSE", "131", "MALE", self.report, [], x), "extraction")

    def test_wrong_sex_is_extraction(self):
        x = explanation(judgments=[judged("HEMOGLOBIN", "11.5", "NORMAL_B")])
        self.assertEqual(miss_cause("HEMOGLOBIN", "11.5", "MALE", {"sex": "FEMALE"}, [], x), "extraction")

    def test_same_value_and_sex_but_different_judgment_is_judgment(self):
        x = explanation(judgments=[judged("FASTING_GLUCOSE", "131.0", "NORMAL_B")])
        self.assertEqual(miss_cause("FASTING_GLUCOSE", "131", "MALE", self.report, [], x), "judgment")

    def test_suspect_but_not_explained_is_max_items(self):
        x = explanation(judgments=[judged("FASTING_GLUCOSE", "131", "SUSPECT")])
        self.assertEqual(miss_cause("FASTING_GLUCOSE", "131", "MALE", self.report, [], x), "maxItems")

    def test_out_of_range_value_is_needs_review(self):
        review = [{"code": "AST", "value": "6200"}]
        self.assertEqual(miss_cause("AST", "6200", "MALE", self.report, review, explanation()), "needsReview")


class AssessAndSummarizeTest(unittest.TestCase):

    def test_recall_precision_and_causes(self):
        expected = {"sex": "MALE", "items": [{"code": "FASTING_GLUCOSE", "value": "131"},
                                             {"code": "SYSTOLIC_BP", "value": "150"},
                                             {"code": "OTHER", "value": "999"}]}
        x = explanation(judgments=[judged("FASTING_GLUCOSE", "131", "SUSPECT"), judged("SYSTOLIC_BP", "125", "NORMAL_B"),
                                   judged("LDL_CHOLESTEROL", "170", "SUSPECT")],
                        items=[explained("FASTING_GLUCOSE"), explained("LDL_CHOLESTEROL", "170")])
        row = assess_report(CRITERIA, expected, {"sex": "MALE"}, [], x)

        self.assertEqual(row["expectedSuspect"], ["FASTING_GLUCOSE", "SYSTOLIC_BP"])
        self.assertEqual(row["misses"], [{"code": "SYSTOLIC_BP", "cause": "extraction"}])
        self.assertEqual(row["falseAdvice"], ["LDL_CHOLESTEROL"])

        s = summarize([row, row])
        self.assertEqual(s["endToEnd"]["expectedSuspect"], 4)
        self.assertEqual(s["endToEnd"]["advisedCorrect"], 2)
        self.assertEqual(s["endToEnd"]["careAdviceRecall"], 0.5)
        self.assertEqual(s["endToEnd"]["careAdvicePrecision"], 0.5)
        self.assertEqual(s["endToEnd"]["missCauses"]["extraction"], 2)
        self.assertEqual(s["explanationLlm"]["sentencePassRate"], 0.5)
        self.assertEqual(s["codeGuaranteed"]["disclaimer"], "2/2")


class CompactOutputTest(unittest.TestCase):

    def test_citation_numbers_become_chunk_ids_and_source_text_is_dropped(self):
        x = explanation(items=[explained("FASTING_GLUCOSE", sentences=[{"text": "문장", "citations": [2, 1]}])],
                        citations=[{"number": 1, "chunkId": "row-glucose", "text": "원문"},
                                   {"number": 2, "chunkId": "kdca-1", "text": "포털 원문"}])
        out = compact_output(x)
        self.assertEqual(out[0]["sentences"], [{"text": "문장", "sources": ["kdca-1", "row-glucose"]}])
        self.assertNotIn("원문", json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    unittest.main()
