import 'dart:math' as math;

import 'package:flutter/material.dart';

/// Original vector HUD painters. Cinematic Jarvis-style feel drawn in code:
/// concentric reactor rings, orbiting particle sphere, waveform, scan line,
/// perspective-grid background, HUD corner brackets, custom mic glyph.
/// No movie stills, no copyrighted artwork.

const hudCyan = Color(0xFF00E5FF);
const hudBlue = Color(0xFF2E9BFF);
const hudDeep = Color(0xFF020A14);

class ReactorPainter extends CustomPainter {
  final double progress; // 0..1 boot progress
  final double tick; // animation tick
  final bool speaking;
  final bool thinking;
  final Color accent; // state color: cyan idle, green listening, orange thinking
  final bool ripple; // speaking/listening: expanding sound-wave rings

  ReactorPainter({
    required this.progress,
    required this.tick,
    this.speaking = false,
    this.thinking = false,
    this.accent = hudCyan,
    this.ripple = false,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final c = Offset(size.width / 2, size.height / 2);
    final r = math.min(size.width, size.height) / 2;
    final coreR = r * 0.28 * (0.85 + 0.15 * math.sin(tick * 2.2));

    // Outer glow
    final glow = Paint()
      ..color = accent.withOpacity(0.10 + 0.06 * math.sin(tick * 1.7))
      ..style = PaintingStyle.fill;
    canvas.drawCircle(c, r * 0.98, glow);

    // Sound-wave ripples (speaking / listening)
    if (ripple) {
      for (var i = 0; i < 2; i++) {
        final phase = (tick * 0.55 + i * 0.5) % 1;
        final ringPaint = Paint()
          ..color = accent.withOpacity(0.35 * (1 - phase))
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.5;
        canvas.drawCircle(c, r * (0.72 + phase * 0.42), ringPaint);
      }
    }

    // Rotating dashed orbit rings
    for (var i = 0; i < 3; i++) {
      final rr = r * (0.96 - i * 0.13);
      final p = Paint()
        ..color = accent.withOpacity(0.55 - i * 0.12)
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

    // Counter-rotating outer tick ring
    final counterPaint = Paint()
      ..color = accent.withOpacity(0.5)
      ..strokeWidth = 1.2;
    for (var i = 0; i < 24; i++) {
      final a = -tick * 0.5 + i * 2 * math.pi / 24;
      final inner = r * 0.90;
      final outer = r * 0.95;
      canvas.drawLine(
        c + Offset(math.cos(a) * inner, math.sin(a) * inner),
        c + Offset(math.cos(a) * outer, math.sin(a) * outer),
        counterPaint,
      );
    }

    // Tick marks
    final tickPaint = Paint()
      ..color = accent.withOpacity(0.8)
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
      ..color = accent
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
          colors: [accent.withOpacity(0.0), accent.withOpacity(0.45)],
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
      ..color = (speaking ? Colors.white : accent)
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
    // Core coils (10 small coils around core, vector-drawn)
    final coil = Paint()
      ..color = hudBlue.withOpacity(0.9)
      ..style = PaintingStyle.fill;
    for (var i = 0; i < 10; i++) {
      final a = i * 2 * math.pi / 10 + tick * 0.1;
      final pos =
          c + Offset(math.cos(a) * coreR * 1.35, math.sin(a) * coreR * 1.35);
      canvas.drawCircle(pos, coreR * 0.14, coil);
    }
  }

  @override
  bool shouldRepaint(covariant ReactorPainter old) =>
      old.progress != progress ||
      old.tick != tick ||
      old.speaking != speaking ||
      old.thinking != thinking ||
      old.accent != accent ||
      old.ripple != ripple;
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
    final rotY = tick * (active ? 0.9 : 0.45);
    final rotX = 0.6 + 0.12 * math.sin(tick * 0.4);
    // Active: particles spread outward + breathe.
    final spread = active ? 1.18 + 0.06 * math.sin(tick * 3) : 1.0;
    final cy = math.cos(rotY), sy = math.sin(rotY);
    final cx = math.cos(rotX), sx = math.sin(rotX);
    for (final p in points) {
      // rotate Y then X
      final x1 = p.x * cy + p.z * sy;
      final z1 = -p.x * sy + p.z * cy;
      final y2 = p.y * cx - z1 * sx;
      final z2 = p.y * sx + z1 * cx;
      final depth = (z2 + 1) / 2; // 0 near, 1 far
      final sx2 = c.dx + x1 * r * spread;
      final sy2 = c.dy + y2 * r * spread;
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
    // Tilted gyro ring
    canvas.drawOval(
        Rect.fromCenter(center: c, width: r * 2.1, height: r * 1.1), ring);
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

/// Voice waveform: animated energy columns with gradient + glowing tip.
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
      final rect = Rect.fromCenter(
          center: Offset(x + bw * 0.3, size.height / 2),
          width: bw * 0.6,
          height: h);
      final rrect = RRect.fromRectAndRadius(rect, const Radius.circular(3));
      final shader = LinearGradient(
        colors: [
          hudBlue.withOpacity(0.35),
          hudCyan,
          Colors.white,
        ],
        stops: const [0.0, 0.7, 1.0],
        begin: Alignment.bottomCenter,
        end: Alignment.topCenter,
      ).createShader(rect);
      canvas.drawRRect(
          rrect,
          Paint()
            ..shader = shader
            ..style = PaintingStyle.fill);
      // Glowing tip
      canvas.drawCircle(
          Offset(x + bw * 0.3, size.height / 2 - h / 2),
          1.6,
          Paint()..color = Colors.white.withOpacity(0.55 + 0.35 * v));
    }
  }

  @override
  bool shouldRepaint(covariant WaveformPainter old) =>
      old.tick != tick || old.amplitude != amplitude;
}

/// Full-screen animated background: perspective grid floor + drifting
/// dust particles + top light beam. Drawn under the main UI.
class HudBackgroundPainter extends CustomPainter {
  final double tick;
  final List<DustPoint> dust;

  HudBackgroundPainter({required this.tick, required this.dust});

  @override
  void paint(Canvas canvas, Size size) {
    final horizonY = size.height * 0.32;
    final cx = size.width / 2;

    // Top light beam (trapezoid, fading downward)
    final beamPath = Path()
      ..moveTo(cx - 28, 0)
      ..lineTo(cx + 28, 0)
      ..lineTo(cx + 170, size.height * 0.55)
      ..lineTo(cx - 170, size.height * 0.55)
      ..close();
    final beamRect = Rect.fromLTWH(0, 0, size.width, size.height * 0.55);
    canvas.drawPath(
        beamPath,
        Paint()
          ..shader = LinearGradient(
            colors: [
              hudCyan.withOpacity(0.10),
              hudCyan.withOpacity(0.0),
            ],
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
          ).createShader(beamRect));

    // Perspective grid floor: converging verticals
    final gridPaint = Paint()
      ..color = hudCyan.withOpacity(0.07)
      ..strokeWidth = 1;
    for (var i = -10; i <= 10; i++) {
      canvas.drawLine(
        Offset(cx + i * 6, horizonY),
        Offset(cx + i * size.width * 0.12, size.height),
        gridPaint,
      );
    }
    // Horizontal lines, scrolling downward with perspective spacing
    final scroll = (tick * 0.22) % 1;
    for (var i = 0; i < 9; i++) {
      final t = (i + scroll) / 9;
      final y = horizonY + (size.height - horizonY) * t * t;
      canvas.drawLine(
        Offset(0, y),
        Offset(size.width, y),
        Paint()
          ..color = hudCyan.withOpacity(0.04 + 0.06 * t)
          ..strokeWidth = 1,
      );
    }

    // Drifting dust particles
    for (final p in dust) {
      final yy = ((p.y - tick * 0.015 * (0.5 + p.phase)) % 1.0) * size.height;
      final xx = p.x * size.width + math.sin(tick + p.phase * 6) * 6;
      final twinkle = 0.5 + 0.5 * math.sin(tick * 2 + p.phase * 10);
      final color = p.phase > 0.7 ? Colors.white : hudCyan;
      canvas.drawCircle(
          Offset(xx, yy),
          p.size,
          Paint()..color = color.withOpacity(0.12 + 0.35 * twinkle));
    }
  }

  @override
  bool shouldRepaint(covariant HudBackgroundPainter old) =>
      old.tick != tick || old.dust != dust;
}

class DustPoint {
  final double x, y, size, phase; // x,y in 0..1
  const DustPoint(this.x, this.y, this.size, this.phase);
}

List<DustPoint> makeDustPoints(int n, int seed) {
  final rnd = math.Random(seed);
  final out = <DustPoint>[];
  for (var i = 0; i < n; i++) {
    out.add(DustPoint(
      rnd.nextDouble(),
      rnd.nextDouble(),
      0.8 + rnd.nextDouble() * 1.4,
      rnd.nextDouble(),
    ));
  }
  return out;
}

/// HUD corner brackets: 4 L-shaped glowing corners around a panel.
class HudCornersPainter extends CustomPainter {
  final Color color;
  final double strokeWidth;
  final double len;

  const HudCornersPainter({
    this.color = hudCyan,
    this.strokeWidth = 1.6,
    this.len = 14,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final p = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..strokeCap = StrokeCap.round;
    final l = len;
    canvas.drawPath(
        Path()
          ..moveTo(0, l)
          ..lineTo(0, 0)
          ..lineTo(l, 0),
        p);
    canvas.drawPath(
        Path()
          ..moveTo(size.width - l, 0)
          ..lineTo(size.width, 0)
          ..lineTo(size.width, l),
        p);
    canvas.drawPath(
        Path()
          ..moveTo(0, size.height - l)
          ..lineTo(0, size.height)
          ..lineTo(l, size.height),
        p);
    canvas.drawPath(
        Path()
          ..moveTo(size.width - l, size.height)
          ..lineTo(size.width, size.height)
          ..lineTo(size.width, size.height - l),
        p);
  }

  @override
  bool shouldRepaint(covariant HudCornersPainter old) =>
      old.color != color || old.len != len;
}

/// Custom mic glyph: capsule + arc + stand, with soft glow behind.
/// Listening: animated equalizer bars instead of the mic.
class MicGlyphPainter extends CustomPainter {
  final bool listening;
  final double tick;
  final Color color;

  const MicGlyphPainter({
    required this.listening,
    required this.tick,
    this.color = Colors.white,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final c = Offset(size.width / 2, size.height / 2);
    if (listening) {
      for (var i = 0; i < 4; i++) {
        final h = size.height *
            (0.35 + 0.3 * (0.5 + 0.5 * math.sin(tick * 9 + i * 1.3)));
        final x = size.width * (0.2 + i * 0.2);
        canvas.drawRRect(
            RRect.fromRectAndRadius(
                Rect.fromCenter(
                    center: Offset(x, c.dy), width: 3.2, height: h),
                const Radius.circular(1.6)),
            Paint()..color = color);
      }
      return;
    }
    // Soft glow behind the glyph
    canvas.drawCircle(
        c, size.width * 0.44, Paint()..color = hudCyan.withOpacity(0.20));
    final fill = Paint()
      ..color = color
      ..style = PaintingStyle.fill;
    final stroke = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.4
      ..strokeCap = StrokeCap.round;
    // Mic capsule
    final capW = size.width * 0.32;
    final capH = size.height * 0.44;
    canvas.drawRRect(
        RRect.fromRectAndRadius(
            Rect.fromCenter(
                center: Offset(c.dx, c.dy - size.height * 0.08),
                width: capW,
                height: capH),
            Radius.circular(capW / 2)),
        fill);
    // Arc under the capsule
    canvas.drawArc(
        Rect.fromCenter(
            center: Offset(c.dx, c.dy - size.height * 0.08),
            width: size.width * 0.62,
            height: size.height * 0.52),
        0.15 * math.pi,
        0.7 * math.pi,
        false,
        stroke);
    // Stand + base
    canvas.drawLine(Offset(c.dx, c.dy + size.height * 0.20),
        Offset(c.dx, c.dy + size.height * 0.34), stroke);
    canvas.drawLine(
        Offset(c.dx - size.width * 0.18, c.dy + size.height * 0.34),
        Offset(c.dx + size.width * 0.18, c.dy + size.height * 0.34),
        stroke);
  }

  @override
  bool shouldRepaint(covariant MicGlyphPainter old) =>
      old.listening != listening || old.tick != tick || old.color != color;
}
