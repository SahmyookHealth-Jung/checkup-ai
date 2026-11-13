"""후속 질문 보조 지표(문서 단위 인용) 단위 테스트"""
import unittest

from rag.run_followup_answer_eval import doc_of, grounded_doc


class GroundedDocTest(unittest.TestCase):
    def test_doc_of(self):
        self.assertEqual(doc_of("kdca-5457#개요-종류"), "kdca-5457")
        self.assertEqual(doc_of("mohw-annex4-row-egfr"), "mohw-annex4")
        self.assertEqual(doc_of("mohw-annex4-def-suspect"), "mohw-annex4")

    def test_grounded_doc(self):
        expected = {"kdca-5457#개요-종류", "mohw-annex4-row-egfr"}
        self.assertTrue(grounded_doc("ANSWER", ["kdca-5457#개요-정의"], expected))   # 같은 문서 다른 절
        self.assertTrue(grounded_doc("ANSWER", ["mohw-annex4-def-normal-b"], expected))
        self.assertFalse(grounded_doc("ANSWER", ["kdca-6765#개요"], expected))       # 다른 문서
        self.assertFalse(grounded_doc("NO_EVIDENCE", ["kdca-5457#개요-정의"], expected))
        self.assertFalse(grounded_doc("ANSWER", [], set()))


if __name__ == "__main__":
    unittest.main()
