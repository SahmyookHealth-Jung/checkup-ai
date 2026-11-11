"""오류 심은 세트 단위 테스트 (모델 호출 없음).  실행 (eval/ 폴더에서): .venv/bin/python -m unittest discover -s rag -t ."""
import unittest
from collections import Counter

from rag.build_seeded_set import DEFAULT_JUDGE, load_bases, row_codes
from rag.seeded_errors import (APPEND_BASES, APPENDED, SWAP_EXCLUDED, TEXT_EDITS, TYPES, apply_edit, build_rows,
                               clean_bases, is_correct, score, swap_target)

ROW_CODES = {"mohw-annex4-row-triglycerides": ["TRIGLYCERIDES"], "mohw-annex4-row-blood-pressure": ["SYSTOLIC_BP", "DIASTOLIC_BP"],
             "mohw-annex4-row-hemoglobin": ["HEMOGLOBIN"], "mohw-annex4-row-urine-protein": ["URINE_PROTEIN"],
             "mohw-annex4-row-egfr": ["EGFR"], "mohw-annex4-row-bmi": ["BMI", "HEIGHT", "WEIGHT"]}


def judged(cal_id, support="supported", band="correct", treatment="none", repeats=1):
    return {"id": cal_id, "repeats": [{"result": {"support": support, "band": band, "treatment": treatment}}] * repeats}


class EditTest(unittest.TestCase):

    def test_edit_replaces_text_that_occurs_exactly_once(self):
        self.assertEqual(apply_edit("결과는 질환의심 구간에 해당합니다.", "질환의심 구간", "정상A 구간"),
                         "결과는 정상A 구간에 해당합니다.")

    def test_edit_fails_when_text_is_missing_or_repeated(self):
        with self.assertRaises(ValueError):
            apply_edit("결과는 정상A입니다.", "질환의심", "정상A")
        with self.assertRaises(ValueError):
            apply_edit("140 이상 또는 140 이상", "140 이상", "120 이상")

    def test_swap_target_skips_own_row_and_original_citation(self):
        self.assertEqual(swap_target(0, "TRIGLYCERIDES", [], ROW_CODES), "mohw-annex4-row-blood-pressure")
        self.assertEqual(swap_target(0, "ALT", ["mohw-annex4-row-triglycerides"], ROW_CODES),
                         "mohw-annex4-row-blood-pressure")
        self.assertEqual(swap_target(7, "ALT", [], ROW_CODES), "mohw-annex4-row-blood-pressure")  # 목록을 돌아간다


class CleanBaseTest(unittest.TestCase):

    def test_base_needs_human_and_every_judge_repeat_to_pass(self):
        rows = [{"calId": c} for c in ("a", "b", "c", "d")]
        labels = {"a": {"support": "supported", "treatment": "none"}, "b": {"support": "partial", "treatment": "none"},
                  "c": {"support": "supported", "treatment": "none"}, "d": {"support": "supported", "treatment": "none"}}
        details = [judged("a", repeats=3), judged("b", repeats=3),
                   {"id": "c", "repeats": [{"result": {"support": "supported", "band": "correct", "treatment": "none"}},
                                           {"result": {"support": "partial", "band": "correct", "treatment": "none"}}]},
                   {"id": "d", "repeats": [{"error": "stop_reason=max_tokens"}]}]
        self.assertEqual([r["calId"] for r in clean_bases(rows, labels, details)], ["a"])


class RealSetTest(unittest.TestCase):
    """커밋된 보정셋, 사람 라벨, 보정 채점 결과로 실제 세트를 만들어 본다"""

    @classmethod
    def setUpClass(cls):
        cls.bases, cls.judge = load_bases(DEFAULT_JUDGE)
        cls.by_id = {b["calId"]: b for b in cls.bases}
        cls.rows = build_rows(cls.bases, row_codes())

    def test_counts_by_type(self):
        self.assertEqual(len(self.bases), 28)
        self.assertEqual(Counter(r["stratum"] for r in self.rows),
                         {"band_name": 15, "band_number": 17, "band_sex": 5, "inversion": 6, "drug_added": APPEND_BASES,
                          "dose_change": APPEND_BASES, "control_lifestyle": APPEND_BASES, "unsupported_fact": APPEND_BASES,
                          "citation_swap": 28 - len(SWAP_EXCLUDED), "smoke": 3, "base": 28})
        self.assertEqual(len({r["calId"] for r in self.rows}), len(self.rows))
        self.assertEqual({r["stratum"] for r in self.rows}, set(TYPES))

    def test_same_input_builds_the_same_set(self):
        self.assertEqual(build_rows(self.bases, row_codes()), self.rows)

    def test_band_edits_use_bases_whose_band_everyone_called_correct(self):
        labels = {d["id"]: [r["result"]["band"] for r in d["repeats"]] for d in self.judge["details"]}
        for kind in ("band_name", "band_number", "band_sex"):
            for cal_id, _, _ in TEXT_EDITS[kind]:
                self.assertEqual(set(labels[cal_id]), {"correct"}, f"{kind} {cal_id}")

    def test_each_sentence_changes_exactly_one_thing(self):
        for row in self.rows:
            base, change = self.by_id[row["seed"]["base"]], row["seed"]["change"]
            text_changed, sources_changed = row["text"] != base["text"], row["sources"] != base["sources"]
            expected = {"replace": (True, False), "append": (True, False), "text": (True, False),
                        "sources": (False, True), "none": (False, False)}[change["kind"]]
            self.assertEqual((text_changed, sources_changed), expected, row["calId"])
            self.assertEqual(row["item"], base["item"], row["calId"])

    def test_swapped_citation_is_another_items_row(self):
        codes = row_codes()
        for row in (r for r in self.rows if r["stratum"] == "citation_swap"):
            self.assertEqual(len(row["sources"]), 1)
            self.assertNotIn(row["item"]["code"], codes[row["sources"][0]], row["calId"])
            self.assertNotIn(row["seed"]["base"], SWAP_EXCLUDED)

    def test_treatment_types_share_bases_and_fact_bases_cite_only_the_notice(self):
        bases_of = lambda kind: [r["seed"]["base"] for r in self.rows if r["stratum"] == kind]
        self.assertEqual(bases_of("drug_added"), bases_of("dose_change"))
        self.assertEqual(bases_of("drug_added"), bases_of("control_lifestyle"))
        for row in (r for r in self.rows if r["stratum"] == "unsupported_fact"):
            self.assertTrue(all(s.startswith("mohw-annex4-") for s in row["sources"]), row["calId"])
            self.assertTrue(any(row["text"].endswith(p) for p in APPENDED["unsupported_fact"]))


class ScoreTest(unittest.TestCase):

    def test_what_counts_as_correct_for_each_axis(self):
        ok = {"support": "supported", "band": "correct", "treatment": "none"}
        self.assertTrue(is_correct("band_name", {**ok, "band": "wrong"}))
        self.assertFalse(is_correct("band_name", {**ok, "support": "unsupported"}))  # 다른 축이 막아도 구간 축이 놓친 것이다
        self.assertTrue(is_correct("drug_added", {**ok, "treatment": "present"}))
        self.assertTrue(is_correct("control_lifestyle", {**ok, "support": "partial"}))  # 치료 축만 본다
        self.assertFalse(is_correct("control_lifestyle", {**ok, "treatment": "present"}))
        self.assertTrue(is_correct("citation_swap", {**ok, "support": "partial"}))
        self.assertFalse(is_correct("inversion", ok))
        self.assertTrue(is_correct("smoke", {**ok, "support": "unsupported"}))
        self.assertFalse(is_correct("smoke", ok))
        self.assertTrue(is_correct("base", ok))

    def test_score_counts_by_type_and_checks_criteria(self):
        row = lambda cal_id, kind: {"calId": cal_id, "seed": {"type": kind, "base": "cal-001"}}
        rows = [row(f"b{i}", "band_name") for i in range(10)] + [row("t1", "drug_added"), row("t2", "dose_change"),
                                                                 row("c1", "control_lifestyle"), row("c2", "control_lifestyle"),
                                                                 row("s1", "citation_swap"), row("x1", "citation_swap")]
        details = [judged(f"b{i}", band="wrong") for i in range(9)] + [judged("b9")]
        details += [judged("t1", treatment="present"), judged("t2"), judged("c1"), judged("c2", treatment="present"),
                    judged("s1", support="unsupported"), {"id": "x1", "repeats": [{"error": "stop_reason=max_tokens"}]}]
        report = score(rows, details)
        self.assertEqual((report["byType"]["band_name"]["correct"], report["byType"]["band_name"]["wrong"]), (9, ["b9"]))
        self.assertEqual(report["byType"]["citation_swap"]["notJudged"], ["x1"])  # 채점 실패는 분모에 넣지 않는다
        self.assertEqual({k: v["met"] for k, v in report["criteria"].items()},
                         {"band": True, "treatment": False, "control": True, "citation": True})

    def test_repeats_use_majority_and_ties_go_to_the_strict_label(self):
        rows = [{"calId": "s", "seed": {"type": "citation_swap", "base": "cal-001"}}]
        details = [{"id": "s", "repeats": [{"result": {"support": "supported", "band": "correct", "treatment": "none"}},
                                           {"result": {"support": "partial", "band": "correct", "treatment": "none"}}]}]
        self.assertEqual(score(rows, details)["sentences"][0]["labels"]["support"], "partial")


if __name__ == "__main__":
    unittest.main()
