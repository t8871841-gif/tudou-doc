import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

/// HUD painters ported from the single-file web Jarvis design
/// (`index_0_jeoy.html`): central reactor, FX layer (particles, radar
/// sweep, hex data ring, orbiting satellites, lens flare), perspective
/// grid background. All animation is driven by a shared tick
/// [ValueListenable] so the painters repaint at display refresh rate
/// without rebuilding widgets; each painter keeps its own smoothing
/// state (hue, spin, energy level, particles) exactly like the HTML
/// per-frame easing. Original vector art, no assets.

const hudCyan = Color(0xFF33DDFF); // hsl(190, 100%, 60%)
const hudBlue = Color(0xFF2E9BFF);
const hudDeep = Color(0xFF00060B); // HTML body background

/// Visual mode of the HUD. Mirrors the HTML state -> hue mapping:
/// idle 190 cyan, listening 38 amber, thinking 205 blue, speaking 185 cyan.
enum HudMode { idle, listening, thinking, speaking }

double hudHueFor(HudMode mode) => switch (mode) {
      HudMode.idle => 190,
      HudMode.listening => 38,
      HudMode.thinking => 205,
      HudMode.speaking => 185,
    };

/// HTML `--c` = hsl(hue, 100%, 60%).
Color hueColor(double hue, double alpha) =>
    HSLColor.fromAHSL(alpha, hue, 1.0, 0.6).toColor();

/// 柔和渐变配色（参考 Gemini：蓝 → 紫 → 玫瑰）。
/// 每个状态一个主色，另配两个同色系辅助色，全界面的渐变都用这三色。
const hudBlue2 = Color(0xFF8AB4F8); // 待命：天蓝
const hudRose = Color(0xFFE8788A); // 聆听：玫瑰
const hudPurple = Color(0xFFA98BE0); // 思考：薰衣草紫
const hudAqua = Color(0xFF7FD6E6); // 回答：浅青

(Color, Color) hudPaletteFor(Color primary) {
  if (primary == hudBlue2) return (hudPurple, hudRose);
  if (primary == hudRose) return (hudPurple, hudBlue2);
  if (primary == hudPurple) return (hudBlue2, hudRose);
  if (primary == hudAqua) return (hudBlue2, hudPurple);
  final h = HSLColor.fromColor(primary);
  return (
    h.withHue((h.hue + 40) % 360).toColor(),
    h.withHue((h.hue + 90) % 360).toColor(),
  );
}

/// HTML `.sub .y` user line: hsla(190, 60%, 75%, .65).
Color get userDimColor =>
    HSLColor.fromAHSL(0.65, 190, 0.6, 0.75).toColor();

/// Shared per-frame state machine for the animated painters.
/// Subclasses implement [paintFrame]; this base handles hue / spin /
/// energy-level easing and state-change shockwaves, mirroring the HTML
/// `frame()` loop.
abstract class _HudPainter extends CustomPainter {
  _HudPainter({
    required this.tick,
    required this.mode,
    required this.wakeMode,
    required this.micLevel,
    required this.themeColor,
  }) : super(repaint: tick);

  final ValueListenable<double> tick;
  final HudMode Function() mode;
  final bool Function() wakeMode;
  final double Function() micLevel;
  // 动态主题色：待命全息蓝 / 聆听纳米红 / 思考·说话纳米金，由 home_screen 按 JarvisState 提供。
  final Color Function() themeColor;

  // 平滑过渡后的主题色：每帧向目标色 lerp，避免状态切换时颜色硬跳。
  Color _displayedTheme = hudBlue2;
  Color _displayedSec = hudPurple;
  Color _displayedTer = hudRose;

  /// 辅助色 / 第三色（同样每帧平滑过渡）。
  Color secCol(double a) => _displayedSec.withValues(alpha: a.clamp(0.0, 1.0));
  Color terCol(double a) => _displayedTer.withValues(alpha: a.clamp(0.0, 1.0));

  /// 沿 主色→辅色→第三色→主色 的环形渐变取色，u 为 0..1（自动取小数部分）。
  Color spectrumCol(double u, double a) {
    final w = u - u.floorToDouble();
    final s3 = w * 3;
    final Color from;
    final Color to;
    final double f;
    if (s3 < 1) {
      from = _displayedTheme;
      to = _displayedSec;
      f = s3;
    } else if (s3 < 2) {
      from = _displayedSec;
      to = _displayedTer;
      f = s3 - 1;
    } else {
      from = _displayedTer;
      to = _displayedTheme;
      f = s3 - 2;
    }
    return (Color.lerp(from, to, f) ?? from)
        .withValues(alpha: a.clamp(0.0, 1.0));
  }

  /// 主题色 + 透明度，替代旧的 hueColor(hue, a) 漂移色。
  Color themeCol(double a) =>
      _displayedTheme.withValues(alpha: a.clamp(0.0, 1.0));

  double hue = 190;
  double spin = 1;
  double level = 0.08;
  HudMode _lastMode = HudMode.idle;
  final List<double> shocks = []; // shockwave start ticks (seconds)

  /// Current smoothed energy level (0..1).
  double get energy => level;

  void step(double t) {
    final m = mode();
    if (m != _lastMode) {
      shocks.add(t);
      _lastMode = m;
    }
    shocks.removeWhere((s) => t - s > 0.9);

    hue += (hudHueFor(m) - hue) * 0.06;
    spin += ((m == HudMode.thinking ? 5 : 1) - spin) * 0.05;

    // 主题色平滑过渡：每帧向目标主题色靠 8%，蓝 ↔ 金 / 红不硬跳。
    _displayedTheme =
        Color.lerp(_displayedTheme, themeColor(), 0.08) ?? themeColor();
    final pal = hudPaletteFor(themeColor());
    _displayedSec = Color.lerp(_displayedSec, pal.$1, 0.08) ?? pal.$1;
    _displayedTer = Color.lerp(_displayedTer, pal.$2, 0.08) ?? pal.$2;

    // Energy target mirrors the HTML: real mic level while listening,
    // synthetic waves while speaking / thinking / idle.
    final mic = micLevel();
    final target = switch (m) {
      HudMode.listening => 0.06 + mic * 0.95,
      HudMode.speaking =>
        0.3 + 0.5 * (math.sin(t * 9) * math.cos(t * 4.3)).abs(),
      HudMode.thinking => 0.25,
      HudMode.idle => 0.08 + 0.03 * math.sin(t * 2),
    };
    level += (target - level) * 0.12;
  }

  /// Shockwave rings pushed on every state change (HTML `sw` array).
  void paintShocks(Canvas canvas, Offset c, double r, double t) {
    for (final s in shocks) {
      final p = (t - s) / 0.9;
      final a = 0.9 * math.pow(0.06, p).toDouble();
      final rr = r * (0.3 + p * 1.8);
      canvas.drawCircle(
        c,
        rr,
        Paint()
          ..color = themeCol(a.clamp(0.0, 1.0))
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2 + a * 3,
      );
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

/// 中央光球：柔和的三色星云光球 + 细渐变光环 + 环绕的声波点阵。
class ReactorPainter extends _HudPainter {
  ReactorPainter({
    required super.tick,
    required super.mode,
    required super.wakeMode,
    required super.micLevel,
    required super.themeColor,
  });

  double get smoothedHue => hue;

  @override
  void paint(Canvas canvas, Size size) {
    final t = tick.value;
    step(t);
    final cx = size.width / 2;
    final cy = size.height / 2;
    final c = Offset(cx, cy);
    final r = math.min(size.width, size.height) * 0.36;
    final lvl = level;
    Color col(double a) => themeCol(a);

    // 1. 大光晕：主色 → 辅色 → 透明，随能量呼吸。
    final haloR = r * (1.35 + lvl * 0.3);
    canvas.drawCircle(
      c,
      haloR,
      Paint()
        ..shader = RadialGradient(
          colors: [
            col(0.30 + lvl * 0.25),
            secCol(0.12 + lvl * 0.10),
            terCol(0),
          ],
          stops: const [0.0, 0.55, 1.0],
        ).createShader(Rect.fromCircle(center: c, radius: haloR)),
    );

    // 2. 星云光球：三团柔焦色块缓慢缠绕，自然混色。
    final orbit = r * 0.2 * (1 + lvl * 1.2);
    final blobR = r * (0.42 + lvl * 0.12);
    final soft = MaskFilter.blur(BlurStyle.normal, r * 0.24);
    for (var i = 0; i < 3; i++) {
      final a = t * (0.45 + i * 0.17) * spin + i * 2.094;
      final p = Offset(
          cx + math.cos(a) * orbit, cy + math.sin(a * 1.15) * orbit);
      final color = i == 0 ? col(0.9) : (i == 1 ? secCol(0.9) : terCol(0.9));
      canvas.drawCircle(
        p,
        blobR,
        Paint()
          ..color = color
          ..maskFilter = soft,
      );
    }

    // 3. 柔和高光核心。
    final coreR = r * 0.26 * (1 + lvl * 0.8);
    canvas.drawCircle(
      c,
      coreR,
      Paint()
        ..shader = RadialGradient(
          colors: [
            Colors.white.withValues(alpha: 0.85),
            Colors.white.withValues(alpha: 0),
          ],
        ).createShader(Rect.fromCircle(center: c, radius: coreR)),
    );

    // 4. 细光环：一圈淡淡的底环 + 一段渐隐的彩色弧光。
    final ringR = r * 0.98;
    _circle(canvas, c, ringR, 1, col(0.14));
    canvas.drawCircle(
      c,
      ringR,
      Paint()
        ..shader = SweepGradient(
          colors: [terCol(0), col(0.9), secCol(0.9), terCol(0)],
          stops: const [0.0, 0.35, 0.7, 1.0],
          transform: GradientRotation(t * 0.8 * spin),
        ).createShader(Rect.fromCircle(center: c, radius: ringR))
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.2
        ..strokeCap = StrokeCap.round,
    );
    _dashedCircle(canvas, c, r * 1.3, 1, col(0.16), 2, 10, -t * 0.12 * spin);

    // 4b. 霓虹渐变环（草稿固定配色）：电光青 → 霓虹紫 → 翡翠绿 → 电光青，
    // 缓慢旋转。草稿的 SweepGradient(...).shader 不存在，改用 createShader。
    final neonR = r * 1.15;
    canvas.drawCircle(
      c,
      neonR,
      Paint()
        ..shader = SweepGradient(
          colors: const [
            Color(0xFF00F0FF),
            Color(0xFF7000FF),
            Color(0xFF00FF9D),
            Color(0xFF00F0FF),
          ],
          transform: GradientRotation(t * 0.5 * spin),
        ).createShader(Rect.fromCircle(center: c, radius: neonR))
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.0
        ..strokeCap = StrokeCap.round,
    );

    // 5. 声波点阵：待命时是一圈小点，说话 / 聆听时随声音伸长。
    final barPaint = Paint()
      ..strokeWidth = 2.4
      ..strokeCap = StrokeCap.round;
    for (var i = 0; i < 72; i++) {
      final a = i / 72 * math.pi * 2;
      final r0 = r * 1.1;
      final len = r * 0.3 * lvl *
              (0.3 +
                  0.7 *
                      (math.sin(i * 0.7 + t * 6) * math.cos(i * 0.31 - t * 3))
                          .abs()) +
          2;
      barPaint.color = spectrumCol(i / 72 + t * 0.06, 0.85);
      canvas.drawLine(
        Offset(cx + math.cos(a) * r0, cy + math.sin(a) * r0),
        Offset(cx + math.cos(a) * (r0 + len), cy + math.sin(a) * (r0 + len)),
        barPaint,
      );
    }

    // 状态切换时的涟漪。
    paintShocks(canvas, c, r, t);
  }

  void _circle(Canvas canvas, Offset c, double radius, double width, Color color) {
    canvas.drawCircle(
      c,
      radius,
      Paint()
        ..color = color
        ..style = PaintingStyle.stroke
        ..strokeWidth = width,
    );
  }

  /// Dashed circle: [dash] on, [gap] off, rotated by [rotation].
  void _dashedCircle(Canvas canvas, Offset c, double radius, double width,
      Color color, double dash, double gap, double rotation) {
    final circ = 2 * math.pi * radius;
    final n = (circ / (dash + gap)).floor();
    if (n < 1) {
      _circle(canvas, c, radius, width, color);
      return;
    }
    final sweep = dash / circ * math.pi * 2;
    final stepA = math.pi * 2 / n;
    final p = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = width;
    for (var i = 0; i < n; i++) {
      canvas.drawArc(Rect.fromCircle(center: c, radius: radius),
          rotation + i * stepA, sweep, false, p);
    }
  }
}

class _FxParticle {
  double a;
  double r;
  double s;
  double z;
  double? px;
  double? py;

  _FxParticle(this.a, this.r, this.s, this.z);

  factory _FxParticle.random(math.Random rnd) => _FxParticle(
        rnd.nextDouble() * math.pi * 2,
        rnd.nextDouble(),
        0.4 + rnd.nextDouble(),
        rnd.nextDouble(),
      );
}

/// 特效层：几缕淡淡的粒子轨迹 + 两颗环绕的小卫星。
/// （去掉了雷达扫描、十六进制数据环和镜头光斑，画面更干净。）
class FxPainter extends _HudPainter {
  FxPainter({
    required super.tick,
    required super.mode,
    required super.wakeMode,
    required super.micLevel,
    required super.themeColor,
  });

  final _rnd = math.Random();
  late final List<_FxParticle> _pts =
      List.generate(60, (_) => _FxParticle.random(_rnd));

  @override
  void paint(Canvas canvas, Size size) {
    final t = tick.value;
    step(t);
    final m = mode();
    final listening = m == HudMode.listening;
    final speaking = m == HudMode.speaking;
    final cx = size.width / 2;
    final cy = size.height / 2;
    final c = Offset(cx, cy);
    final r = math.min(size.width, size.height) * 0.36;
    final maxR = math.max(size.width, size.height) * 0.62;
    final lvl = level;

    // 粒子轨迹：聆听时向内汇聚，说话时向外扩散。
    final pull =
        listening ? -0.004 : speaking ? 0.002 + lvl * 0.01 : 0.0005;
    for (final p in _pts) {
      p.a += p.s * 0.0016 * spin;
      p.r += pull * p.s;
      if (p.r < 0.14) {
        p.r = 1;
        p.px = null;
      } else if (p.r > 1) {
        p.r = 0.16;
        p.px = null;
      }
      final x = cx + math.cos(p.a) * p.r * maxR;
      final y = cy + math.sin(p.a) * p.r * maxR;
      if (p.px != null) {
        canvas.drawLine(
          Offset(p.px!, p.py!),
          Offset(x, y),
          Paint()
            ..color = spectrumCol(p.a / (math.pi * 2) + p.z * 0.3, 0.05 + p.z * 0.28)
            ..strokeWidth = 0.4 + p.z * 1.0
            ..strokeCap = StrokeCap.round,
        );
      }
      p.px = x;
      p.py = y;
    }

    // 两颗小卫星，带一段渐隐的弧线。
    const sats = [
      (1.3, 0.7, 0.0),
      (1.42, -0.5, 2.6),
    ];
    var si = 0;
    for (final (k, sp, ph) in sats) {
      final sc = si == 0 ? secCol(1) : terCol(1);
      si++;
      final a = t * sp * spin + ph;
      final x = cx + math.cos(a) * r * k;
      final y = cy + math.sin(a) * r * k;
      canvas.drawArc(
        Rect.fromCircle(center: c, radius: r * k),
        sp > 0 ? a - 0.7 : a,
        0.7,
        false,
        Paint()
          ..color = sc.withValues(alpha: 0.4)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.4
          ..strokeCap = StrokeCap.round,
      );
      canvas.drawCircle(Offset(x, y), 6, Paint()..color = sc.withValues(alpha: 0.22));
      canvas.drawCircle(Offset(x, y), 2.4, Paint()..color = Colors.white.withValues(alpha: 0.9));
    }

    // 状态切换涟漪（与光球层一致）。
    paintShocks(canvas, c, r, t);
  }
}

/// 极光背景：几团大而柔的光晕缓慢漂移（Gemini 风），
/// 强度随语音能量轻轻起伏，颜色随状态平滑过渡。
class AuroraPainter extends _HudPainter {
  AuroraPainter({
    required super.tick,
    required super.mode,
    required super.wakeMode,
    required super.micLevel,
    required super.themeColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final t = tick.value;
    step(t);
    final w = size.width;
    final h = size.height;
    final lvl = level;

    void blob(Offset c, double r, Color col) {
      canvas.drawCircle(
        c,
        r,
        Paint()
          ..shader = RadialGradient(
            colors: [col, col.withValues(alpha: 0)],
          ).createShader(Rect.fromCircle(center: c, radius: r)),
      );
    }

    // 左上：辅色；右中：第三色；底部：主色（像 Gemini 从底部升起的光）。
    blob(
      Offset(w * (0.08 + 0.08 * math.sin(t * 0.17)),
          h * (0.10 + 0.05 * math.cos(t * 0.23))),
      w * 1.0,
      secCol(0.20 + lvl * 0.08),
    );
    blob(
      Offset(w * (0.98 + 0.06 * math.cos(t * 0.19)),
          h * (0.46 + 0.08 * math.sin(t * 0.15))),
      w * 0.95,
      terCol(0.15 + lvl * 0.07),
    );
    blob(
      Offset(w * (0.5 + 0.12 * math.sin(t * 0.13)),
          h * (1.02 + 0.03 * math.cos(t * 0.21))),
      w * 1.1,
      themeCol(0.22 + lvl * 0.10),
    );
  }
}

/// 极淡的点阵纹理（不再有网格线、扫描线和不透明底色，
/// 这样下面的极光才看得见）。
class GridBackgroundPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final dot = Paint()..color = Colors.white.withValues(alpha: 0.05);
    for (var x = 18.0; x < size.width; x += 36) {
      for (var y = 18.0; y < size.height; y += 36) {
        canvas.drawCircle(Offset(x, y), 0.9, dot);
      }
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
