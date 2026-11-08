"""metrics.py 단위 테스트.  실행: .venv/bin/python -m unittest discover -s extraction -t ."""
import unittest

from extraction.metrics import normalize_value, score_document, score_failed_document


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


if __name__ == "__main__":
    unittest.main()
