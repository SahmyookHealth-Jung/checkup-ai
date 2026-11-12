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


if __name__ == "__main__":
    unittest.main()
