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
  }) : super(repaint: tick);

  final ValueListenable<double> tick;
  final HudMode Function() mode;
  final bool Function() wakeMode;
  final double Function() micLevel;

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
          ..color = hueColor(hue, a.clamp(0.0, 1.0))
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2 + a * 3,
      );
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

/// Central arc-reactor: core glow, five counter-rotating dashed rings,
/// 72-tick ring, 96 radial sound bars, rotating triangle kernel,
/// white-hot core. Direct port of the HTML `#c` canvas.
class ReactorPainter extends _HudPainter {
  ReactorPainter({
    required super.tick,
    required super.mode,
    required super.wakeMode,
    required super.micLevel,
  });

  double get smoothedHue => hue;

  @override
  void paint(Canvas canvas, Size size) {
    final t = tick.value;
    step(t);
    final cx = size.width / 2;
    final cy = size.height / 2;
    final c = Offset(cx, cy);
    // HTML: R = min(W, H) * .36 of the stage.
    final r = math.min(size.width, size.height) * 0.36;
    final lvl = level;
    Color col(double a) => hueColor(hue, a);

    // Core glow (radial gradient).
    final glow = Paint()
      ..shader = RadialGradient(
        colors: [
          col(0.5 + lvl * 0.45),
          col(0.1 + lvl * 0.18),
          col(0),
        ],
        stops: const [0.0, 0.45, 1.0],
      ).createShader(Rect.fromCircle(center: c, radius: r * 0.7))
      ..style = PaintingStyle.fill;
    canvas.drawCircle(c, r * 0.7, glow);

    // Concentric dashed rings, alternating rotation (HTML `ring()`).
    _dashedCircle(canvas, c, r * 1.12, 1, col(0.45), 2, 9, t * 0.3 * spin);
    _patternCircle(
        canvas, c, r * 0.98, 3, col(0.85), [r * 0.9, r * 0.25, r * 0.12, r * 0.25],
        -t * 0.5 * spin);
    _dashedCircle(canvas, c, r * 0.82, 1.5, col(0.6), 4, 7, t * 0.8 * spin);
    _dashedCircle(
        canvas, c, r * 0.64, 4, col(0.95), r * 0.55, r * 0.22, t * 1.1 * spin);
    _circle(canvas, c, r * 0.3, 2, col(0.9));

    // 72-tick ring, slowly counter-rotating.
    canvas.save();
    canvas.translate(cx, cy);
    canvas.rotate(-t * 0.12 * spin);
    final tickPaint = Paint()
      ..color = col(0.7)
      ..strokeWidth = 1.2;
    for (var i = 0; i < 72; i++) {
      final a = i / 72 * math.pi * 2;
      final inner = r * 1.2;
      final outer = r * 1.2 + (i % 6 == 0 ? 14 : 6);
      canvas.drawLine(
        Offset(math.cos(a) * inner, math.sin(a) * inner),
        Offset(math.cos(a) * outer, math.sin(a) * outer),
        tickPaint,
      );
    }
    canvas.restore();

    // Radial sound bars (96). Length follows the smoothed energy level,
    // which tracks the real mic level while listening.
    final barPaint = Paint()
      ..color = col(0.95)
      ..strokeWidth = 2.4
      ..strokeCap = StrokeCap.round;
    for (var i = 0; i < 96; i++) {
      final a = i / 96 * math.pi * 2;
      final r0 = r * 0.34;
      final len = r * 0.26 * lvl *
              (0.35 +
                  0.65 *
                      (math.sin(i * 0.7 + t * 6) * math.cos(i * 0.31 - t * 3))
                          .abs()) +
          2;
      canvas.drawLine(
        Offset(cx + math.cos(a) * r0, cy + math.sin(a) * r0),
        Offset(cx + math.cos(a) * (r0 + len), cy + math.sin(a) * (r0 + len)),
        barPaint,
      );
    }

    // Rotating triangle kernel.
    canvas.save();
    canvas.translate(cx, cy);
    canvas.rotate(t * 0.9 * spin);
    final tri = Path();
    for (var i = 0; i < 3; i++) {
      final a = i / 3 * math.pi * 2 - math.pi / 2;
      final p = Offset(math.cos(a) * r * 0.2, math.sin(a) * r * 0.2);
      if (i == 0) {
        tri.moveTo(p.dx, p.dy);
      } else {
        tri.lineTo(p.dx, p.dy);
      }
    }
    tri.close();
    canvas.drawPath(
      tri,
      Paint()
        ..color = col(1)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2,
    );
    canvas.restore();

    // State-change shockwaves.
    paintShocks(canvas, c, r, t);

    // White-hot core (HTML FX layer `cg` gradient).
    final cr = r * 0.3 * (1 + lvl);
    final core = Paint()
      ..shader = RadialGradient(
        colors: [
          Colors.white.withValues(alpha: 0.95),
          col(0.6),
          col(0),
        ],
        stops: const [0.0, 0.3, 1.0],
      ).createShader(Rect.fromCircle(center: c, radius: cr))
      ..style = PaintingStyle.fill;
    canvas.drawCircle(c, cr, core);
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

  /// Circle with a repeating multi-length dash pattern, e.g. HTML
  /// `[R*.9, R*.25, R*.12, R*.25]` (dash, gap, dash, gap).
  void _patternCircle(Canvas canvas, Offset c, double radius, double width,
      Color color, List<double> pattern, double rotation) {
    final circ = 2 * math.pi * radius;
    final total = pattern.reduce((a, b) => a + b);
    final n = (circ / total).floor();
    final p = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = width;
    var acc = 0.0;
    final offsets = <double>[];
    for (final seg in pattern) {
      offsets.add(acc);
      acc += seg;
    }
    for (var i = 0; i < n; i++) {
      final base = rotation + i * (total / circ) * math.pi * 2;
      // Dashes are pattern[0] and pattern[2].
      for (var d = 0; d < pattern.length; d += 2) {
        canvas.drawArc(
            Rect.fromCircle(center: c, radius: radius),
            base + (offsets[d] / circ) * math.pi * 2,
            (pattern[d] / circ) * math.pi * 2,
            false,
            p);
      }
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

/// FX layer (HTML `#fx` canvas): orbiting trail particles, radar
/// sweep, hex data ring, orbiting satellites, horizontal lens flare.
class FxPainter extends _HudPainter {
  FxPainter({
    required super.tick,
    required super.mode,
    required super.wakeMode,
    required super.micLevel,
  });

  final _rnd = math.Random();
  final List<_FxParticle> _pts =
      List.generate(80, (_) => _FxParticle.random(math.Random()));
  List<String> _hex = List.generate(36, (_) => '00');
  final List<TextPainter> _hexPainters = [];
  double _lastHex = -10;

  String _hexByte() => _rnd.nextInt(256).toRadixString(16).padLeft(2, '0').toUpperCase();

  void _regenHex(double hueNow) {
    _hex = List.generate(36, (_) => _hexByte());
    _hexPainters
      ..clear()
      ..addAll(_hex.map((s) {
        final tp = TextPainter(
          text: TextSpan(
            text: s,
            style: TextStyle(
              fontSize: 9,
              fontFamily: 'monospace',
              color: hueColor(hueNow, 0.45),
            ),
          ),
          textAlign: TextAlign.center,
          textDirection: TextDirection.ltr,
        )..layout();
        return tp;
      }));
  }

  @override
  void paint(Canvas canvas, Size size) {
    final t = tick.value;
    step(t);
    final m = mode();
    final listening = m == HudMode.listening;
    final speaking = m == HudMode.speaking;
    final thinking = m == HudMode.thinking;
    final cx = size.width / 2;
    final cy = size.height / 2;
    final c = Offset(cx, cy);
    final r = math.min(size.width, size.height) * 0.36;
    final maxR = math.max(size.width, size.height) * 0.62;
    final lvl = level;
    Color col(double a) => hueColor(hue, a);

    // Trail particles: converge while listening, diffuse while speaking.
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
            ..color = col(0.1 + p.z * 0.5)
            ..strokeWidth = 0.4 + p.z * 1.4,
        );
      }
      p.px = x;
      p.py = y;
    }

    // Radar sweep (HTML conic gradient wedge + radial line).
    final sa = t * 1.2 * spin;
    final sweep = Paint()
      ..shader = SweepGradient(
        colors: [col(0), col(0), col(0.3)],
        stops: const [0.0, 0.84, 1.0],
        transform: GradientRotation(sa),
      ).createShader(Rect.fromCircle(center: c, radius: r * 1.18))
      ..style = PaintingStyle.fill;
    canvas.drawCircle(c, r * 1.18, sweep);
    canvas.drawLine(
      Offset(cx + math.cos(sa) * r * 0.36, cy + math.sin(sa) * r * 0.36),
      Offset(cx + math.cos(sa) * r * 1.18, cy + math.sin(sa) * r * 1.18),
      Paint()
        ..color = col(0.7)
        ..strokeWidth = 1.5,
    );

    // Hex data ring: fast refresh while thinking (HTML .1s vs 1.8s).
    if ((thinking && t - _lastHex > 0.1) || t - _lastHex > 1.8) {
      _lastHex = t;
      _regenHex(hue);
    }
    for (var i = 0; i < _hexPainters.length; i++) {
      final tp = _hexPainters[i];
      canvas.save();
      canvas.translate(cx, cy);
      canvas.rotate(i / 36 * math.pi * 2 + t * 0.08 * spin);
      tp.paint(canvas, Offset(-tp.width / 2, -r * 1.36));
      canvas.restore();
    }

    // Orbiting satellites with arc trails.
    const sats = [
      (1.3, 0.9, 0.0),
      (1.42, -0.6, 2.0),
      (1.24, 1.4, 4.0),
    ];
    for (final (k, sp, ph) in sats) {
      final a = t * sp * spin + ph;
      final x = cx + math.cos(a) * r * k;
      final y = cy + math.sin(a) * r * k;
      canvas.drawArc(
        Rect.fromCircle(center: c, radius: r * k),
        sp > 0 ? a - 0.8 : a,
        0.8,
        false,
        Paint()
          ..color = col(0.4)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.4,
      );
      final dotPaint = Paint()..color = Colors.white;
      canvas.drawCircle(Offset(x, y), 2.6 * 2.2, dotPaint..color = col(0.25));
      canvas.drawCircle(Offset(x, y), 2.6, dotPaint..color = Colors.white);
    }

    // State-change shockwaves (same as reactor layer).
    paintShocks(canvas, c, r, t);

    // Horizontal lens flare.
    final flare = Paint()
      ..shader = LinearGradient(
        colors: [col(0), col(0.3 + lvl * 0.7), col(0)],
        stops: const [0.0, 0.5, 1.0],
      ).createShader(Rect.fromLTWH(0, cy - 1, size.width, 2))
      ..style = PaintingStyle.fill;
    canvas.drawRect(Rect.fromLTWH(0, cy - 1, size.width, 2), flare);
  }
}

/// Static background: radial gradient + perspective grid with radial
/// fade + scanlines (HTML `body`, `body:before`, `body:after`).
class GridBackgroundPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    // Deep radial background: #02202c at 50%/45% -> #00080e -> #000.
    final bg = Paint()
      ..shader = RadialGradient(
        center: const Alignment(0.0, -0.1),
        radius: 1.1,
        colors: const [
          Color(0xFF02202C),
          Color(0xFF00080E),
          Color(0xFF000000),
        ],
        stops: const [0.0, 0.6, 1.0],
      ).createShader(Offset.zero & size)
      ..style = PaintingStyle.fill;
    canvas.drawRect(Offset.zero & size, bg);

    // Perspective grid, 40px cells, hue 190 at .18 (HTML `--cd`).
    final gridPaint = Paint()
      ..color = hueColor(190, 0.18)
      ..strokeWidth = 1;
    final gridPath = Path();
    for (var x = 0.0; x <= size.width; x += 40) {
      gridPath.moveTo(x, 0);
      gridPath.lineTo(x, size.height);
    }
    for (var y = 0.0; y <= size.height; y += 40) {
      gridPath.moveTo(0, y);
      gridPath.lineTo(size.width, y);
    }
    canvas.drawPath(gridPath, gridPaint);

    // Radial mask: fade the grid out toward the edges (HTML mask).
    final fade = Paint()
      ..shader = RadialGradient(
        center: const Alignment(0.0, -0.1),
        radius: 0.95,
        colors: [
          hudDeep.withValues(alpha: 0),
          hudDeep.withValues(alpha: 0.85),
        ],
        stops: const [0.0, 0.75],
      ).createShader(Offset.zero & size)
      ..style = PaintingStyle.fill;
    canvas.drawRect(Offset.zero & size, fade);

    // Scanlines: 1px black at .18 every 3px (HTML `body:after`).
    final scanPaint = Paint()
      ..color = Colors.black.withValues(alpha: 0.18)
      ..strokeWidth = 1;
    final scanPath = Path();
    for (var y = 0.0; y <= size.height; y += 3) {
      scanPath.moveTo(0, y);
      scanPath.lineTo(size.width, y);
    }
    canvas.drawPath(scanPath, scanPaint);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
