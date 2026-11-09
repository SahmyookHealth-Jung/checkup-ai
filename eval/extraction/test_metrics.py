"""metrics.py 단위 테스트.  실행: .venv/bin/python -m unittest discover -s extraction -t ."""
import unittest

from extraction.metrics import normalize_field, normalize_value, score_document, score_failed_document, score_fields


def item(code, value, raw="x"):
    return {"code": code, "rawName": raw, "value": value}


class ScoreDocumentTest(unittest.TestCase):

    def test_all_correct(self):
        expected = [item("FASTING_GLUCOSE", "112"), item("URINE_PROTEIN", "음성")]
        score = score_document(expected, list(expected))
        self.assertEqual((score.tp, score.fp, score.fn), (2, 0, 0))
        self.assertEqual(score.f1, 1.0)

    def test_wrong_value_counts_as_fp_and_fn(self):
        score = score_document([item("AST", "23")], [item("AST", "33")])
        self.assertEqual((score.tp, score.fp, score.fn), (0, 1, 1))
        self.assertEqual(score.errors[0]["type"], "wrong_value")

    def test_missing_and_extra(self):
        score = score_document([item("AST", "23")], [item("ALT", "44")])
        self.assertEqual((score.tp, score.fp, score.fn), (0, 1, 1))
        self.assertEqual({e["type"] for e in score.errors}, {"missing", "extra"})

    def test_duplicate_code_is_extra_fp(self):
        score = score_document([item("AST", "23")], [item("AST", "23"), item("AST", "30")])
        self.assertEqual((score.tp, score.fp, score.fn), (1, 1, 0))

    def test_duplicate_picks_matching_candidate(self):
        # 전회 값(30)을 먼저 뽑고 이번 값(23)도 뽑은 경우: 맞은 쪽을 TP로 본다
        score = score_document([item("AST", "23")], [item("AST", "30"), item("AST", "23")])
        self.assertEqual((score.tp, score.fp, score.fn), (1, 1, 0))

    def test_other_items_match_by_value(self):
        expected = [item("OTHER", "7.9", "요산"), item("OTHER", "5.1", "HbA1c")]
        predicted = [item("OTHER", "5.1", "당화혈색소"), item("OTHER", "1.2", "TSH")]
        score = score_document(expected, predicted)
        self.assertEqual((score.tp, score.fp, score.fn), (1, 1, 1))

    def test_known_item_labelled_other_is_miss_and_extra(self):
        score = score_document([item("FASTING_GLUCOSE", "112")], [item("OTHER", "112", "FBS")])
        self.assertEqual((score.tp, score.fp, score.fn), (0, 1, 1))

    def test_failed_document_misses_everything(self):
        score = score_failed_document([item("AST", "23"), item("ALT", "44")], "timeout")
        self.assertEqual((score.tp, score.fp, score.fn), (0, 0, 2))
        self.assertEqual(score.recall, 0.0)


class NormalizeValueTest(unittest.TestCase):

    def test_numeric_values_compare_numerically(self):
        self.assertEqual(normalize_value("26.0"), normalize_value("26"))
        self.assertEqual(normalize_value(" 112 "), normalize_value(112))

    def test_text_values_ignore_spaces(self):
        self.assertEqual(normalize_value("약양성 (±)"), normalize_value("약양성(±)"))
        self.assertNotEqual(normalize_value("음성"), normalize_value("양성"))

    def test_none(self):
        self.assertIsNone(normalize_value(None))


def full(code, value, unit=None, ref=None, judgment=None, raw="x"):
    return {"code": code, "rawName": raw, "value": value, "unit": unit, "referenceRaw": ref, "centerJudgment": judgment}


class ScoreFieldsTest(unittest.TestCase):

    def test_only_matched_items_are_scored(self):
        expected = [full("FASTING_GLUCOSE", "112", "mg/dL", "100 미만", "정상B"), full("HEMOGLOBIN", "13.1")]
        predicted = [full("FASTING_GLUCOSE", "112", "mg/dL", "100미만", "정상B"), full("HEMOGLOBIN", "31.1", "g/dL")]
        fields = score_fields(score_document(expected, predicted).matches)
        self.assertEqual(fields["unit"]["total"], 1)  # 값이 틀린 혈색소는 필드를 보지 않는다
        self.assertEqual(fields["referenceRaw"]["accuracy"], 1.0)  # 공백 차이는 무시

    def test_error_kinds(self):
        expected = [full("BMI", "22.9", ref=None, judgment="정상A"), full("WEIGHT", "68.5", ref="-"),
                    full("OTHER", "3.88", ref="0.4-4.5", raw="TSH")]
        predicted = [full("BMI", "22.9", ref="정상A", judgment=None), full("WEIGHT", "68.5", ref="null"),
                     full("OTHER", "3.88", ref="4.18", raw="TSH")]
        fields = score_fields(score_document(expected, predicted).matches)
        self.assertEqual({k: fields["referenceRaw"][k] for k in ("correct", "invented", "dropped", "wrong")},
                         {"correct": 1, "invented": 1, "dropped": 0, "wrong": 1})
        self.assertEqual(fields["centerJudgment"]["dropped"], 1)

    def test_empty_marks(self):
        for mark in (None, "", "-", "없음", "null", " N/A "):
            self.assertIsNone(normalize_field(mark))


if __name__ == "__main__":
    unittest.main()
