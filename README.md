# SchematicDropper

A Meteor Client addon for Minecraft 1.21.1 that drops the **exact** material
quantities a build requires.

It reads a Litematica schematic, a `.litematic` / `.schem` / `.schematic` file, or
a plain material list, works out how many of each item is needed, and then takes
those items from the creative inventory and drops them - at the player's feet, or
toward the entity they are looking at.

Built for creative mode. Made to be exact: it never drops a full stack when one
item is required, and it never counts an item the server did not actually create.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft
   1.21.1 and [Meteor Client](https://meteorclient.com/) for 1.21.1.
2. Drop `schematic-dropper-1.0.0+mc1.21.1.jar` into `.minecraft/mods/`.
3. Optional: add [Litematica](https://www.curseforge.com/minecraft/mc-mods/litematica)
   for the "selected placement" input source. The addon works without it.

Requires Java 21.

## Dependencies

| Dependency | Version | Required |
|---|---|---|
| Minecraft | 1.21.1 | yes |
| Fabric Loader | 0.16.x+ | yes |
| Meteor Client | 0.5.8+ | yes |
| Fabric API | 1.21.1 | yes |
| Litematica | 1.21.1 build | no |

## Quick start

1. Open the Meteor GUI, find **SchematicDropper** under the *Schematic Dropper* category.
2. Click **Open Material Manager** in the module's settings panel.
3. Import a schematic, a schematic file, or a material list.
4. Pick a target mode and a speed.
5. Press **Start**.

## Input sources

### Litematica

Reads the placement currently selected in Litematica. Litematica's own material
list is used rather than walking the block container, so waterlogged blocks,
doors, slabs and other multi-item or non-obvious blocks are converted the same way
Litematica converts them. Air is never counted.

Only the regions the placement has **enabled** are read. A region switched off in
Litematica is not going to be built, so it is not counted here.

Litematica is optional. Nothing in the addon references a Litematica type until
the mod is confirmed present, so the addon loads fine without it and the
Litematica source reports plainly that the mod is missing.

### Schematic files

`.litematic`, `.schem` and `.schematic` are supported. Parsing happens on a
background thread; the result is handed to the client thread when it is ready, so
a large schematic does not stall the game.

### Material lists

`.txt`, `.json` and `.csv`.

```
minecraft:stone, 128
minecraft:dirt, 64
minecraft:glass, 32
```

```json
{
  "materials": [
    {"id": "minecraft:stone", "count": 128},
    {"id": "minecraft:dirt", "count": 64}
  ]
}
```

Identifiers are validated, duplicates are merged, and non-positive or malformed
quantities are rejected with a message that says which line was wrong.

## Drop targeting

| Mode | Behaviour |
|---|---|
| **Feet** | The item is dropped at the player's position. |
| **Looked-at entity** | The camera is raycast; the item is thrown toward the living entity under the crosshair. |

Targeting range is configurable. When no valid target is found the run reports it
and does not fall back to another mode or another entity. An optional target lock
keeps a chosen entity across ticks, and an optional notification announces when
the target changes.

The look direction is set through Meteor's rotation queue rather than written onto
the player object, and it is refreshed on every tick rather than only when a drop
is sent. The client sends its movement packet before this module runs, so a
rotation set on the same tick as a drop would only reach the server a tick later -
after the drop had already been thrown at the old angle.

## Exact quantities

Stone 130 / Dirt 70 / Glass 17 becomes:

```
Stone   64 + 64 + 2
Dirt    64 + 6
Glass   17
```

Partial stacks are handled, quantities are never exceeded, and a one-item
requirement never becomes a full stack.

### Confirmed drops, not assumed drops

This is the part that matters on a multiplayer server.

A creative drop packet is a *request*. The server decides whether to honour it. It
may refuse because the player is not in creative, because the item is unknown,
because its anti-spam budget is exhausted, or because a plugin blocks it. The
client is told none of this.

So the addon never counts a drop when it sends the packet. It records the request
in a ledger and waits. Only when the server actually spawns the item entity - which
the client can see - does the ledger confirm it and the progress figures move.

Consequences of that design:

- **Progress is real.** A figure of 130/130 means 130 items were observed in the
  world, not 130 packets were sent.
- **A refused drop does not silently vanish.** It stays unconfirmed and the run
  stops with a message rather than pretending the material is done.
- **A duplicated event cannot inflate progress.** The ledger will not credit more
  items than were sent, so one item entity reported twice is counted once.
- **A stop does not overstate.** Unconfirmed requests are discarded, never
  counted.
- **Someone else's drop is not ours.** Only item entities that spawn within a few
  blocks of the player can confirm a request, so a nearby player dropping the same
  item does not move the progress figures.

The server's own limit is respected: it performs ten creative drops per tick, or
two hundred per second. The scheduler clamps to that instead of firing into a
budget that is already spent.

### Retries

A drop that is never confirmed is **not** resent by default. A server that is
merely slow would receive a second drop and the world would end up with duplicates.
The run stops instead, which is the safe failure. Resending can be enabled
deliberately with the `max-attempts` setting.

## Creative inventory

Two strategies, both real server-side actions:

| Strategy | Packets | Notes |
|---|---|---|
| **Direct drop** (default) | 1 | One creative drop packet. The server validates the item and performs the drop. |
| **Hotbar then drop** | 2 | The item is placed in a hotbar slot, then thrown with the vanilla drop action. |

The hotbar route needs one tick between the give and the throw: the client sends
its selected-slot packet at the start of a tick, so a throw issued in the same tick
would be executed against the previous slot and would throw the wrong item. The
handler sequences the two ticks itself.

There is no packet spam. Drops are paced by the speed settings and capped by what
the server will actually accept.

## Speed controls

Items per second, maximum drops per tick, minimum delay, randomised delay, maximum
per drop, confirmation timeout, pause, resume and stop.

## Material manager GUI

Per material: name, required, dropped, remaining, progress percentage, and a
missing / stalled status. Filtering by name, sorting, and a "only remaining"
toggle. Buttons for importing, refreshing, starting, pausing, resuming, stopping,
clearing the list and resetting progress.

## HUD

```
SchematicDropper
-------------------------
Stone       130 / 130
Dirt          64 / 70
Glass         17 / 17
-------------------------
Total: 211 / 217
Progress: 97.2%
Status: Running
Target: Player / Feet
```

Configurable position, scale, background opacity, compact mode, "only remaining"
mode and a target line toggle.

## Configuration

Input source, selected schematic, material list, target mode, range, drop speed,
inventory strategy, HUD settings, filtering and the current material list are all
persisted by Meteor's own config system.

A run is **never** restarted automatically. If the connection or the world changes
mid-run, the task stops, marks itself as needing an explicit restart, and says so.
Resuming is a deliberate action.

## Reliability

- Parsing runs off the client thread; results are applied on it.
- A second `Start` while a run is active is refused, so a double-click cannot
  launch two competing operations.
- Disabling the module stops the task, clears the lock and removes the HUD.
- Stale parse results are discarded by generation counter, so an abandoned import
  cannot overwrite a newer one.
- Null and disconnect checks throughout; the module is inert when no world or
  player is present.

## Tests

```
./gradlew test
```

126 tests covering material list parsing, duplicate merging, identifier
validation, stack arithmetic, partial stacks, zero quantities, missing materials,
progress tracking, cancellation, resume, duplicate-drop prevention, confirmation
accounting and the exact-quantity contract end to end.

The scheduler keeps no Minecraft types - everything that touches the game goes
through a small `Actions` interface - so the whole exact-quantity path is tested
without a running game.

## Limitations

- Creative mode only. The server ignores creative inventory packets from a
  non-creative player.
- The "looked-at entity" mode requires a living entity in range; it will not aim
  at blocks.
- `.schematic` (the legacy MCEdit format) support depends on what the file
  contains; the modern formats are the reliable path.
- Litematica integration is only as accurate as Litematica's own material list,
  which is the point - it is the same list Litematica shows.

## Building

```
./gradlew build
```

The jar lands in `build/libs/`. Requires a JDK 21 toolchain.

## Licence

MIT.
