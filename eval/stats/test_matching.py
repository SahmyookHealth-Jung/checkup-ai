"""실행 결과 맞추기 규칙의 단위 테스트: .venv/bin/python -m unittest stats.test_matching"""
import unittest

from stats.run_stats_eval import matches


class MatchingTest(unittest.TestCase):
    gold = {"columns": ["region", "avg", "n"], "rows": [["서울특별시", "101.23", "175300"], ["세종특별자치시", "99.0", "7636"]]}

    def test_extra_columns_and_order_free(self):
        pred = {"rows": [["세종특별자치시", 36, 99.04, 7636], ["서울특별시", 11, 101.2, 175300]]}
        self.assertTrue(matches(self.gold, pred, ordered=False))

    def test_wrong_value_or_rows(self):
        self.assertFalse(matches(self.gold, {"rows": [["서울특별시", 101.5, 175300], ["세종특별자치시", 99.0, 7636]]}, ordered=False))
        self.assertFalse(matches(self.gold, {"rows": [["서울특별시", 101.2, 175300]]}, ordered=False))
        self.assertFalse(matches(self.gold, None, ordered=False))

    def test_ordered_requires_order(self):
        pred = {"rows": [["세종특별자치시", 99.0, 7636], ["서울특별시", 101.2, 175300]]}
        self.assertFalse(matches(self.gold, pred, ordered=True))

    def test_small_groups_suppressed_on_both_sides(self):
        gold = {"columns": ["age", "pct", "n"], "rows": [["85세 이상", "12.5", "8"]]}
        self.assertTrue(matches(gold, {"rows": [["85세 이상", None, None]]}, ordered=False))

    def test_label_wording_normalized_but_meaning_checked(self):
        gold = {"columns": ["smoking", "avg", "n"], "rows": [["피우지 않음", "27.6", "638782"], ["현재 흡연", "54.7", "185546"]]}
        same = {"rows": [["피우지 않는다", 27.6, 638782], ["현재도 피운다", 54.7, 185546]]}
        swapped = {"rows": [["현재도 피운다", 27.6, 638782], ["피우지 않는다", 54.7, 185546]]}
        self.assertTrue(matches(gold, same, ordered=False))
        self.assertFalse(matches(gold, swapped, ordered=False))  # 이름을 뒤바꾼 답은 틀린다
        self.assertTrue(matches(gold, swapped, ordered=False, numeric=True))  # 숫자 기준은 못 잡는다 (진단용인 이유)


if __name__ == "__main__":
    unittest.main()
