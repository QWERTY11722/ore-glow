# Exposed Ore Glow (Fabric, Minecraft 26.2)

Client-side mod. Draws a glowing, see-through box around these ores **only when at least one of their 6 sides touches air** (air, cave air, or void air — water/lava don't count):

| Ore | Glow color |
|---|---|
| Diamond Ore | Cyan |
| Deepslate Diamond Ore | Cyan |
| Deepslate Emerald Ore | Green |
| Deepslate Coal Ore | Grey |

The boxes render with depth testing turned off, so they're visible through any blocks. Press **H** to toggle (rebindable under Controls → Exposed Ore Glow).

## Build

Requires **JDK 25**.

```
./gradlew build        # Windows: gradlew.bat build
```

The jar lands in `build/libs/exposed-ore-glow-1.0.0+26.2.jar`. Drop it into your `mods` folder together with Fabric API for 26.2 and run with Fabric Loader 0.19+.

To test from the project: `./gradlew runClient`.

## Tweaks

- Scan radius: `OreScanner.RADIUS_CHUNKS` (default 6 chunks ≈ 96 blocks).
- Glow strength: `OreHighlightClient.GLOW_ALPHA`.
- Colors / which ores: `OreScanner.colorFor`.

The scanner sweeps the loaded chunks around you a few per tick, so newly exposed ore (e.g. after you mine next to it) lights up within about a second.

Note: like any X-ray-style mod, this will be treated as cheating on most multiplayer servers.
