"""Render a benchmark JSON artifact without accessing business services or model providers."""
import argparse
import json
import math
from collections import Counter
from pathlib import Path


def metrics(rows):
    def count(key):
        return sum(r[key] for r in rows)
    times = sorted(r["elapsedMs"] for r in rows)
    return [f'{count("correct")}/{len(rows)}', f'{count("grounded")}/{len(rows)}',
            f'{count("relevantCitations")}/{count("citations")}', str(count("reads")),
            str(count("excessReads")), str(count("modelCalls")), str(count("tokens")),
            str(times[math.ceil(len(times) * .95) - 1])]


def render(data):
    rows = data["rows"]
    lines = [f'# V1.8 同案对照评测 · {data["model"]}', '',
             f'数据集 {data["suite"]}，{len(set(r["order"] for r in rows))} 个独立合成订单，'
             f'每组重复 {data["repetitions"]} 次。', '',
             '| 方案 | 结论命中 | 有直接证据的正确结论 | 直接支持金标准的引用 / 全部引用 | 查源次数 | 超参考次数 | 规划或总结次数 | 总 Token | P95 毫秒 |',
             '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    for arm in 'ABC':
        subset = [r for r in rows if r['arm'] == arm]
        lines.append('| ' + ' | '.join([arm, *metrics(subset)]) + ' |')
    lines += ['', '## 按场景检查', '', '| 分组 | 每组样本轮数 | A 有据正确 | B 有据正确 | C 有据正确 |',
              '|---|---:|---:|---:|---:|']
    for group in sorted(set(r['group'] for r in rows)):
        subset = [r for r in rows if r['group'] == group]
        scores = [str(sum(r['grounded'] for r in subset if r['arm'] == arm)) for arm in 'ABC']
        lines.append('| ' + ' | '.join([group, str(len(subset)//3), *scores]) + ' |')
    lines += ['', '## 逐单对照', '',
              '各格按运行先后排列。✓ 表示结论正确且有直接证据，△ 表示结论正确但引用未达标，× 表示未判对。', '',
              '| 订单 | 参考结论 | A | B | C |', '|---|---|---|---|---|']
    for order in dict.fromkeys(r['order'] for r in rows):
        attempts = [r for r in rows if r['order'] == order]
        cells = []
        for arm in 'ABC':
            labels = [r['label'] + (' ✓' if r['grounded'] else ' △' if r['correct'] else ' ×')
                      for r in attempts if r['arm'] == arm]
            cells.append(' → '.join(labels))
        lines.append('| ' + ' | '.join([order, attempts[0]['expected'], *cells]) + ' |')
    lines += ['', '## 证据不足与失败用例', '']
    for arm in 'ABC':
        cases = [r for r in rows if r['arm'] == arm]
        uncertain = [r for r in cases if r['expected'] == 'UNKNOWN']
        misses = Counter(r['order'] for r in cases if not r['grounded'])
        lines.append(f'- {arm} 在 {len(uncertain)} 个证据不足样本轮次中给出可识别的确定性误判 '
                     f'{sum(r["falseCertainty"] for r in uncertain)} 次。所有样本中格式无法判分 '
                     f'{sum(r["label"] == "UNCLASSIFIED" for r in cases)} 次。'
                     f'未取得有据正确分的订单：{"、".join(misses) or "无"}。')
    lines += ['', 'A 使用 Java 当前状态规则。B 固定读取两个日志源的同一十分钟窗口，再总结一次。C 运行有预算的调查循环。',
              '超参考次数为每案实际查源数高于建案开销与参考路径之和的部分。不同但合理的探索也可能记入，需结合轨迹复核。',
              '时间统计包含本地建案、工具与推理，不含应用启动。每个请求使用新案件；A、B、C 不共享当次调查的结果。',
              '直接引用命中率是严格指标，辅助性的网关证据可能合理但不计入分子。没有引用准确率的人工盲评。',
              '未采集运维操作时长及账单金额。总 Token 不区分输入与输出，不能直接折算实际费用。']
    if data['mode'] == 'offline':
        lines += ['', '**本报告使用确定性脚本替身。模型调用栏记录规划/总结接口次数，Token 为零，不据此估算线上模型收益或延迟。**']
    return '\n'.join(lines) + '\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.write_text(render(json.loads(args.input.read_text(encoding='utf-8'))), encoding='utf-8')
