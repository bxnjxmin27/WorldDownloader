# World Downloader²

A client-side Fabric mod that saves worlds from multiplayer servers (and singleplayer) as a normal, playable singleplayer save — including chests and entities.

[![Modrinth](https://img.shields.io/modrinth/dt/worlddownloader?logo=modrinth&label=Modrinth)](https://modrinth.com/mod/worlddownloader)

## Features

- **Records chunks straight from the server** while you walk around — already loaded chunks are captured the moment you start recording
- **Writes real Anvil region files** (`.mca`), no external tools needed
- **Playable world**: generates `level.dat` and player data, ready to open
- **Chest & container contents** are saved when you open them (double chests included)
- **Entities** are exported too
- **Server resource pack** is exported as the world's `resources.zip`
- **Downloaded Worlds** tab in the world selection screen
- Auto-start recording on join and auto-export on leave (configurable)
- Settings screen via [Mod Menu](https://modrinth.com/mod/modmenu)
- Translated into 12 languages

## Usage

| Key | Action |
|-----|--------|
| `I` | Start / stop recording |
| `J` | Export the world |
| `K` | Clear the cache |

Exports land in `.minecraft/downloaded_worlds/Export_<date>/` and show up under **Downloaded Worlds** in the world selection screen.

## Requirements

- Minecraft 1.21.11
- Fabric Loader ≥ 0.18.4
- Fabric API
- Mod Menu (optional, for the settings screen)

## Building

```bash
./gradlew build
```

The jar ends up in `build/libs/`. To copy it straight into a mods folder, set `wdModsDir=/path/to/.minecraft/mods` in `~/.gradle/gradle.properties` and run `./gradlew installMod`.

## License

[MIT](LICENSE) © benjamy
