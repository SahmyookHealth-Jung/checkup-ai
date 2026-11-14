"""후속 질문 단계별 실패 분류 단위 테스트 (작업 지시 1번)"""
import unittest

from rag.run_followup_answer_eval import failure_stage, refuse_layers

EXPECTED = {"kdca-5457#개요-종류"}


def row(decision="ANSWER", llm="ANSWER", context=("kdca-5457#개요-종류",), grounded=False, category="answerable",
        rule=False, intent=False, blocked=False):
    return {"category": category, "decision": decision, "llmDecision": llm, "contextUnitIds": list(context),
            "grounded": grounded, "ruleDiagnosis": rule, "intentDiagnosis": intent, "answerBlocked": blocked}


class FailureStageTest(unittest.TestCase):
    def test_stages(self):
        cases = [
            (row(decision="REFUSE_DIAGNOSIS", intent=True), "거절 층"),
            (row(decision="NO_EVIDENCE", llm=None), "LLM 오류"),
            (row(context=("kdca-6765#개요",), grounded=False), "검색"),
            (row(decision="NO_EVIDENCE", llm="ANSWER", context=()), "검색"),       # 문맥에 없으면 지워진 것보다 검색이 먼저
            (row(decision="NO_EVIDENCE", llm="NO_EVIDENCE"), "생성: 보류"),
            (row(decision="NO_EVIDENCE", llm="ANSWER"), "인용 검증"),
            (row(grounded=False), "생성: 다른 절"),
            (row(grounded=True), "통과"),
        ]
        for r, stage in cases:
            with self.subTest(stage=stage, row=r):
                self.assertEqual(failure_stage(r, EXPECTED), stage)

    def test_not_answerable_has_no_stage(self):
        self.assertIsNone(failure_stage(row(category="refuse", decision="ANSWER"), EXPECTED))

    def test_refuse_layers(self):
        self.assertEqual(refuse_layers(row(decision="REFUSE_DIAGNOSIS", rule=True, intent=True)), ["rule", "intent"])
        self.assertEqual(refuse_layers(row(decision="REFUSE_DIAGNOSIS", intent=None)), ["intent"])  # 분류 실패
        self.assertEqual(refuse_layers(row(decision="REFUSE_DIAGNOSIS", blocked=True)), ["answerCheck"])
        self.assertEqual(refuse_layers(row(decision="REFUSE_DIAGNOSIS", llm="REFUSE_DIAGNOSIS")), ["llm"])
        self.assertEqual(refuse_layers(row(decision="REFUSE_TREATMENT", llm="REFUSE_TREATMENT")), ["llm"])
        self.assertEqual(refuse_layers(row()), [])


if __name__ == "__main__":
    unittest.main()
