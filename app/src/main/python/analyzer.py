# analyzer.py
# كود Python اللي يشتغل داخل التطبيق عن طريق Chaquopy
# كل دالة ترجع نص JSON عشان Kotlin يقراه بسهولة

import gc
import json
import os
import sys
import time

import numpy as np
import pandas as pd
import sklearn
from sklearn.ensemble import IsolationForest

# الملف المفتوح حاليا في الشاشة
current_df = None


def read_csv_file(path):
    # نجرب UTF-8 أول، واذا فشل نجرب ترميز ويندوز العربي
    try:
        return pd.read_csv(path, encoding="utf-8-sig")
    except UnicodeDecodeError:
        return pd.read_csv(path, encoding="cp1256")


def numeric_columns(df):
    return [c for c in df.columns if pd.api.types.is_numeric_dtype(df[c])]


def missing_table(df):
    result = []
    percents = df.isna().mean() * 100
    for col in df.columns:
        result.append({"column": str(col), "percent": round(float(percents[col]), 2)})
    return result


def count_outliers(df, column):
    values = df[[column]].astype(float)
    values = values.fillna(values[column].median())  # IsolationForest ما يقبل قيم مفقودة
    model = IsolationForest(n_estimators=100, random_state=42, n_jobs=1)
    labels = model.fit_predict(values)  # -1 = شاذ ، 1 = طبيعي
    return int((labels == -1).sum())


# ---------------- الشاشة الرئيسية ----------------

def load_file(path):
    global current_df
    current_df = None
    gc.collect()

    t0 = time.perf_counter()
    current_df = read_csv_file(path)
    t1 = time.perf_counter()

    return json.dumps({
        "rows": int(len(current_df)),
        "cols": int(len(current_df.columns)),
        "size_mb": os.path.getsize(path) / (1024 * 1024),
        "load_time": t1 - t0,
        "numeric_columns": [str(c) for c in numeric_columns(current_df)],
    }, ensure_ascii=False)


def analyze_missing():
    t0 = time.perf_counter()
    table = missing_table(current_df)
    t1 = time.perf_counter()
    return json.dumps({"table": table, "time": t1 - t0}, ensure_ascii=False)


def run_isolation_forest(column):
    t0 = time.perf_counter()
    outliers = count_outliers(current_df, column)
    t1 = time.perf_counter()
    return json.dumps({
        "column": column,
        "outliers": outliers,
        "rows": int(len(current_df)),
        "time": t1 - t0,
    }, ensure_ascii=False)


# ---------------- قياس الأداء ----------------

CITIES = ["الرياض", "جدة", "مكة المكرمة", "المدينة المنورة", "الدمام", "أبها", "تبوك", "حائل"]
NAMES = ["محمد", "أحمد", "عبدالله", "فهد", "سارة", "نورة", "خالد", "ريم", "فيصل", "هند"]


def generate_file(rows, folder):
    # يولد ملف CSV تجريبي فيه أعمدة عربية ورقمية وقيم مفقودة (5%) وقيم شاذة قليلة
    path = os.path.join(folder, "bench_%d.csv" % rows)
    rng = np.random.default_rng(42)

    df = pd.DataFrame({
        "id": np.arange(1, rows + 1),
        "name": rng.choice(NAMES, rows),
        "city": rng.choice(CITIES, rows),
        "age": rng.integers(18, 65, rows).astype(float),
        "salary": rng.normal(9000, 2500, rows).round(2),
        "score": rng.uniform(0, 100, rows).round(1),
    })

    # قيم شاذة: 1% من الرواتب نضربها في 10
    idx = rng.choice(rows, max(1, rows // 100), replace=False)
    df.loc[idx, "salary"] = df.loc[idx, "salary"] * 10

    # قيم مفقودة: 5% في بعض الأعمدة
    for col in ["city", "age", "salary", "score"]:
        miss = rng.choice(rows, rows // 20, replace=False)
        df.loc[miss, col] = np.nan

    df.to_csv(path, index=False, encoding="utf-8")
    return json.dumps({
        "path": path,
        "rows": rows,
        "size_mb": os.path.getsize(path) / (1024 * 1024),
    })


def benchmark_once(path):
    # مرة وحدة: تحميل + تحليل المفقود + Isolation Forest على عمود الراتب
    gc.collect()

    t0 = time.perf_counter()
    df = read_csv_file(path)
    t1 = time.perf_counter()
    missing_table(df)
    t2 = time.perf_counter()
    count_outliers(df, "salary")
    t3 = time.perf_counter()

    del df
    gc.collect()
    return json.dumps({
        "load_time": t1 - t0,
        "missing_time": t2 - t1,
        "ml_time": t3 - t2,
    })


def versions():
    return json.dumps({
        "python": sys.version.split()[0],
        "pandas": pd.__version__,
        "numpy": np.__version__,
        "sklearn": sklearn.__version__,
    })
