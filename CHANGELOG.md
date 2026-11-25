# Changelog

All notable changes to this project are written here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the version numbers follow [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-25

First public release.

### Added

- Live view of the FLIR ONE Pro (USB-C, `09CB:1996`) through an own USB driver written on top of Android's USB host API:
  no vendor SDK and no native libraries. About 9.7 frames per second.
- Display modes: thermal, visible light and MSX (edges of the visible picture laid over the thermal one), with six palettes
  (Iron, Rainbow, White hot, Black hot, Lava, Arctic).
- Temperature measurement: a draggable crosshair, markers for the hottest and coldest point, a temperature scale,
  adjustable emissivity and reflected temperature.
- In-app calibration against reference temperatures (offset, or offset and sensitivity), kept between launches.
- Photo and video capture to the gallery (`Pictures/FLIR ONE Pro`, `Movies/FLIR ONE Pro`); the video shows exactly what is
  on the screen, with the temperature marks.
- Screen rotation, mirror, fill-the-screen mode, camera battery level, hideable controls.
- English and Ukrainian interface (follows the system language, can be switched in the settings).
- Experimental manual shutter calibration (the command that triggers it on demand is undocumented; see the README).
- Signed release APK with a SHA-256 checksum, built by GitHub Actions.

### Known limitations

- Verified on one camera (FLIR ONE Pro, sled firmware 1.0.27) and one phone (Honor 10, Android 10). Other models and
  firmware versions are untested.
- Not a certified instrument: about ±1 K between −15 and +35 °C compared with a thermocouple, up to about ±3 K in general;
  hot surfaces above about 60 °C are not verified.
- The direction of the picture in landscape orientation has not been verified on a real device.

[1.0.0]: https://github.com/rzn357/FLIRONEProApp/releases/tag/v1.0.0
