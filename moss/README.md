# Moss

A personal on-device agent, derived from [Andee](https://github.com/kuafuai/andee)
by KUAFUAI (Apache 2.0 + additional conditions — see `LICENSE`, `LICENSE-ANDEE`,
and `NOTICE.md`).

Moss runs the agent loop on the device against a model you configure. It has
no face/character system, no robot-dog support, no hub link, no market tools,
and no update phone-home: the version self-report probe was removed, and
nothing in Moss reports the device to the author's servers.

## Status

Scaffold only: the Andee tree was copied, renamed to `com.moss.app`, and
stripped as described in `NOTICE.md`. It has **not** been compiled or run on
a device yet. Builds are triggered by `moss-v*` tags (see
`.github/workflows/build-moss-apk.yml`) and only on an explicit order.

## Configure

The brain, voice, and model endpoints are configured in the app's Settings,
as in Andee. No account, no vendor backend: the conversation goes to the
model endpoint you configure, and the notebook never leaves the device.
