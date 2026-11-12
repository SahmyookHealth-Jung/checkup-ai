"""기능 2 통계 데이터 적재 (ADR 0013). 모델 호출 없음.

공공데이터포털 "국민건강보험공단_건강검진정보" 2023 CSV(cp949)를 stats.checkup에 넣는다.
가입자 일련번호와 2023년에 비어 있는 세 항목(결손치·치아마모·사랑니)은 넣지 않는다.
테이블은 Flyway V4가 만든다(앱을 한 번 띄운 뒤에 실행). docker의 psql \\copy로 넣으므로 별도 DB 드라이버가 필요 없다.

사용법 (eval/ 폴더에서):
    .venv/bin/python -m stats.load_checkup ../data/stats/raw/nhis-checkup-2023.csv
"""
from __future__ import annotations

import csv
import io
import subprocess
import sys

CONTAINER = "checkup-postgres"
# CSV 머리글 → 테이블 컬럼 (순서대로)
COLUMNS = {
    "기준년도": "year", "시도코드": "region_code", "성별코드": "sex", "연령대코드(5세단위)": "age_group",
    "신장(5cm단위)": "height_cm", "체중(5kg단위)": "weight_kg", "허리둘레": "waist_cm",
    "시력(좌)": "vision_left", "시력(우)": "vision_right", "청력(좌)": "hearing_left", "청력(우)": "hearing_right",
    "수축기혈압": "systolic_bp", "이완기혈압": "diastolic_bp", "식전혈당(공복혈당)": "fasting_glucose",
    "총콜레스테롤": "total_chol", "트리글리세라이드": "triglycerides", "HDL콜레스테롤": "hdl", "LDL콜레스테롤": "ldl",
    "혈색소": "hemoglobin", "요단백": "urine_protein", "혈청크레아티닌": "creatinine",
    "혈청지오티(AST)": "ast", "혈청지피티(ALT)": "alt", "감마지티피": "ggt",
    "흡연상태": "smoking", "음주여부": "drinking", "구강검진수검여부": "oral_exam",
    "치아우식증유무": "dental_caries", "치석": "tartar",
}


def psql(*args: str, stdin: bytes | None = None) -> str:
    out = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "checkup", "-d", "checkup", "-v", "ON_ERROR_STOP=1",
                          *args], input=stdin, capture_output=True, check=True)
    return out.stdout.decode()


def main() -> None:
    src = sys.argv[1]
    buf = io.StringIO()
    writer = csv.writer(buf)
    with open(src, encoding="cp949", newline="") as f:
        reader = csv.reader(f)
        head = next(reader)
        missing = [h for h in COLUMNS if h not in head]
        if missing:
            raise SystemExit(f"CSV에 없는 머리글: {missing}")
        idx = [head.index(h) for h in COLUMNS]
        n = 0
        for row in reader:
            writer.writerow([row[i] for i in idx])
            n += 1
    cols = ", ".join(COLUMNS.values())
    psql("-c", "TRUNCATE stats.checkup")
    psql("-c", f"\\copy stats.checkup ({cols}) FROM STDIN WITH (FORMAT csv)", stdin=buf.getvalue().encode())
    psql("-c", "ANALYZE stats.checkup")
    loaded = psql("-At", "-c", "SELECT count(*) FROM stats.checkup").strip()
    print(f"CSV {n}행 → stats.checkup {loaded}행")
    if int(loaded) != n:
        raise SystemExit("행 수가 다르다")


if __name__ == "__main__":
    main()
