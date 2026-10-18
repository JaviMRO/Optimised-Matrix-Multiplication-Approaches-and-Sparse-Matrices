"""Tables and figures for Assignment 2 from the raw results in results/raw.

1. Converts every raw file (JMH JSON, pilot/timeout CSVs, memory CSVs) into one normalized CSV,
   results/tables/normalized_all.csv, without dropping any row.
2. For each host separately (results of different machines are never mixed), writes LaTeX (booktabs)
   and CSV tables to results/tables/<host>/ and PDF/PNG figures to results/figures/<host>/.

If a configuration was measured in several runs, the analysis uses the most recent run of that
configuration (all runs stay in normalized_all.csv).

Usage (from the repository root):  python scripts/analysis/make_tables_and_plots.py
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]

COLUMNS = ["host", "env_id", "part", "method", "n", "density", "nnz_A", "nnz_B", "nnz_C",
           "block_size", "cutoff", "seed", "repetition", "time_s", "status", "metric", "value",
           "unit", "matrix", "note"]
EXTRA = ["kind", "mode", "source", "run_ts"]
KEY = ["part", "method", "n", "density", "matrix", "block_size", "cutoff", "seed"]

# Fixed colour and marker per method (colour follows the entity, never its rank; the marker
# shape is a second encoding so that identity never depends on colour alone).
STYLE = {
    "naive":         ("#2a78d6", "o"),
    "ikj":           ("#eb6834", "s"),
    "blocked":       ("#1baf7a", "^"),
    "strassen":      ("#eda100", "D"),
    "ejml":          ("#e87ba4", "v"),
    "convert":       ("#008300", "P"),
    "ejml+convert":  ("#6250d6", "X"),
}
LABEL = {
    "naive": "Naive i-j-k", "ikj": "Loop i-k-j", "blocked": "Blocked", "strassen": "Strassen",
    "ejml": "EJML sparse (kernel)", "convert": "Dense to CSC conversion",
    "ejml+convert": "EJML sparse + conversion",
}
DENSE_METHODS = ["naive", "ikj", "blocked", "strassen"]
TIME_FACTOR = {"ns": 1e-9, "us": 1e-6, "µs": 1e-6, "ms": 1e-3, "s": 1.0, "min": 60.0}

plt.rcParams.update({
    "figure.dpi": 120, "savefig.dpi": 200, "font.size": 9, "axes.titlesize": 10,
    "axes.grid": True, "grid.color": "#e2e1dc", "grid.linewidth": 0.6,
    "axes.edgecolor": "#8a8984", "axes.labelcolor": "#0b0b0b", "xtick.color": "#52514e",
    "ytick.color": "#52514e", "lines.linewidth": 2, "lines.markersize": 5, "legend.frameon": False,
})


# ----------------------------------------------------------------------------- loading

def run_ts(name: str) -> str:
    m = re.search(r"(\d{8}_\d{6})", name)
    return m.group(1) if m else ""


def load_jmh(path: Path) -> list[dict]:
    m = re.match(r"^(?P<host>.+)_(?P<part>dense|block_sweep|cutoff_sweep|sparse)_(?P<ts>\d{8}_\d{6})\.json$",
                 path.name)
    if not m:
        return []
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        return []
    rows = []
    for entry in json.loads(text):
        props = {}
        for arg in entry.get("jvmArgs", []):
            if arg.startswith("-D") and "=" in arg:
                k, v = arg[2:].split("=", 1)
                props[k] = v
        p = entry.get("params", {})
        bench = entry["benchmark"]
        if "DenseBenchmark" in bench:
            method = p.get("method")
        elif "SparseBenchmark" in bench:
            method = "ejml"
        else:
            method = "convert"
        base = {
            "host": props.get("a2.host", m["host"]), "env_id": props.get("a2.envId", ""),
            "part": m["part"], "method": method, "n": int(p.get("n", 0)),
            "density": float(p.get("density", 1.0)), "matrix": p.get("matrix", "none"),
            "block_size": int(p["blockSize"]) if method == "blocked" else np.nan,
            "cutoff": int(p["cutoff"]) if method == "strassen" else np.nan,
            "seed": int(p.get("seed", 42)), "status": "OK", "note": "",
            "kind": "jmh", "mode": entry.get("mode", ""), "source": path.name, "run_ts": m["ts"],
        }
        pm = entry["primaryMetric"]
        factor = TIME_FACTOR[pm["scoreUnit"].split("/")[0]]
        times = [t * factor for fork in pm["rawData"] for t in fork]
        for i, t in enumerate(times):
            rows.append({**base, "repetition": i, "time_s": t, "metric": "time", "value": t, "unit": "s"})
        alloc = entry.get("secondaryMetrics", {}).get("gc.alloc.rate.norm")
        if alloc:
            values = [v for fork in alloc["rawData"] for v in fork]
            for i, v in enumerate(values):
                rows.append({**base, "repetition": i, "time_s": times[i] if i < len(times) else np.nan,
                             "metric": "gc.alloc.rate.norm", "value": v, "unit": "B/op"})
    return rows


def load_csv(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path, keep_default_na=True)
    if df.empty:
        return df
    name = path.name
    kind = "memory" if name.startswith("memory_") else "pilot" if "_pilot_" in name else "limits"
    df["kind"] = kind
    df["mode"] = ""
    df["source"] = name
    df["run_ts"] = run_ts(name)
    return df


def load_all(raw: Path) -> pd.DataFrame:
    frames = []
    jmh_rows = []
    for f in sorted(raw.glob("*.json")):
        jmh_rows += load_jmh(f)
    if jmh_rows:
        frames.append(pd.DataFrame(jmh_rows))
    for f in sorted(raw.glob("*.csv")):
        d = load_csv(f)
        if not d.empty:
            frames.append(d)
    if not frames:
        return pd.DataFrame(columns=COLUMNS + EXTRA)
    df = pd.concat(frames, ignore_index=True)
    for c in COLUMNS + EXTRA:
        if c not in df:
            df[c] = np.nan
    df["matrix"] = df["matrix"].fillna("none")
    df["note"] = df["note"].fillna("")
    for c in ["n", "seed"]:
        df[c] = pd.to_numeric(df[c], errors="coerce")
    for c in ["density", "nnz_A", "nnz_B", "nnz_C", "block_size", "cutoff", "repetition", "time_s", "value"]:
        df[c] = pd.to_numeric(df[c], errors="coerce")
    return df[COLUMNS + EXTRA]


def fill_nnz(df: pd.DataFrame) -> pd.DataFrame:
    """JMH rows carry no nnz: take them from the pilot rows of the same configuration."""
    pilot = latest(df[(df.kind == "pilot") & (df.status == "OK")])
    if pilot.empty:
        return df
    k = pilot[["host"] + KEY + ["nnz_A", "nnz_B", "nnz_C"]].copy()
    k = k.astype({"block_size": "float", "cutoff": "float"}).drop_duplicates(["host"] + KEY)
    out = df.astype({"block_size": "float", "cutoff": "float"}).merge(
        k, on=["host"] + KEY, how="left", suffixes=("", "_pilot"))
    for c in ["nnz_A", "nnz_B", "nnz_C"]:
        out[c] = out[c].fillna(out[c + "_pilot"])
        out = out.drop(columns=c + "_pilot")
    return out


def latest(df: pd.DataFrame) -> pd.DataFrame:
    """Rows of the most recent run of every (kind, configuration)."""
    if df.empty:
        return df
    keys = ["host", "kind"] + KEY
    d = df.copy()
    d[keys] = d[keys].fillna(-1)
    newest = d.groupby(keys, dropna=False)["run_ts"].transform("max")
    return df[(d["run_ts"] == newest).values]


# ----------------------------------------------------------------------------- tables

def summarise(df: pd.DataFrame, by: list[str], metric: str = "time") -> pd.DataFrame:
    d = df[(df.metric == metric) & (df.status == "OK")]
    if d.empty:
        return pd.DataFrame()
    g = d.groupby(by, dropna=False)["value"]
    s = pd.DataFrame({
        "median": g.median(), "q1": g.quantile(0.25), "q3": g.quantile(0.75),
        "count": g.count(), "min": g.min(), "max": g.max(),
    }).reset_index()
    s["iqr"] = s["q3"] - s["q1"]
    return s


def fmt(v: float, digits: int = 3) -> str:
    if v is None or (isinstance(v, float) and np.isnan(v)):
        return "--"
    return f"{v:.{digits}g}"


def tex_escape(s: str) -> str:
    return str(s).replace("_", r"\_").replace("%", r"\%").replace("&", r"\&")


def write_table(df: pd.DataFrame, out: Path, caption: str, label: str) -> None:
    """Writes df as CSV and as a booktabs LaTeX table (cells are written as they are)."""
    out.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(out.with_suffix(".csv"), index=False)
    cols = list(df.columns)
    lines = [
        r"\begin{table}[htbp]", r"\centering", r"\small",
        r"\caption{" + caption + "}", r"\label{" + label + "}",
        r"\begin{tabular}{" + "l" * 1 + "r" * (len(cols) - 1) + "}", r"\toprule",
        " & ".join(tex_escape(c) for c in cols) + r" \\", r"\midrule",
    ]
    for _, row in df.iterrows():
        lines.append(" & ".join(tex_escape(v) for v in row.values) + r" \\")
    lines += [r"\bottomrule", r"\end{tabular}", r"\end{table}", ""]
    out.with_suffix(".tex").write_text("\n".join(lines), encoding="utf-8")


def status_cell(pilot: pd.DataFrame, filt: dict) -> str | None:
    """'TIMEOUT' / 'OOM' / 'ERROR' if the configuration failed or was not run, else None."""
    d = pilot
    for k, v in filt.items():
        d = d[d[k] == v]
    if d.empty:
        return None
    st = d.iloc[-1]["status"]
    if st == "OK":
        return None
    return str(st)


def budget_note(pilot: pd.DataFrame) -> str:
    """Caption text explaining TIMEOUT/OOM cells, with the time budget read from the pilot notes."""
    budgets = sorted({m for m in pilot.note.astype(str).str.extract(r"exceeded ([0-9.]+) s")[0].dropna()})
    budget = "/".join(f"{float(b):g}" for b in budgets) or "180"
    return (f"TIMEOUT: one run exceeded the {budget} s budget; OOM: the matrices do not fit the heap. "
            "Larger sizes of a failed method are not run, and no time is reported for them.")


# ----------------------------------------------------------------------------- figures

def save(fig, out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out.with_suffix(".pdf"), bbox_inches="tight")
    fig.savefig(out.with_suffix(".png"), bbox_inches="tight")
    plt.close(fig)


def plot_series(ax, x, y, lo, hi, method, label=None):
    color, marker = STYLE.get(method, ("#52514e", "o"))
    yerr = np.vstack([np.asarray(y) - np.asarray(lo), np.asarray(hi) - np.asarray(y)])
    ax.errorbar(x, y, yerr=yerr, color=color, marker=marker, markersize=5, capsize=2,
                linewidth=2, label=label or LABEL.get(method, method),
                markeredgecolor="white", markeredgewidth=0.8)


def reference_line(ax, xs, x0, y0, exponent, text, dashes):
    xs = np.asarray(sorted(xs), dtype=float)
    ax.plot(xs, y0 * (xs / x0) ** exponent, color="#8a8984", linewidth=1, dashes=dashes, label=text)


# ----------------------------------------------------------------------------- Part A

def part_a(df: pd.DataFrame, tables: Path, figs: Path, host: str) -> None:
    jmh = df[(df.kind == "jmh") & (df.part == "dense")]
    pilot = df[(df.kind == "pilot") & (df.part == "dense")]
    if jmh.empty and pilot.empty:
        return
    s = summarise(jmh, ["method", "n"])
    sizes = sorted(set(pilot.n.dropna().astype(int)) | set(s.n.astype(int) if not s.empty else []))
    methods = [m for m in DENSE_METHODS if m in set(pilot.method) | set(s.method if not s.empty else [])]

    # Table: median (IQR) per method and n, failures marked.
    rows = []
    for n in sizes:
        row = {"n": n}
        for m in methods:
            hit = s[(s.method == m) & (s.n == n)] if not s.empty else s
            if not hit.empty:
                r = hit.iloc[0]
                row[LABEL[m] + " [s]"] = f"{fmt(r['median'])} ({fmt(r['iqr'], 2)})"
            else:
                row[LABEL[m] + " [s]"] = status_cell(pilot, {"method": m, "n": n}) or "--"
        rows.append(row)
    count = int(s["count"].max()) if not s.empty else 0
    write_table(pd.DataFrame(rows), tables / "dense_time",
                f"Dense multiplication time per method on {tex_escape(host)}: median (IQR) over {count} JMH "
                "iterations, in seconds. " + budget_note(pilot),
                "tab:dense-time")
    if s.empty:
        return

    # Figure: log-log time vs n with n^3 and n^2.81 references.
    fig, ax = plt.subplots(figsize=(6.0, 4.0))
    for m in methods:
        d = s[s.method == m].sort_values("n")
        if not d.empty:
            plot_series(ax, d.n, d["median"], d.q1, d.q3, m)
    naive = s[s.method == "naive"].sort_values("n")
    if not naive.empty:
        reference_line(ax, sizes, naive.n.iloc[-1], naive["median"].iloc[-1], 3, r"$\propto n^3$ (through last naive point)", (5, 3))
    st = s[s.method == "strassen"].sort_values("n")
    if not st.empty:
        reference_line(ax, sizes, st.n.iloc[-1], st["median"].iloc[-1], np.log2(7), r"$\propto n^{2.81}$ (through last Strassen point)", (1, 2))
    ax.set_xscale("log", base=2)
    ax.set_yscale("log")
    ax.set_xticks(sizes)
    ax.set_xticklabels([str(n) for n in sizes], rotation=45)
    ax.set_xlabel("Matrix size n")
    ax.set_ylabel("Time per multiplication [s]")
    ax.set_title(f"Dense multiplication time vs n ({host}); bars = IQR")
    fails = []
    for m in methods:
        bad = [n for n in sizes if status_cell(pilot, {"method": m, "n": n})]
        if bad:
            fails.append(f"{LABEL[m]}: {status_cell(pilot, {'method': m, 'n': bad[0]})} from n={bad[0]}")
    if fails:
        ax.text(0.0, -0.22, "Not measured:" + chr(10) + chr(10).join(fails), transform=ax.transAxes, fontsize=7,
                color="#52514e", va="top")
    ax.legend(loc="upper left")
    save(fig, figs / "dense_time_vs_n")

    # Speedup vs naive.
    base = s[s.method == "naive"].set_index("n")["median"]
    sp_rows = []
    fig, ax = plt.subplots(figsize=(6.0, 3.6))
    for m in methods:
        if m == "naive":
            continue
        d = s[s.method == m].set_index("n")["median"]
        common = sorted(set(d.index) & set(base.index))
        if not common:
            continue
        sp = base.loc[common] / d.loc[common]
        color, marker = STYLE[m]
        ax.plot(common, sp.values, color=color, marker=marker, label=LABEL[m],
                markeredgecolor="white", markeredgewidth=0.8)
        for n in common:
            sp_rows.append({"method": LABEL[m], "n": n, "speedup vs naive": fmt(sp.loc[n])})
    ax.axhline(1.0, color="#8a8984", linewidth=1, linestyle=":")
    ax.set_xscale("log", base=2)
    ax.set_xticks(sizes)
    ax.set_xticklabels([str(n) for n in sizes], rotation=45)
    ax.set_xlabel("Matrix size n")
    ax.set_ylabel("Speedup over naive [x]")
    ax.set_title(f"Speedup of each optimisation over naive i-j-k ({host})")
    ax.legend(loc="upper left")
    save(fig, figs / "dense_speedup")
    if sp_rows:
        sp_df = pd.DataFrame(sp_rows).pivot(index="n", columns="method", values="speedup vs naive").reset_index()
        write_table(sp_df, tables / "dense_speedup",
                    f"Speedup over the naive method (ratio of medians) on {tex_escape(host)}.", "tab:dense-speedup")

    # Bytes allocated per operation.
    a = summarise(jmh, ["method", "n"], metric="gc.alloc.rate.norm")
    if not a.empty:
        t = a.pivot(index="n", columns="method", values="median").reset_index()
        t = t.rename(columns={m: LABEL[m] + " [B/op]" for m in DENSE_METHODS})
        write_table(t.map(lambda v: fmt(v) if isinstance(v, float) else v), tables / "dense_alloc",
                    f"Bytes allocated per multiplication (JMH gc.alloc.rate.norm, median) on {tex_escape(host)}.",
                    "tab:dense-alloc")


def sweep(df: pd.DataFrame, part: str, param: str, method: str, tables: Path, figs: Path, host: str) -> None:
    jmh = df[(df.kind == "jmh") & (df.part == part)]
    s = summarise(jmh, [param, "n"])
    if s.empty:
        return
    name = "Block size" if param == "block_size" else "Strassen cutoff"
    t = s.copy()
    t[param] = t[param].astype(int)
    t = t[["n", param, "median", "q1", "q3", "iqr", "count"]].sort_values(["n", param])
    write_table(t.assign(**{c: t[c].map(fmt) for c in ["median", "q1", "q3", "iqr"]}),
                tables / f"{part}",
                f"{name} sweep for {LABEL[method]} on {tex_escape(host)}: time per multiplication in seconds.",
                f"tab:{part.replace('_', '-')}")
    fig, ax = plt.subplots(figsize=(4.8, 3.2))
    for n, d in s.groupby("n"):
        d = d.sort_values(param)
        plot_series(ax, d[param], d["median"], d.q1, d.q3, method, label=f"{LABEL[method]}, n = {int(n)}")
        best = d.loc[d["median"].idxmin()]
        ax.annotate(f"best: {int(best[param])}", (best[param], best["median"]), textcoords="offset points",
                    xytext=(0, 8), ha="center", fontsize=8, color="#0b0b0b")
    ax.set_xscale("log", base=2)
    ax.set_xticks(sorted(s[param].unique()))
    ax.set_xticklabels([str(int(v)) for v in sorted(s[param].unique())])
    ax.set_xlabel(name)
    ax.set_ylabel("Time per multiplication [s]")
    ax.set_title(f"{name} sweep ({host}); bars = IQR")
    ax.legend(loc="best")
    save(fig, figs / part)


# ----------------------------------------------------------------------------- Part B

def crossover(dens: np.ndarray, t_sparse: np.ndarray, t_dense: np.ndarray) -> str:
    """Density where the sparse and dense curves cross (log-log interpolation), if inside the range."""
    diff = np.log(t_sparse) - np.log(t_dense)
    for i in range(len(dens) - 1):
        if diff[i] == 0:
            return f"{dens[i]:.3g}"
        if diff[i] * diff[i + 1] < 0:
            x0, x1 = np.log(dens[i]), np.log(dens[i + 1])
            x = x0 + (x1 - x0) * (-diff[i]) / (diff[i + 1] - diff[i])
            return f"{np.exp(x):.3g}"
    if (diff < 0).all():
        return "sparse faster at all tested densities"
    if (diff > 0).all():
        return "dense faster at all tested densities"
    return "--"


def part_b(df: pd.DataFrame, tables: Path, figs: Path, host: str) -> None:
    jmh = df[(df.kind == "jmh") & (df.part == "sparse")]
    pilot = df[(df.kind == "pilot") & (df.part == "sparse")]
    if jmh.empty and pilot.empty:
        return
    s = summarise(jmh, ["method", "n", "density", "matrix"])
    syn = s[s.matrix == "none"] if not s.empty else s
    dense_present = [m for m in DENSE_METHODS if not s.empty and m in set(s.method)]
    best_dense = [m for m in dense_present if m != "naive"]

    # Summary table for synthetic inputs.
    if not syn.empty:
        nnz = pilot[(pilot.method == "ejml") & (pilot.status == "OK")][["n", "density", "nnz_A", "nnz_B", "nnz_C"]]
        rows = []
        for (n, d), g in syn.groupby(["n", "density"]):
            row = {"n": int(n), "density": f"{d:g}"}
            k = nnz[(nnz.n == n) & (nnz.density == d)]
            if not k.empty:
                row["nnz(A)"] = int(k.nnz_A.iloc[0])
                row["nnz(C)"] = int(k.nnz_C.iloc[0])
                row["density(C)"] = fmt(k.nnz_C.iloc[0] / n ** 2)
            for m in ["ejml", "convert"] + dense_present:
                hit = g[g.method == m]
                if not hit.empty:
                    row[LABEL[m] + " [s]"] = f"{fmt(hit['median'].iloc[0])} ({fmt(hit['iqr'].iloc[0], 2)})"
                else:
                    row[LABEL[m] + " [s]"] = status_cell(pilot, {"method": m, "n": n, "density": d}) or "--"
            rows.append(row)
        write_table(pd.DataFrame(rows), tables / "sparse_time",
                    f"Sparse vs dense on {tex_escape(host)}: median (IQR) time per operation in seconds. "
                    "EJML: kernel only; conversion: both operands, dense to CSC. " + budget_note(pilot), "tab:sparse-time")

    # Time vs density, one panel per n, with crossover points.
    cross_rows = []
    ns = sorted(syn.n.unique()) if not syn.empty else []
    if ns:
        cols = min(2, len(ns))
        nrows = int(np.ceil(len(ns) / cols))
        fig, axes = plt.subplots(nrows, cols, figsize=(6.4, 2.9 * nrows), squeeze=False)
        for ax, n in zip(axes.flat, ns):
            g = syn[syn.n == n]
            for m in ["ejml", "convert"] + dense_present:
                d = g[g.method == m].sort_values("density")
                if not d.empty:
                    plot_series(ax, d.density, d["median"], d.q1, d.q3, m)
            e = g[g.method == "ejml"].set_index("density")["median"]
            c = g[g.method == "convert"].set_index("density")["median"]
            both = sorted(set(e.index) & set(c.index))
            if both:
                tot = e.loc[both] + c.loc[both]
                color, marker = STYLE["ejml+convert"]
                ax.plot(both, tot.values, color=color, marker=marker, linestyle="-.", linewidth=1.5,
                        label=LABEL["ejml+convert"], markeredgecolor="white", markeredgewidth=0.8)
            for m in best_dense + (["naive"] if "naive" in dense_present else []):
                dd = g[g.method == m].set_index("density")["median"]
                for sparse_name, sp in [("kernel", e), ("kernel + conversion", e.add(c, fill_value=np.nan))]:
                    common = sorted(set(sp.dropna().index) & set(dd.index))
                    if len(common) >= 1:
                        x = crossover(np.array(common), sp.loc[common].values, dd.loc[common].values)
                        cross_rows.append({"n": int(n), "dense method": LABEL[m], "sparse cost": sparse_name,
                                           "crossover density": x})
                        try:
                            xv = float(x)
                            if sparse_name == "kernel" and m in best_dense:
                                ax.axvline(xv, color="#52514e", linestyle=":", linewidth=1)
                                ax.annotate(f"crossover\nd = {x}", (xv, ax.get_ylim()[0]), xytext=(4, 4),
                                            textcoords="offset points", fontsize=7, color="#0b0b0b")
                        except ValueError:
                            pass
            ax.set_xscale("log")
            ax.set_yscale("log")
            ax.set_title(f"n = {int(n)}")
            ax.set_xlabel("Input density (fraction of non-zeros)")
            ax.set_ylabel("Time per operation [s]")
        for ax in list(axes.flat)[len(ns):]:
            ax.set_visible(False)
        handles, labels = axes.flat[0].get_legend_handles_labels()
        fig.legend(handles, labels, loc="upper center", ncol=3, bbox_to_anchor=(0.5, 0.0))
        fig.suptitle(f"Sparse (EJML, CSC) vs dense multiplication ({host}); bars = IQR")
        fig.tight_layout()
        save(fig, figs / "sparse_vs_dense_time")
    if cross_rows:
        write_table(pd.DataFrame(cross_rows), tables / "sparse_crossover",
                    f"Density at which sparse and dense times cross on {tex_escape(host)} "
                    "(log-log interpolation between tested densities).", "tab:sparse-crossover")

    # Output density vs input density.
    k = pilot[(pilot.method == "ejml") & (pilot.status == "OK") & (pilot.matrix == "none")]
    if not k.empty:
        fig, ax = plt.subplots(figsize=(4.8, 3.4))
        cats = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300"]
        marks = ["o", "s", "^", "D", "v", "P"]
        dgrid = np.logspace(np.log10(k.density.min()), np.log10(k.density.max()), 100)
        for i, (n, g) in enumerate(sorted(k.groupby("n"), key=lambda t: t[0])):
            g = g.sort_values("density")
            col, mk = cats[i % len(cats)], marks[i % len(marks)]
            ax.plot(g.density, g.nnz_C / n ** 2, color=col, marker=mk, linestyle="none", label=f"n = {int(n)}")
            ax.plot(dgrid, 1 - (1 - dgrid ** 2) ** n, color=col, linewidth=1, alpha=0.7)
        ax.plot(dgrid, dgrid, color="#8a8984", linestyle="--", linewidth=1, label="output = input density")
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xlabel("Input density of A and B")
        ax.set_ylabel("Output density nnz(C) / n$^2$")
        ax.set_title(f"Fill-in of the product ({host})\nmarkers: measured; lines: $1-(1-d^2)^n$")
        ax.legend(loc="lower right", fontsize=7)
        save(fig, figs / "sparse_output_density")

    # Real matrices.
    real = pilot[pilot.matrix != "none"]
    if not real.empty:
        rows = []
        for mat, g in real.groupby("matrix"):
            e = g[(g.method == "ejml") & (g.status == "OK")]
            n = int(g.n.iloc[0])
            row = {"matrix": mat, "n": n}
            if not e.empty:
                row["nnz(A)"] = int(e.nnz_A.iloc[0])
                row["density(A)"] = fmt(e.nnz_A.iloc[0] / n ** 2)
                row["nnz(A*A)"] = int(e.nnz_C.iloc[0])
                row["density(A*A)"] = fmt(e.nnz_C.iloc[0] / n ** 2)
            for m in ["ejml", "convert"] + [x for x in DENSE_METHODS if x in set(g.method)]:
                hit = s[(s.method == m) & (s.matrix == mat)] if not s.empty else s
                if not hit.empty:
                    row[LABEL[m] + " [s]"] = f"{fmt(hit['median'].iloc[0])} ({fmt(hit['iqr'].iloc[0], 2)})"
                else:
                    row[LABEL[m] + " [s]"] = status_cell(pilot, {"method": m, "matrix": mat}) or "--"
            rows.append(row)
        write_table(pd.DataFrame(rows), tables / "sparse_real",
                    f"Real SuiteSparse matrices (product $A\\cdot A$) on {tex_escape(host)}: median (IQR) in seconds. "
                    + budget_note(pilot), "tab:sparse-real")


# ----------------------------------------------------------------------------- memory

def memory(df: pd.DataFrame, tables: Path, figs: Path, host: str) -> None:
    mem = df[df.kind == "memory"]
    jmh = df[df.kind == "jmh"]
    if mem.empty:
        return
    for part in sorted(mem.part.unique()):
        mp = mem[mem.part == part]
        by = ["method", "n", "density", "matrix"]
        peak = summarise(mp, by, "peak_heap_used")
        extra = summarise(mp, by, "peak_minus_baseline")
        analytic = summarise(mp, by, "analytic_storage")
        alloc = summarise(jmh[jmh.part == part], by, "gc.alloc.rate.norm")
        if peak.empty:
            continue
        t = peak[by + ["median", "count"]].rename(columns={"median": "peak heap used [B]", "count": "reps"})
        t = t.merge(extra[by + ["median"]].rename(columns={"median": "peak - baseline [B]"}), on=by, how="left")
        t = t.merge(analytic[by + ["median"]].rename(columns={"median": "analytic storage [B]"}), on=by, how="left")
        if not alloc.empty:
            t = t.merge(alloc[by + ["median"]].rename(columns={"median": "allocated per op [B]"}), on=by, how="left")
        t = t.sort_values(["method", "matrix", "density", "n"])
        out = t.copy()
        for c in out.columns:
            if c.endswith("[B]"):
                out[c] = out[c].map(lambda v: fmt(v))
        out["density"] = out["density"].map(lambda v: f"{v:g}")
        write_table(out, tables / f"memory_{part}",
                    f"Memory on {tex_escape(host)} ({tex_escape(part)}): three different quantities. Analytic storage of "
                    "the matrices; peak heap used around one operation (median of the repetitions, separate JVM, "
                    "serial GC without TLABs); bytes allocated per operation (JMH gc.alloc.rate.norm).",
                    f"tab:memory-{part}")

        # Figure: one panel per method, the three quantities vs n (synthetic inputs only).
        syn = t[t.matrix == "none"]
        methods = [m for m in DENSE_METHODS + ["ejml", "convert"] if m in set(syn.method)]
        if not methods:
            continue
        dens = sorted(syn.density.unique())
        dsel = dens[-1]  # the densest input: the largest memory footprint
        cols = min(3, len(methods))
        nrows = int(np.ceil(len(methods) / cols))
        fig, axes = plt.subplots(nrows, cols, figsize=(2.6 * cols + 0.6, 2.6 * nrows), squeeze=False)
        quantities = [("analytic storage [B]", "#2a78d6", "o", "Analytic storage"),
                      ("peak heap used [B]", "#eb6834", "s", "Peak heap used"),
                      ("allocated per op [B]", "#1baf7a", "^", "Allocated per op (JMH)")]
        for ax, m in zip(axes.flat, methods):
            d = syn[(syn.method == m) & (syn.density == dsel)].sort_values("n")
            for col, color, marker, lab in quantities:
                if col in d and d[col].notna().any():
                    v = d[col].where(d[col] > 0)
                    ax.plot(d.n, v / 2 ** 20, color=color, marker=marker, label=lab,
                            markeredgecolor="white", markeredgewidth=0.8)
            ax.set_xscale("log")
            ax.set_yscale("log")
            ax.set_xticks(sorted(d.n.unique()))
            ax.set_xticklabels([str(int(v)) for v in sorted(d.n.unique())], rotation=45)
            ax.minorticks_off()
            ax.set_title(LABEL[m])
            ax.set_xlabel("Matrix size n")
            ax.set_ylabel("Memory [MiB]")
        for ax in list(axes.flat)[len(methods):]:
            ax.set_visible(False)
        handles, labels = axes.flat[0].get_legend_handles_labels()
        fig.legend(handles, labels, loc="upper center", ncol=3, bbox_to_anchor=(0.5, 0.0))
        title = f"Memory ({part}, {host})" + (f", input density {dsel:g}" if part == "sparse" else "")
        fig.suptitle(title)
        fig.tight_layout()
        save(fig, figs / f"memory_{part}")


# ----------------------------------------------------------------------------- limits

def limits(df: pd.DataFrame, tables: Path, host: str) -> None:
    p = df[df.kind == "pilot"]
    if p.empty:
        return
    rows = []
    for (part, method, matrix, dens), g in p.groupby(["part", "method", "matrix", "density"]):
        ok = g[g.status == "OK"]
        bad = g[g.status != "OK"].sort_values("n")
        rows.append({
            "part": part, "method": method, "matrix": matrix,
            "density": f"{dens:g}" if matrix == "none" else "--",
            "largest feasible n": int(ok.n.max()) if not ok.empty else "--",
            "first failing n": int(bad.n.iloc[0]) if not bad.empty else "--",
            "status": bad.status.iloc[0] if not bad.empty else "all OK",
            "limit": bad.note.iloc[0] if not bad.empty else "",
        })
    write_table(pd.DataFrame(rows), tables / "limits",
                f"Feasibility limits found by the pilot on {tex_escape(host)} (heap = 75\\% of RAM). "
                + budget_note(p), "tab:limits")


# ----------------------------------------------------------------------------- main

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raw", default=ROOT / "results" / "raw", type=Path)
    ap.add_argument("--tables", default=ROOT / "results" / "tables", type=Path)
    ap.add_argument("--figures", default=ROOT / "results" / "figures", type=Path)
    args = ap.parse_args()

    df = load_all(args.raw)
    args.tables.mkdir(parents=True, exist_ok=True)
    df.to_csv(args.tables / "normalized_all.csv", index=False)
    print(f"normalized_all.csv: {len(df)} rows from {df.source.nunique()} raw files")
    if df.empty:
        return
    df = fill_nnz(df)
    for host, h in df.groupby("host"):
        cur = latest(h)
        tdir, fdir = args.tables / host, args.figures / host
        part_a(cur, tdir, fdir, host)
        sweep(cur, "block_sweep", "block_size", "blocked", tdir, fdir, host)
        sweep(cur, "cutoff_sweep", "cutoff", "strassen", tdir, fdir, host)
        part_b(cur, tdir, fdir, host)
        memory(cur, tdir, fdir, host)
        limits(cur, tdir, host)
        print(f"{host}: tables in {tdir}, figures in {fdir}")


if __name__ == "__main__":
    main()
