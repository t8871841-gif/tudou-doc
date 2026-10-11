import 'package:flutter_test/flutter_test.dart';
import 'package:nihaisha_app/screens/formula_detail_screen.dart';

/// 与运行时同源：全部方名按长优先拼成的正则（最长优先匹配）。
RegExp _nameRe(List<String> names) {
  final sorted = [...names]..sort((a, b) => b.length.compareTo(a.length));
  return RegExp(sorted.map(RegExp.escape).join('|'));
}

void main() {
  final re = _nameRe(['桂枝汤', '茯苓四逆汤', '通脉四逆汤', '四逆汤', '白虎加人参汤', '真武汤']);

  test('方证标题即该方 → 命中', () {
    expect(clauseIntroducesFormula('#### 条文34：四逆汤（救逆第四方）\n', re, '四逆汤'), isTrue);
    expect(clauseIntroducesFormula('#### 条文20：桂枝汤禁忌——无汗伤寒禁用\n', re, '桂枝汤'), isTrue);
  });

  test('原文「XX主之」→ 命中（中间档仅认主之）', () {
    // 方证标题首行即该方（标题规则仍保留）
    expect(clauseIntroducesFormula('### 三三七：[少阴病]，脉沉者，急温之，宜[四逆汤]。\n', re, '四逆汤'), isTrue);
    // 原文「XX主之」
    expect(clauseIntroducesFormula('### 三六九：大汗，若大下利而厥冷者，「四逆汤」主之。\n', re, '四逆汤'), isTrue);
    expect(clauseIntroducesFormula('> 太阳病发汗…真武汤主之。\n', re, '真武汤'), isTrue);
  });

  test('原文仅有「宜/可与」而无主之 → 不命中（中间档舍去弱信号）', () {
    // 仅「宜/可与」置于原文引文块，无「主之」→ 中间档不计入
    expect(clauseIntroducesFormula('> [少阴病]，脉沉者，急温之，宜[四逆汤]。\n', re, '四逆汤'), isFalse);
    expect(clauseIntroducesFormula('> 舌上白苔者，可与「桂枝汤」，上焦得通\n', re, '桂枝汤'), isFalse);
  });

  test('最长优先：四逆汤 不误命中 茯苓四逆汤 / 通脉四逆汤', () {
    expect(clauseIntroducesFormula('#### 条文74：茯苓四逆汤\n', re, '四逆汤'), isFalse);
    expect(clauseIntroducesFormula('### 三八六：汗出而厥者，『通脉四逆汤』主之。\n', re, '四逆汤'), isFalse);
    expect(clauseIntroducesFormula('#### 条文74：茯苓四逆汤\n', re, '茯苓四逆汤'), isTrue);
  });

  test('误治 / 对比语境不算「专门介绍」', () {
    // 服桂枝汤后转白虎加人参汤：是白虎汤证条文，非「介绍桂枝汤」
    expect(
        clauseIntroducesFormula(
            '> 服桂枝汤，大汗出后，大烦渴不解，脉洪大者，白虎加人参汤主之。\n', re, '桂枝汤'),
        isFalse);
    expect(
        clauseIntroducesFormula('> 伤寒脉浮…反与桂枝汤，欲攻其表，此误也。\n', re, '桂枝汤'),
        isFalse);
  });

  test('只看方证标题 + 原文，讲解小标题里的方名不算', () {
    const md = '#### 条文90：真武汤\n\n> 太阳病发汗…真武汤主之。\n\n#### 与桂枝汤之别\n\n- 此处讲桂枝汤…\n';
    expect(clauseIntroducesFormula(md, re, '桂枝汤'), isFalse);
    expect(clauseIntroducesFormula(md, re, '真武汤'), isTrue);
  });
}
