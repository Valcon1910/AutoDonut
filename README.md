# AutoDonut

Client-side Fabric mod for the **Donut SMP** (`donutsmp.net`).
Minecraft **26.3**, Fabric Loader **0.19.5**, Java **25**.

## Features

### Control panel (press **K**)
- Custom-drawn panel that fades and scales in and out.
- Dark / light mode plus an **Appearance** page with named colour styles and accent colours; changes blend smoothly.
- Settings are saved to `config/autodonut.json`.

### Auto Auction
Add items you want sold automatically:
1. **+ Add item**, then search for the item by name.
2. Enter a **price** (`500`, `1.5k`, `2m`…), either **per item** or **per stack**.
3. Choose the **quantity**: **Exactly** (type a stack size) or **Custom** (drag the minimum and maximum handles).
4. Turn on the **Enabled** switch.

When a matching stack is in your inventory, AutoDonut selects it (moving it to the hotbar if needed),
runs `/ah sell <price>`, then puts your selected slot back.

**Safety tab** — everything is randomised so actions don't happen on a fixed rhythm:
- random delay between listings (min/max), reaction time after picking an item up
- hourly listing cap, optional random multi-minute breaks
- pauses while a menu or chat is open
- pauses if the server refuses a listing, or if the same listing keeps failing
- optionally only runs on Donut SMP

The sell command can be changed in `config/autodonut.json` (`"sellCommand": "ah sell {price}"`).

### Connect screen
When joining Donut SMP, the status after *Encrypting…* shows **AutoDonut Booting Up**.

## IntelliJ IDEA setup
1. Use **Eclipse Temurin 25** (not an "EA"/"Loom" build) for both the Project SDK and the Gradle JVM.
2. *File → Open* → select this folder, let Gradle sync.
3. Run **Minecraft Client** (or `./gradlew runClient`).

## Build
```
./gradlew build
```
Output jar: `build/libs/`.
