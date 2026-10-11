import 'package:flutter_test/flutter_test.dart';
import 'package:nihaisha_app/screens/markdown_doc_screen.dart';
import 'package:nihaisha_app/data/formula_repository.dart';
import 'package:nihaisha_app/data/herb_repository.dart';

/// 回归：闭门课 / 伤寒金匮 / 内经 正文里的方剂·药材链接点击无反应。
///
/// 根因：flutter_markdown 会把链接目标做 percent-encoding 后传给 `onTapLink`，
/// 例如正文 `[四逆汤](formula://四逆汤)` 传入的是 `formula://%E5%9B%9B%E9%80%86%E6%B1%A4`。
/// 旧代码 `href.substring('formula://'.length)` 拿到编码串直接查库 → 永远 null → 静默无跳转。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('decodeMarkdownHref 解 percent-encoding；非法 % 不抛异常', () {
    expect(decodeMarkdownHref('formula://%E5%9B%9B%E9%80%86%E6%B1%A4'),
        'formula://四逆汤');
    expect(decodeMarkdownHref('herb://%E6%9F%B4%E8%83%A1'), 'herb://柴胡');
    // 非法 % 序列：原样返回，不崩
    expect(decodeMarkdownHref('formula://100%'), 'formula://100%');
  });

  test('编码 href 解码后能命中方剂 / 药材（复刻 flutter_markdown 的编码形态）', () async {
    await FormulaRepository.load();
    await HerbRepository.load();

    // 方剂：formula://四逆汤
    final fHref = 'formula://${Uri.encodeComponent('四逆汤')}';
    final fName = decodeMarkdownHref(fHref.substring('formula://'.length));
    expect(fName, '四逆汤');
    expect(FormulaRepository.getByName(fName), isNotNull);

    // 药材（含别名归一）：herb://柴胡
    final hHref = 'herb://${Uri.encodeComponent('柴胡')}';
    final hName = decodeMarkdownHref(hHref.substring('herb://'.length));
    expect(hName, '柴胡');
    expect(HerbRepository.getExactByName(hName), isNotNull);
  });

  test('未解码时会命中失败（证明该回归确实由缺解码引起）', () async {
    await FormulaRepository.load();
    final encoded = Uri.encodeComponent('四逆汤'); // %E5%9B%9B...
    expect(FormulaRepository.getByName(encoded), isNull);
  });
}
