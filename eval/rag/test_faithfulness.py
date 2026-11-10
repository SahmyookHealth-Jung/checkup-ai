"""채점기·일치도 단위 테스트 (모델 호출 없음).  실행 (eval/ 폴더에서): .venv/bin/python -m unittest discover -s rag -t ."""
import unittest

from rag.agreement import binary, cohen_kappa, majority, self_consistency
from rag.calibration_agreement import compare
from rag.faithfulness_judge import cost_usd, finalize, system_prompt, user_message, verify_quotes
from rag.faithfulness_rubric import passes

SOURCES = [{"id": "row-glucose", "text": "공복 혈당 / 정상A: 100 미만 / 정상B(경계): 100-125 /  질환의심: 126 이상"},
           {"id": "def-b", "text": "정상B(경계): 건강에 이상이 없으나 자가관리 및 예방조치가 필요한 자"}]


def raw(label="supported", quotes=None, band="correct", treatment="none"):
    return {"support": {"label": label, "quotes": quotes if quotes is not None else [], "unsupportedClaims": [], "reason": "r"},
            "band": {"label": band, "reason": "b"}, "treatment": {"label": treatment, "quote": ""}}


class QuoteCheckTest(unittest.TestCase):

    def test_exact_quote_is_found_and_whitespace_differences_are_allowed(self):
        checked = verify_quotes([{"source": 1, "quote": "질환의심: 126 이상"}, {"source": 2, "quote": "자가관리 및  예방조치"}],
                                SOURCES)
        self.assertEqual([q["found"] for q in checked], [True, True])

    def test_paraphrase_wrong_source_and_out_of_range_index_are_not_found(self):
        checked = verify_quotes([{"source": 1, "quote": "126 이상이면 질환의심"}, {"source": 2, "quote": "126 이상"},
                                 {"source": 3, "quote": "126"}], SOURCES)
        self.assertEqual([q["found"] for q in checked], [False, False, False])

    def test_supported_without_verified_quote_becomes_unsupported(self):
        result = finalize(raw("supported", [{"source": 1, "quote": "지어낸 구절"}]), SOURCES)
        self.assertEqual((result["support"], result["supportRaw"], result["quoteVerified"]),
                         ("unsupported", "supported", False))
        self.assertEqual(finalize(raw("partial", []), SOURCES)["support"], "unsupported")

    def test_verified_label_is_kept_and_unsupported_needs_no_quote(self):
        self.assertEqual(finalize(raw("partial", [{"source": 2, "quote": "예방조치가 필요한 자"}]), SOURCES)["support"],
                         "partial")
        self.assertTrue(finalize(raw("unsupported", []), SOURCES)["quoteVerified"])

    def test_pass_rule(self):
        ok = finalize(raw("supported", [{"source": 1, "quote": "126 이상"}]), SOURCES)
        self.assertTrue(passes(ok))
        self.assertFalse(passes({**ok, "band": "wrong"}))
        self.assertFalse(passes({**ok, "treatment": "present"}))
        self.assertFalse(passes({**ok, "support": "partial"}))


class PromptAndCostTest(unittest.TestCase):

    def test_system_prompt_is_stable_and_contains_every_label(self):
        self.assertEqual(system_prompt(), system_prompt())  # 캐시가 맞으려면 글자가 같아야 한다
        for label in ("supported", "partial", "unsupported", "correct", "wrong", "not_stated", "none", "present"):
            self.assertIn(label, system_prompt())

    def test_user_message_numbers_sources_in_order(self):
        item = {"name": "공복혈당", "value": "131", "unit": "mg/dL", "judgmentLabel": "질환의심", "criterion": "126 이상"}
        message = user_message(item, "문장", SOURCES)
        self.assertLess(message.index("[근거 1]"), message.index("[근거 2]"))
        self.assertIn("131 mg/dL", message)

    def test_cost_uses_cache_prices(self):
        usage = {"input_tokens": 1_000_000, "output_tokens": 100_000, "cache_creation_input_tokens": 0,
                 "cache_read_input_tokens": 1_000_000}
        self.assertAlmostEqual(cost_usd(usage), 2.0 + 1.0 + 0.10)


class AgreementTest(unittest.TestCase):

    def test_kappa(self):
        self.assertEqual(cohen_kappa(list("aabb"), list("aabb")), 1.0)
        self.assertEqual(cohen_kappa(list("aabb"), list("bbaa")), -1.0)
        self.assertEqual(cohen_kappa(list("aaab"), list("aaba")), -0.333)
        self.assertIsNone(cohen_kappa(list("aaaa"), list("aaaa")))  # 한 라벨만 쓰면 정의되지 않는다

    def test_majority_breaks_ties_toward_strict_label(self):
        self.assertEqual(majority(["supported", "supported", "partial"], "support"), "supported")
        self.assertEqual(majority(["supported", "partial", "unsupported"], "support"), "unsupported")
        self.assertEqual(majority(["none", "present"], "treatment"), "present")

    def test_self_consistency_and_binary(self):
        self.assertEqual(self_consistency([["a", "a", "a"], ["a", "b", "a"], ["a"]]), 0.5)
        self.assertEqual(binary(["supported", "partial", "unsupported"]), ["pass", "fail", "fail"])

    def test_compare_uses_majority_and_lists_disagreements(self):
        def rep(support):
            return {"result": {"support": support, "band": "correct", "treatment": "none", "reason": {"support": "x"}}}
        judge = {"details": [{"id": "cal-001", "stratum": "rest", "repeats": [rep("supported"), rep("supported"), rep("partial")]},
                             {"id": "cal-002", "stratum": "rest", "repeats": [rep("unsupported")] * 3}]}
        labels = {"cal-001": {"support": "supported", "band": "correct", "treatment": "none"},
                  "cal-002": {"support": "partial", "band": "correct", "treatment": "none", "memo": "애매"},
                  "cal-003": {"support": "supported"}}  # 미완료 라벨은 비교하지 않는다
        report = compare(labels, judge)
        self.assertEqual(report["compared"], 2)
        self.assertEqual(report["axes"]["support"]["agreement"], 0.5)
        self.assertEqual([d["id"] for d in report["disagreements"]], ["cal-002"])
        self.assertEqual(report["disagreements"][0]["memo"], "애매")


if __name__ == "__main__":
    unittest.main()
