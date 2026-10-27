# NOTICE — Moss

Moss is derived from **Andee** by KUAFUAI, licensed under the Apache License
2.0 with additional conditions (see `LICENSE` and `LICENSE-ANDEE`). The
original copyright headers are retained in the source files; files
substantively modified for Moss carry a `// Moss:` note at the top.

## What was changed for Moss

- Package renamed `net.kuafuai.andee` → `com.moss.app`; app name Moss.
- Removed: the face/character system (fullscreen face, expression picker,
  mood tags, look personas), the robot-dog subsystem, the hub link
  (inbound WebSocket server, outbound hub client, call-extension registry),
  the CodeFlying market tools, and the version self-report probe that
  contacted the author's update host.
- The floating ball renderer is kept as a neutral orb: nothing in Moss sets
  an expression or a persona, and the model is never instructed to emit
  mood tags.
- The local-brain, notebook, scheduler, scenes, notification triage,
  meeting recorder, pages, vault, and device tools are otherwise carried
  over as written.

## Distribution limits (Andee license, Part A)

- Personal use on your own devices is permitted without further agreement.
- Distributing Moss to end users must not remove, replace, or obscure
  Andee's product name, icon, or copyright notices as displayed to the
  user (no white-labelling).
- Shipping Moss inside another product offered to others needs prior
  written authorisation from KUAFUAI.
- Operating it as a service driving third parties' devices (multi-tenant
  hosting) needs written authorisation.
- Andee's name, logo, and product identity are not licensed for use as
  Moss's brand beyond reasonable attribution of origin.
