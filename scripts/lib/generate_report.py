#!/usr/bin/env python3
"""generate_report.py

聚合 unit (surefire + jacoco) / perf (JMH JSON) / e2e (surefire) 的产物，
输出：
  - <report_root>/summary.json          机器可读
  - <doc_out>                           Markdown 摘要（默认写入 docs/test-reports/）

所有解析失败/缺失文件均降级为占位，不抛异常，确保脚本主流程不被打断。
"""
from __future__ import annotations

import argparse
import datetime as _dt
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field
from typing import Any


# ---------------- dataclass ----------------


@dataclass
class SurefireSummary:
    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    time_seconds: float = 0.0
    modules: dict[str, dict[str, Any]] = field(default_factory=dict)
    failed_cases: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class JacocoSummary:
    overall_coverage: float = 0.0
    overall_covered: int = 0
    overall_missed: int = 0
    modules: dict[str, dict[str, float | int]] = field(default_factory=dict)


@dataclass
class JmhBench:
    benchmark: str
    mode: str
    score: float
    error: float
    unit: str
    params: dict[str, str] = field(default_factory=dict)


@dataclass
class StageStatus:
    status: int
    label: str


# ---------------- surefire ----------------


def parse_surefire(root_dir: str) -> SurefireSummary:
    """遍历 surefire xml 汇总测试统计。"""
    s = SurefireSummary()
    if not os.path.isdir(root_dir):
        return s
    xml_files = sorted(glob.glob(os.path.join(root_dir, "**", "TEST-*.xml"), recursive=True))
    for xml in xml_files:
        try:
            tree = ET.parse(xml)
        except ET.ParseError:
            continue
        root = tree.getroot()
        if root.tag != "testsuite":
            continue
        module = os.path.basename(os.path.dirname(xml))
        tests = int(root.attrib.get("tests", "0"))
        failures = int(root.attrib.get("failures", "0"))
        errors = int(root.attrib.get("errors", "0"))
        skipped = int(root.attrib.get("skipped", "0"))
        time_s = float(root.attrib.get("time", "0"))

        s.tests += tests
        s.failures += failures
        s.errors += errors
        s.skipped += skipped
        s.time_seconds += time_s

        mstat = s.modules.setdefault(module, {
            "tests": 0, "failures": 0, "errors": 0, "skipped": 0, "time_seconds": 0.0,
        })
        mstat["tests"] += tests
        mstat["failures"] += failures
        mstat["errors"] += errors
        mstat["skipped"] += skipped
        mstat["time_seconds"] += time_s

        for tc in root.iter("testcase"):
            failure = tc.find("failure")
            error = tc.find("error")
            if failure is not None or error is not None:
                node = failure if failure is not None else error
                s.failed_cases.append({
                    "module": module,
                    "classname": tc.attrib.get("classname", ""),
                    "name": tc.attrib.get("name", ""),
                    "type": node.attrib.get("type", "") if node is not None else "",
                    "message": (node.attrib.get("message", "") if node is not None else "")[:300],
                })
    return s


# ---------------- jacoco ----------------


def parse_jacoco(root_dir: str) -> JacocoSummary:
    """优先用每 module 的 jacoco.csv（mvn jacoco:report 产出）。"""
    j = JacocoSummary()
    if not os.path.isdir(root_dir):
        return j

    # 收集时按 <module>/ 组织；优先找 csv，没有就读 jacoco.xml
    for entry in sorted(os.listdir(root_dir)):
        mdir = os.path.join(root_dir, entry)
        if not os.path.isdir(mdir):
            continue
        csv_path = os.path.join(mdir, "jacoco.csv")
        # jacoco-maven-plugin 默认 csv 名实际不一定是 jacoco.csv；fallback 模糊匹配
        if not os.path.exists(csv_path):
            for f in os.listdir(mdir):
                if f.endswith(".csv"):
                    csv_path = os.path.join(mdir, f)
                    break
        if os.path.exists(csv_path):
            covered, missed = _parse_jacoco_csv(csv_path)
        else:
            xml_path = os.path.join(mdir, "jacoco.xml")
            if not os.path.exists(xml_path):
                continue
            covered, missed = _parse_jacoco_xml(xml_path)
        total = covered + missed
        cov = (covered / total * 100) if total > 0 else 0.0
        j.modules[entry] = {"covered": covered, "missed": missed, "coverage_pct": round(cov, 2)}
        j.overall_covered += covered
        j.overall_missed += missed

    total = j.overall_covered + j.overall_missed
    if total > 0:
        j.overall_coverage = round(j.overall_covered / total * 100, 2)
    return j


def _parse_jacoco_csv(path: str) -> tuple[int, int]:
    """jacoco.csv 列: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,..."""
    covered = 0
    missed = 0
    try:
        with open(path, "r", encoding="utf-8") as f:
            header = f.readline().strip().split(",")
            idx_missed = header.index("INSTRUCTION_MISSED") if "INSTRUCTION_MISSED" in header else 3
            idx_covered = header.index("INSTRUCTION_COVERED") if "INSTRUCTION_COVERED" in header else 4
            for line in f:
                parts = line.strip().split(",")
                if len(parts) <= max(idx_missed, idx_covered):
                    continue
                try:
                    missed += int(parts[idx_missed])
                    covered += int(parts[idx_covered])
                except ValueError:
                    pass
    except OSError:
        pass
    return covered, missed


def _parse_jacoco_xml(path: str) -> tuple[int, int]:
    try:
        tree = ET.parse(path)
    except ET.ParseError:
        return 0, 0
    root = tree.getroot()
    covered = 0
    missed = 0
    # /report/counter[@type='INSTRUCTION']
    for c in root.findall("counter"):
        if c.attrib.get("type") == "INSTRUCTION":
            covered += int(c.attrib.get("covered", "0"))
            missed += int(c.attrib.get("missed", "0"))
    return covered, missed


# ---------------- jmh ----------------


def parse_jmh(json_path: str) -> list[JmhBench]:
    if not os.path.exists(json_path):
        return []
    try:
        with open(json_path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (json.JSONDecodeError, OSError):
        return []
    out: list[JmhBench] = []
    for b in data:
        try:
            primary = b.get("primaryMetric", {})
            out.append(JmhBench(
                benchmark=b.get("benchmark", ""),
                mode=b.get("mode", ""),
                score=float(primary.get("score", 0.0)),
                error=float(primary.get("scoreError", 0.0) or 0.0),
                unit=primary.get("scoreUnit", ""),
                params=b.get("params", {}) or {},
            ))
        except (TypeError, ValueError):
            continue
    return out


# ---------------- markdown 渲染 ----------------


def render_markdown(ts: str, sfu: SurefireSummary, jc: JacocoSummary, sfe: SurefireSummary,
                    jmh: list[JmhBench], unit: StageStatus, perf: StageStatus,
                    e2e: StageStatus, report_root: str) -> str:
    lines: list[str] = []
    lines.append(f"# GateDemo 测试报告 - {ts}")
    lines.append("")
    lines.append(f"- **生成时间（UTC）**: {_dt.datetime.now(_dt.timezone.utc).strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- **报告根目录**: `reports/{ts}/`")
    lines.append("")
    lines.append("## 阶段汇总")
    lines.append("")
    lines.append("| 阶段 | 状态 | 备注 |")
    lines.append("|---|---|---|")
    lines.append(f"| 单测 + 覆盖率 | {_status_emoji(unit)} {unit.label} | tests={sfu.tests} pass={sfu.tests - sfu.failures - sfu.errors - sfu.skipped} fail={sfu.failures + sfu.errors} skip={sfu.skipped} |")
    lines.append(f"| JMH 性能 | {_status_emoji(perf)} {perf.label} | benchmarks={len(jmh)} |")
    lines.append(f"| e2e 场景 | {_status_emoji(e2e)} {e2e.label} | tests={sfe.tests} pass={sfe.tests - sfe.failures - sfe.errors - sfe.skipped} fail={sfe.failures + sfe.errors} skip={sfe.skipped} |")
    lines.append("")

    # ---- unit ----
    lines.append("## 单测明细")
    lines.append("")
    if sfu.tests == 0:
        lines.append("_未收集到单测数据。_")
    else:
        lines.append(f"- 总用例: **{sfu.tests}**")
        lines.append(f"- 通过: **{sfu.tests - sfu.failures - sfu.errors - sfu.skipped}**")
        lines.append(f"- 失败 + 错误: **{sfu.failures + sfu.errors}**")
        lines.append(f"- 跳过: **{sfu.skipped}**")
        lines.append(f"- 耗时: **{sfu.time_seconds:.2f}s**")
        lines.append("")
        lines.append("| 模块 | 用例 | 通过 | 失败 | 跳过 | 耗时(s) |")
        lines.append("|---|---:|---:|---:|---:|---:|")
        for m in sorted(sfu.modules):
            v = sfu.modules[m]
            passed = v["tests"] - v["failures"] - v["errors"] - v["skipped"]
            lines.append(f"| {m} | {v['tests']} | {passed} | {v['failures'] + v['errors']} | {v['skipped']} | {v['time_seconds']:.2f} |")
        if sfu.failed_cases:
            lines.append("")
            lines.append("### 失败用例")
            lines.append("")
            for fc in sfu.failed_cases[:20]:
                lines.append(f"- `{fc['module']}` :: `{fc['classname']}.{fc['name']}` — {fc['type']}: {fc['message']}")
            if len(sfu.failed_cases) > 20:
                lines.append(f"- _...还有 {len(sfu.failed_cases) - 20} 条未列出，见 surefire XML。_")
    lines.append("")

    # ---- coverage ----
    lines.append("## 覆盖率（JaCoCo / INSTRUCTION）")
    lines.append("")
    if jc.overall_covered + jc.overall_missed == 0:
        lines.append("_未采集到 JaCoCo 数据（可能未执行 mvn verify，或目标 module 未启用 jacoco）。_")
    else:
        lines.append(f"- 整体: **{jc.overall_coverage:.2f}%** ({jc.overall_covered} / {jc.overall_covered + jc.overall_missed} instructions)")
        lines.append("")
        lines.append("| 模块 | covered | missed | 覆盖率 |")
        lines.append("|---|---:|---:|---:|")
        for m in sorted(jc.modules):
            v = jc.modules[m]
            lines.append(f"| {m} | {v['covered']} | {v['missed']} | {v['coverage_pct']:.2f}% |")
    lines.append("")

    # ---- perf ----
    lines.append("## JMH 性能基线")
    lines.append("")
    if not jmh:
        lines.append("_未采集到 JMH 数据。_")
    else:
        lines.append(f"- benchmark 数量: **{len(jmh)}**")
        lines.append("")
        lines.append("| Benchmark | Mode | Params | Score | Error | Unit |")
        lines.append("|---|---|---|---:|---:|---|")
        for b in jmh:
            params = ", ".join(f"{k}={v}" for k, v in sorted(b.params.items())) or "-"
            short = b.benchmark.split(".")[-1]
            # JMH 在单 iteration / 单 fork 下不会算 stddev，会输出 NaN；改显示 n/a
            err_display = "n/a" if (b.error != b.error or b.error == 0.0) else f"±{b.error:.2f}"
            lines.append(f"| {short} | {b.mode} | {params} | {b.score:.2f} | {err_display} | {b.unit} |")
        lines.append("")
        lines.append(f"_完整 JSON: `reports/{ts}/perf/jmh-result.json`_")
    lines.append("")

    # ---- e2e ----
    lines.append("## e2e 场景明细")
    lines.append("")
    if sfe.tests == 0:
        lines.append("_未执行 e2e 场景（可能 --no-e2e 或 docker-compose 启动失败）。_")
    else:
        lines.append("| 模块 | 用例 | 通过 | 失败 | 跳过 | 耗时(s) |")
        lines.append("|---|---:|---:|---:|---:|---:|")
        for m in sorted(sfe.modules):
            v = sfe.modules[m]
            passed = v["tests"] - v["failures"] - v["errors"] - v["skipped"]
            lines.append(f"| {m} | {v['tests']} | {passed} | {v['failures'] + v['errors']} | {v['skipped']} | {v['time_seconds']:.2f} |")
        if sfe.failed_cases:
            lines.append("")
            lines.append("### e2e 失败用例")
            lines.append("")
            for fc in sfe.failed_cases:
                lines.append(f"- `{fc['module']}` :: `{fc['classname']}.{fc['name']}` — {fc['type']}: {fc['message']}")
    lines.append("")

    # ---- footer ----
    lines.append("---")
    lines.append("")
    lines.append(f"_产物详见 `reports/{ts}/`（surefire HTML、jacoco HTML、jmh JSON、maven 原始日志）。_")
    lines.append("")
    return "\n".join(lines)


def _status_emoji(s: StageStatus) -> str:
    if s.status == 0:
        return "[OK]"
    if s.status == -1:
        return "[SKIP]"
    return "[FAIL]"


def _status_to_label(rc: int) -> str:
    if rc == 0:
        return "PASS"
    if rc == -1:
        return "SKIPPED"
    return f"FAIL(rc={rc})"


# ---------------- main ----------------


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ts", required=True)
    parser.add_argument("--report-root", required=True)
    parser.add_argument("--doc-out", required=True)
    parser.add_argument("--unit-status", type=int, default=-1)
    parser.add_argument("--perf-status", type=int, default=-1)
    parser.add_argument("--e2e-status", type=int, default=-1)
    args = parser.parse_args()

    sfu = parse_surefire(os.path.join(args.report_root, "unit", "surefire"))
    jc = parse_jacoco(os.path.join(args.report_root, "unit", "jacoco"))
    sfe = parse_surefire(os.path.join(args.report_root, "e2e", "surefire"))
    jmh = parse_jmh(os.path.join(args.report_root, "perf", "jmh-result.json"))

    unit_st = StageStatus(args.unit_status, _status_to_label(args.unit_status))
    perf_st = StageStatus(args.perf_status, _status_to_label(args.perf_status))
    e2e_st = StageStatus(args.e2e_status, _status_to_label(args.e2e_status))

    md = render_markdown(args.ts, sfu, jc, sfe, jmh, unit_st, perf_st, e2e_st, args.report_root)
    os.makedirs(os.path.dirname(args.doc_out), exist_ok=True)
    with open(args.doc_out, "w", encoding="utf-8") as f:
        f.write(md)

    summary = {
        "ts": args.ts,
        "stages": {
            "unit": {"status": args.unit_status, "label": unit_st.label, "summary": asdict(sfu)},
            "perf": {"status": args.perf_status, "label": perf_st.label, "benchmarks": [asdict(b) for b in jmh]},
            "e2e": {"status": args.e2e_status, "label": e2e_st.label, "summary": asdict(sfe)},
            "coverage": asdict(jc),
        },
    }
    summary_path = os.path.join(args.report_root, "summary.json")
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)

    return 0


if __name__ == "__main__":
    sys.exit(main())
