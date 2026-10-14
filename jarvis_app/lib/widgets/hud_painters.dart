import 'dart:math' as math;

import 'package:flutter/material.dart';

/// Original vector HUD painters. Cinematic Jarvis-style feel drawn in code:
/// concentric reactor rings, orbiting particle sphere, waveform, scan line.
/// No movie stills, no copyrighted artwork.

const hudCyan = Color(0xFF00E5FF);
const hudBlue = Color(0xFF2E9BFF);
const hudDeep = Color(0xFF020A14);

class ReactorPainter extends CustomPainter {
  final double progress; // 0..1 boot progress
  final double tick; // animation tick
  final bool speaking;
  final bool thinking;

  ReactorPainter({
    required this.progress,
    required this.tick,
    this.speaking = false,
    this.thinking = false,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final c = Offset(size.width / 2, size.height / 2);
    final r = math.min(size.width, size.height) / 2;
    final coreR = r * 0.28 * (0.85 + 0.15 * math.sin(tick * 2.2));

    // Outer glow
    final glow = Paint()
      ..color = hudCyan.withOpacity(0.10 + 0.06 * math.sin(tick * 1.7))
      ..style = PaintingStyle.fill;
    canvas.drawCircle(c, r * 0.98, glow);

    // Rotating dashed orbit rings
    for (var i = 0; i < 3; i++) {
      final rr = r * (0.96 - i * 0.13);
      final p = Paint()
        ..color = hudCyan.withOpacity(0.55 - i * 0.12)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.2;
      final segs = 28 + i * 8;
      for (var s = 0; s < segs; s++) {
        final a0 = tick * (i.isEven ? 0.35 : -0.28) + s * 2 * math.pi / segs;
        final a1 = a0 + (2 * math.pi / segs) * 0.55;
        canvas.drawArc(Rect.fromCircle(center: c, radius: rr), a0, a1 - a0,
            false, p);
      }
    }

    // Tick marks
    final tickPaint = Paint()
      ..color = hudCyan.withOpacity(0.8)
      ..strokeWidth = 1.4;
    for (var i = 0; i < 60; i++) {
      final a = i * 2 * math.pi / 60;
      final inner = r * 0.78;
      final outer = r * (i % 5 == 0 ? 0.86 : 0.82);
      canvas.drawLine(
        c + Offset(math.cos(a) * inner, math.sin(a) * inner),
        c + Offset(math.cos(a) * outer, math.sin(a) * outer),
        tickPaint,
      );
    }

    // Power arc (boot progress)
    final arcPaint = Paint()
      ..color = hudCyan
      ..style = PaintingStyle.stroke
      ..strokeWidth = 4
      ..strokeCap = StrokeCap.round;
    canvas.drawArc(
        Rect.fromCircle(center: c, radius: r * 0.68),
        -math.pi / 2,
        2 * math.pi * progress.clamp(0.0, 1.0),
        false,
        arcPaint);

    // Thinking radar sweep
    if (thinking) {
      final sweep = Paint()
        ..shader = SweepGradient(
          colors: [hudCyan.withOpacity(0.0), hudCyan.withOpacity(0.45)],
          stops: const [0.0, 1.0],
          startAngle: 0,
          endAngle: math.pi * 1.2,
          transform: GradientRotation(tick * 1.4),
        ).createShader(Rect.fromCircle(center: c, radius: r * 0.6))
        ..style = PaintingStyle.fill;
      canvas.drawCircle(c, r * 0.6, sweep);
    }

    // Core: layered glowing circles (original arc-reactor feel)
    final coreGlow = Paint()
      ..color = (speaking ? Colors.white : hudCyan)
          .withOpacity(0.28 + (speaking ? 0.12 * math.sin(tick * 9) : 0))
      ..style = PaintingStyle.fill;
    canvas.drawCircle(c, coreR * 1.9, coreGlow);
    canvas.drawCircle(
        c,
        coreR,
        Paint()
          ..color = const Color(0xFFBDF6FF)
          ..style = PaintingStyle.fill);
    canvas.drawCircle(
        c,
        coreR * 0.55,
        Paint()
          ..color = Colors.white
          ..style = PaintingStyle.fill);
    // Core triangle (10 small coils around core, vector-drawn)
    final coil = Paint()
      ..color = hudBlue.withOpacity(0.9)
      ..style = PaintingStyle.fill;
    for (var i = 0; i < 10; i++) {
      final a = i * 2 * math.pi / 10 + tick * 0.1;
      final pos = c + Offset(math.cos(a) * coreR * 1.35, math.sin(a) * coreR * 1.35);
      canvas.drawCircle(pos, coreR * 0.14, coil);
    }
  }

  @override
  bool shouldRepaint(covariant ReactorPainter old) =>
      old.progress != progress ||
      old.tick != tick ||
      old.speaking != speaking ||
      old.thinking != thinking;
}

/// 3D-projected particle sphere: points on a sphere, rotating, drawn as
/// glowing dots with depth-based size/opacity. Pure code, no assets.
class ParticleSpherePainter extends CustomPainter {
  final double tick;
  final bool active;
  final List<_P3> points;

  ParticleSpherePainter({
    required this.tick,
    required this.active,
    required this.points,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final c = Offset(size.width / 2, size.height / 2);
    final r = math.min(size.width, size.height) * 0.42;
    final rotY = tick * 0.45;
    final rotX = 0.6 + 0.12 * math.sin(tick * 0.4);
    final cy = math.cos(rotY), sy = math.sin(rotY);
    final cx = math.cos(rotX), sx = math.sin(rotX);
    for (final p in points) {
      // rotate Y then X
      final x1 = p.x * cy + p.z * sy;
      final z1 = -p.x * sy + p.z * cy;
      final y2 = p.y * cx - z1 * sx;
      final z2 = p.y * sx + z1 * cx;
      final depth = (z2 + 1) / 2; // 0 near, 1 far
      final sx2 = c.dx + x1 * r;
      final sy2 = c.dy + y2 * r;
      final dotR = (active ? 2.6 : 1.8) * (1.25 - depth * 0.6);
      canvas.drawCircle(
          Offset(sx2, sy2),
          dotR,
          Paint()..color = hudCyan.withOpacity(0.25 + (1 - depth) * 0.7));
    }
    // Orbit rings
    final ring = Paint()
      ..color = hudCyan.withOpacity(0.28)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1;
    canvas.drawOval(
        Rect.fromCenter(center: c, width: r * 2.35, height: r * 0.85), ring);
    canvas.drawOval(
        Rect.fromCenter(center: c, width: r * 2.35, height: r * 1.6), ring);
  }

  @override
  bool shouldRepaint(covariant ParticleSpherePainter old) =>
      old.tick != tick || old.active != active || old.points != points;
}

class _P3 {
  final double x, y, z;
  const _P3(this.x, this.y, this.z);
}

List<_P3> makeSpherePoints(int n, int seed) {
  final rnd = math.Random(seed);
  final out = <_P3>[];
  for (var i = 0; i < n; i++) {
    final u = rnd.nextDouble() * 2 - 1;
    final th = rnd.nextDouble() * 2 * math.pi;
    final s = math.sqrt(1 - u * u);
    out.add(_P3(s * math.cos(th), s * math.sin(th), u));
  }
  return out;
}

/// Voice waveform: animated bars, amplitude follows speaking state.
class WaveformPainter extends CustomPainter {
  final double tick;
  final double amplitude; // 0 idle, 1 loud
  WaveformPainter({required this.tick, required this.amplitude});

  @override
  void paint(Canvas canvas, Size size) {
    const n = 48;
    final bw = size.width / n;
    for (var i = 0; i < n; i++) {
      final t = i / n;
      final env = math.sin(t * math.pi); // taper at edges
      final v = (0.12 + amplitude * 0.88) *
          env *
          (0.55 + 0.45 * math.sin(tick * 6 + i * 0.55));
      final h = size.height * v.clamp(0.05, 1.0);
      final x = i * bw + bw * 0.2;
      final rect = RRect.fromRectAndRadius(
          Rect.fromCenter(
              center: Offset(x + bw * 0.3, size.height / 2),
              width: bw * 0.6,
              height: h),
          const Radius.circular(3));
      canvas.drawRRect(
          rect,
          Paint()
            ..color = hudCyan.withOpacity(0.35 + 0.6 * v)
            ..style = PaintingStyle.fill);
    }
  }

  @override
  bool shouldRepaint(covariant WaveformPainter old) =>
      old.tick != tick || old.amplitude != amplitude;
}
