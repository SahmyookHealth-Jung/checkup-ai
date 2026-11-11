"""표기 변형 지표 단위 테스트 (모델 호출 없음).  실행 (eval/ 폴더에서): .venv/bin/python -m unittest discover -s rag -t ."""
import json
import random
import unittest

from rag.notation_metrics import assess_report, classify, summarize, truth_items
from rag.run_explanation_eval import CRITERIA
from synth.sampling import Measured
from synth.variants import NUMERIC, OTHER_UNITS, TEXT_VARIANTS, UNIT_NOTATIONS, apply_variants

CRITERIA_DATA = json.loads(CRITERIA.read_text(encoding="utf-8"))


class ClassifyTest(unittest.TestCase):

    def test_outcomes(self):
        self.assertEqual(classify("SUSPECT", "SUSPECT", False), "correct")
        self.assertEqual(classify("SUSPECT", "NORMAL_A", False), "falseReassurance")
        self.assertEqual(classify("NORMAL_A", "SUSPECT", False), "falseAlarm")
        self.assertEqual(classify("SUSPECT", "NOT_JUDGED", False), "silent")
        self.assertEqual(classify("SUSPECT", None, False), "silent")        # 추출에서 빠졌다
        self.assertEqual(classify("SUSPECT", None, True), "flagged")         # 확인 필요로 갔다
        self.assertEqual(classify("SUSPECT", "NOT_JUDGED", True), "flagged")
        self.assertEqual(classify("NOT_JUDGED", "NOT_JUDGED", False), "correct")  # 표에 없는 구간은 판정하지 않는 것이 맞다
        self.assertEqual(classify("NOT_JUDGED", "SUSPECT", False), "falseAlarm")

    def test_truth_uses_the_clean_value_and_report_uses_the_first_judgment(self):
        expected = {"sex": "MALE", "items": [
            {"code": "FASTING_GLUCOSE", "value": "7.8", "unit": "mmol/L"},
            {"code": "CHEST_XRAY", "value": "정상(특이소견 없음)", "unit": None},
            {"code": "AST", "value": "30", "unit": "U/L"},
            {"code": "OTHER", "value": "5.2", "unit": "%"}]}
        variants = [{"code": "FASTING_GLUCOSE", "kind": "unit_other", "ideal": "review", "canonicalValue": "140"},
                    {"code": "CHEST_XRAY", "kind": "text_variant", "ideal": "judge", "canonicalValue": "정상"}]
        self.assertEqual([(t["code"], t["kind"], t["truth"]) for t in truth_items(CRITERIA_DATA, expected, variants)],
                         [("FASTING_GLUCOSE", "unit_other", "SUSPECT"), ("CHEST_XRAY", "text_variant", "NORMAL_A"),
                          ("AST", "none", "NORMAL_A")])
        explanation = {"judgments": [{"code": "FASTING_GLUCOSE", "judgment": "NORMAL_A"},
                                     {"code": "CHEST_XRAY", "judgment": "SUSPECT"}], "needsReview": [{"code": "AST"}]}
        rows = assess_report(CRITERIA_DATA, expected, variants, explanation)
        self.assertEqual([r["outcome"] for r in rows], ["falseReassurance", "falseAlarm", "flagged"])
        summary = summarize(rows)
        self.assertEqual((summary["falseReassurance"], summary["falseAlarm"], summary["flagged"]), (1, 1, 1))
        self.assertEqual(summary["suspectReached"], "0/1")
        self.assertEqual(summary["byKind"]["unit_other"]["falseReassurance"], 1)


class VariantTest(unittest.TestCase):

    def test_variants_keep_the_clean_value_as_truth(self):
        items = [Measured("FASTING_GLUCOSE", ("공복혈당", "FBS", "공복 혈당"), "140", "130", "mg/dL", "100 미만", "abnormal"),
                 Measured("CHEST_XRAY", ("흉부방사선", "Chest PA", "흉부 X선"), "정상", "정상", None, None, "normal"),
                 Measured("HEIGHT", ("신장", "Height", "키"), "170.0", "170.1", "cm", None, None)]
        seen = set()
        for seed in range(200):
            changed, truth = apply_variants(random.Random(seed), items)
            self.assertEqual(changed[2], items[2])  # 판정 대상이 아닌 항목은 그대로다
            for t in truth:
                original = next(m for m in items if m.code == t["code"])
                self.assertEqual((t["canonicalValue"], t["canonicalUnit"]), (original.value, original.unit))
                self.assertTrue(t["printedValue"] != original.value or t["printedUnit"] != original.unit)
                seen.add(t["kind"])
        self.assertEqual(seen, {"unit_notation", "flag", "unit_other", "text_variant"})

    def test_every_numeric_item_has_a_unit_notation_and_text_items_cover_all_bands(self):
        units = {"HEMOGLOBIN": "g/dL", "EGFR": "mL/min/1.73m²", "AST": "U/L", "ALT": "U/L", "GAMMA_GTP": "U/L"}
        for code in NUMERIC:
            self.assertIn(units.get(code, "mg/dL"), UNIT_NOTATIONS)
        self.assertTrue(set(OTHER_UNITS) <= NUMERIC)
        for bands in TEXT_VARIANTS.values():
            self.assertEqual(set(bands), {"normal", "borderline", "abnormal"})


if __name__ == "__main__":
    unittest.main()
