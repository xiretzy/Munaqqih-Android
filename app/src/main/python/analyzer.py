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


# العتبة المعايرة لدرجة الشذوذ (التقرير B.3 وB.5)
ANOMALY_THRESHOLD = 0.708
# عتبة الإعداد الافتراضي contamination="auto" في scikit-learn (للمقارنة فقط)
DEFAULT_THRESHOLD = 0.5


def iforest_flags(df, columns):
    # يرجع مصفوفة True/False: هل الصف شاذ؟ (يشتغل على عمود أو أكثر)
    # نحوّل لـ float32 مباشرة لأن sklearn يحوّل له على كل حال، وهذا يوفر نص الذاكرة لو الأعمدة كثيرة
    values = df[columns].to_numpy(dtype=np.float32)
    if not values.flags.writeable:   # بعض نسخ pandas ترجع نسخة للقراءة فقط
        values = values.copy()
    nan_mask = np.isnan(values)
    if nan_mask.any():
        # IsolationForest ما يقبل قيم مفقودة: نعوّض كل عمود بوسيطه
        medians = np.nanmedian(values, axis=0)
        r, c = np.nonzero(nan_mask)
        values[r, c] = medians[c]
    del nan_mask
    model = IsolationForest(n_estimators=100, random_state=42, n_jobs=1)
    model.fit(values)
    # درجة الشذوذ (0..1): كل ما زادت كان الصف أسهل في العزل
    scores = -model.score_samples(values)
    # القاعدة المعتمدة: العتبة المعايرة (0.708) بدل الافتراضي contamination="auto" (عتبته 0.5)
    # الافتراضي يعلّم أطراف التوزيع الطبيعي (~12% من الصفوف)، والعتبة المعايرة تنزلها لحوالي 1%
    return scores > ANOMALY_THRESHOLD, scores > DEFAULT_THRESHOLD


def count_outliers(df, column):
    flags, _ = iforest_flags(df, [column])
    return int(flags.sum())


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


# نولّد الملف على دفعات ونكتبها بالتدريج (حجم الدفعة يصغر لما الأعمدة تكثر، حوالي 5 مليون خلية).
# السبب: لو ولّدناه كله مرة وحدة، التوليد يستهلك ذاكرة أكثر من القراءة،
# وممكن التطبيق ينهار وقت التوليد قبل ما يبدأ القياس أصلا.
BASE_COLS = 6
CELLS_PER_CHUNK = 5_000_000


def generate_file(rows, cols, folder):
    # يولد ملف CSV تجريبي: 6 أعمدة أساسية (عربية ورقمية) + أعمدة رقمية إضافية لو cols أكثر من 6.
    # قيم مفقودة 5% في بعض الأعمدة، و1% من الصفوف «شاذة» حقناها (نضرب أرقامها في 10).
    # أرقام الصفوف الشاذة تنحفظ بجنب الملف (.truth.npy) عشان نقارن فيها نتيجة Isolation Forest.
    cols = max(int(cols), BASE_COLS)
    path = os.path.join(folder, "bench_%dx%d.csv" % (rows, cols))
    rng = np.random.default_rng(42)

    extra = ["f%d" % i for i in range(1, cols - BASE_COLS + 1)]
    numeric = ["age", "salary", "score"] + extra
    chunk_rows = max(1000, min(100_000, CELLS_PER_CHUNK // cols))

    injected = []
    done = 0
    while done < rows:
        n = min(chunk_rows, rows - done)
        df = pd.DataFrame({
            "id": np.arange(done + 1, done + n + 1),
            "name": rng.choice(NAMES, n),
            "city": rng.choice(CITIES, n),
            "age": rng.integers(18, 65, n).astype(float),
            "salary": rng.normal(9000, 2500, n).round(2),
            "score": rng.uniform(0, 100, n).round(1),
        })
        if extra:
            more = pd.DataFrame(rng.normal(100, 20, (n, len(extra))).round(1), columns=extra)
            df = pd.concat([df, more], axis=1)
            del more

        # صفوف شاذة: 1% من الصفوف، كل أرقامها نضربها في 10
        idx = rng.choice(n, max(1, n // 100), replace=False)
        df.loc[idx, numeric] = df.loc[idx, numeric] * 10
        injected.append(idx + done)

        # قيم مفقودة: 5% في بعض الأعمدة
        for col in ["city", "age", "salary", "score"]:
            miss = rng.choice(n, n // 20, replace=False)
            df.loc[miss, col] = np.nan

        # أول دفعة تكتب الملف مع العناوين، والباقي تنضاف تحتها
        df.to_csv(path, index=False, encoding="utf-8",
                  mode="w" if done == 0 else "a", header=(done == 0))
        done += n
        del df

    np.save(path + ".truth.npy", np.concatenate(injected))
    gc.collect()
    return json.dumps({
        "path": path,
        "rows": rows,
        "cols": cols,
        "size_mb": os.path.getsize(path) / (1024 * 1024),
    })


def benchmark_once(path, all_numeric=False):
    # مرة وحدة: تحميل + تحليل المفقود + Isolation Forest
    # all_numeric=False: على عمود الراتب بس (اختبار الصفوف)
    # all_numeric=True: على كل الأعمدة الرقمية ما عدا id (اختبار الأعمدة)
    gc.collect()

    t0 = time.perf_counter()
    df = read_csv_file(path)
    t1 = time.perf_counter()
    missing_table(df)
    t2 = time.perf_counter()
    if all_numeric:
        columns = [c for c in numeric_columns(df) if c != "id"]
    else:
        columns = ["salary"]
    flags, flags_default = iforest_flags(df, columns)
    t3 = time.perf_counter()

    # بعد القياس (مو داخل وقت ML): نقارن اللي لقاه بالصفوف الشاذة اللي حقناها
    truth = np.load(path + ".truth.npy")
    result = {
        "load_time": t1 - t0,
        "missing_time": t2 - t1,
        "ml_time": t3 - t2,
        "rows": int(len(df)),
        "ml_columns": len(columns),
        "flagged": int(flags.sum()),
        "injected": int(len(truth)),
        "true_pos": int(flags[truth].sum()),
        # نفس النموذج بالقاعدة الافتراضية، للمقارنة في التقرير (ما يدخل في الجدول)
        "flagged_default": int(flags_default.sum()),
        "true_pos_default": int(flags_default[truth].sum()),
    }
    del df, flags, flags_default
    gc.collect()
    return json.dumps(result)


def versions():
    return json.dumps({
        "python": sys.version.split()[0],
        "pandas": pd.__version__,
        "numpy": np.__version__,
        "sklearn": sklearn.__version__,
    })
