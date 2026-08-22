# Quantum Tools

NeoForge port of the RFTools Builder and Quarry systems, with the Quantum Tools
Constructor, schematic placement, import and material-management workflow.

## Requirements

- Minecraft 26.1.2
- NeoForge 26.1.2.95 or newer in the 26.1.2 line
- Java 25

## Development

Use Gradle with Java 25:

```text
gradle build
```

The mod artifact is generated in `build/libs`. User schematic files are read
from the local `schematics` directory at runtime; uploaded server copies and
client preview cache directories are managed internally by the mod.

## Supported schematic formats

- Vanilla/Create structure NBT (`.nbt`)
- Sponge/WorldEdit (`.schem`)
- Litematica (`.litematic`)
- Legacy MCEdit/Schematica (`.schematic`)

## License

MIT. Original RFTools authorship is credited in the NeoForge mod manifest.
