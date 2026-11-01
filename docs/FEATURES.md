# SchematicDropper - feature documentation

Reference for the settings, the architecture, and the design decisions behind
them. For installation and a quick start, see the [README](../README.md).

---

## 1. Settings reference

### General

| Setting | Type | Default | Meaning |
|---|---|---|---|
| `source` | enum | Litematica placement | Where materials come from: Litematica, a schematic file, or a material list. |
| `use-selected-placement` | bool | true | Read the placement selected in Litematica rather than a named one. |
| `schematic-file` | string | empty | Path to the `.litematic` / `.schem` / `.schematic` file to read. |
| `material-list` | string | empty | Path to the `.txt` / `.json` / `.csv` material list. |
| `ignore-air` | bool | true | Skip air when counting blocks. |
| `material-order` | enum | Schematic order | The deterministic processing order. See section 2. |
| `run-mode` | enum | Strict order | How an unavailable material is handled. See section 2. |
| `inventory-strategy` | enum | Direct drop | How items leave the creative inventory. See section 5. |

### Speed

| Setting | Type | Default | Meaning |
|---|---|---|---|
| `items-per-second` | int | 128 | Target throughput. Clamped by the server's own ceiling. |
| `max-drops-per-tick` | int | 4 | Upper bound on sends in one tick. |
| `delay` | int (ms) | 0 | Minimum gap between two sends. |
| `random-delay` | int (ms) | 0 | Extra random gap, so the timing is not perfectly regular. |
| `max-per-drop` | int | 64 | Largest stack a single send may carry. |
| `confirm-timeout` | int (ticks) | 20 | How long to wait for a drop to be confirmed before treating it as lost. |
| `max-attempts` | int | 1 | Sends allowed per drop. See section 5. |
| `allow-resend` | bool | false | Resend a timed-out drop. Off, to avoid duplicates. See section 5. |
| `retry-delay` | int (ticks) | 10 | Base delay before retrying a failed operation. |
| `max-retry-delay` | int (ticks) | 100 | Ceiling for the retry backoff. |
| `max-retries` | int | 5 | Failed attempts for one material before it is blocked. |
| `revisit-blocked` | int (ticks) | 100 | How often blocked materials are revisited. |

### Targeting

| Setting | Type | Default | Meaning |
|---|---|---|---|
| `target-mode` | enum | Feet | Feet, or looked-at entity. |
| `target-range` | double | 4.5 | Maximum distance for the looked-at raycast. |
| `lock-target` | bool | false | Keep the first valid target until the run ends. |
| `notify-target-change` | bool | true | Say so in chat when the target changes. |

### HUD

| Setting | Type | Default | Meaning |
|---|---|---|---|
| `hud-enabled` | bool | true | Show the HUD element. |
| `hud-scale` | double | 1.0 | Text scale. |
| `hud-background-opacity` | double | 0.5 | Panel background opacity. |
| `hud-compact` | bool | false | Shorter lines. |
| `hud-only-remaining` | bool | false | List only materials still outstanding. |
| `hud-show-target` | bool | true | Show the current target. |

### How aiming works

A creative drop travels along the player's view vector, so "throwing at a target"
is entirely a matter of where the player is aimed. The aim goes through Meteor's
rotation queue, which rewrites the outgoing movement packet.

The ordering is the subtle part:

1. During the client tick, Minecraft sends the movement packet, carrying whatever
   rotation was queued on the *previous* tick.
2. Then this module's `TickEvent.Post` handler runs.
3. A drop packet queued in step 2 goes out at the end of the tick.

A rotation set in step 2 therefore reaches the server on the next tick, while the
drop goes out now. Rotating and dropping on the same tick would throw the items at
the old angle - at the player's feet, in practice.

`updateTargeting()` therefore aims on every tick while a run is active, not only
on the tick a drop happens. By the time a drop is dispatched, the aim set on the
previous tick is already the server's current view angle.

---

## 2. Deterministic material order

Materials are processed in a fixed order, decided once when the list is imported.
Nothing selects a material at random, and the order does not change mid-run.

| `material-order` | Result |
|---|---|
| **Schematic order** (default) | The order the Litematica material list or the schematic produced. |
| **Imported list order** | The order the `.txt` / `.json` / `.csv` file listed them in. |
| **Alphabetical** | Ascending item id, e.g. `minecraft:apple` before `minecraft:stone`. |
| **Required quantity** | Largest requirement first, ties broken by the original order. |

The chosen order is written into the saved run, so reloading a run preserves it
even if the setting has since been changed.

### Run modes

`run-mode` decides what happens when the material at the head of the queue cannot
be processed right now.

**Strict order** (default)

- The head material is finished - every stack of it - before the next is touched.
- An unavailable material is never skipped. Its position and remaining quantity
  are kept, and the scheduler waits for it.
- Later materials are not processed while it is outstanding.
- Resuming continues from its exact remaining quantity.

**Best effort**

- An unavailable material is temporarily marked `BLOCKED`.
- The other materials are processed in their original relative order.
- Blocked materials are revisited every `revisit-blocked` ticks.
- The run is not complete while any blocked quantity remains.

The documented example - Stone 128, Oak Planks 48, Glass 32, Iron Blocks 16 -
is dropped as 64 + 64 stone, then 48 oak planks, then 32 glass, then 16 iron
blocks, in that order and never interleaved.

### Multiple stacks

One material may need several stacks. They are all dropped before the scheduler
advances: 200 stone is 64 + 64 + 64 + 8, then the next material. A send already in
flight counts against the material's remaining quantity, so a slow confirmation
cannot cause an over-drop.

---

## 3. Failure recovery

A failed drop never ends the run.

Every failure is classified, recorded with its reason and tick, and turned into an
action:

| `FailureKind` | Meaning | Response |
|---|---|---|
| `TEMPORARY_FAILURE` | A transient rejection. | Retry after a backoff. |
| `INVENTORY_CHANGED` | The creative inventory is not usable. | Wait for `inventoryReady`. |
| `TARGET_INVALID` | The configured target is gone. | Wait for a valid target. |
| `SERVER_TIMEOUT` | No confirmation within the window. | Keep the request outstanding and wait; do not resend by default. |
| `SERVER_REJECTION` | The send was refused. | Retry, then block after `max-retries`. |
| `MATERIAL_UNAVAILABLE` | The client has no such item. | Block and revisit later. |
| `PERMANENT_ERROR` | Cannot ever succeed. | Do not retry; keep the work unfinished and report it. |

### Execution states

The scheduler reports what it is waiting on, so the HUD and the user can see why
nothing is being sent:

| State | Meaning |
|---|---|
| `RETRYING` | A transient failure is being retried after a backoff. |
| `RECOVERING` | Blocked materials are being revisited after a wait. |
| `BLOCKED` | A material cannot currently be processed and keeps its queue position. |
| `WAITING_FOR_INVENTORY` | The creative inventory is not usable right now. |
| `WAITING_FOR_TARGET` | The looked-at target is missing. |
| `WAITING_FOR_SERVER` | A drop is unconfirmed and is not being resent. |

### Bounded retries and backoff

Retries use an exponential backoff: `retry-delay` doubled per attempt, capped at
`max-retry-delay`. After `max-retries` failed attempts the material is marked
`BLOCKED` rather than abandoned. The block keeps the material's queue position and
its remaining quantity, and it is revisited every `revisit-blocked` ticks.

While waiting, the scheduler does not re-send an identical request. A material
blocked on a slow server does not stop the other materials from progressing in
Best effort mode.

---

## 4. The exact-quantity contract

Given

```
Stone 130, Dirt 70, Glass 17
```

the scheduler produces

```
Stone   64 + 64 + 2
Dirt    64 + 6
Glass   17
```

The rules it enforces:

1. **Never more than required.** The sendable amount is `remaining - inFlight`,
   so items already requested but not yet confirmed also count against the target.
2. **Never a whole stack for a small requirement.** The last send is whatever is
   left, so a requirement of one is a send of one.
3. **Never split a stack mid-material when the budget runs out.** If the budget
   cannot cover the next stack and something has already been sent this tick, the
   tick ends. This is why the breakdown reads `64 + 64 + 2` rather than
   `36 + 94`. If nothing has been sent yet the send shrinks to fit, so a very low
   items-per-second setting still makes progress rather than stalling forever.
4. **Never count twice.** Outstanding requests are subtracted before a new send is
   sized, so a slow confirmation cannot cause an over-drop.

`StackMath.nextDropSize(remaining, maxStack, maxPerDrop)` owns rules 1-3 and is
tested directly.

---

## 5. Confirmed drops

### Why the client cannot be trusted

`ClientPlayerInteractionManager.dropCreativeStack` sends a
`CreativeInventoryActionC2SPacket` with slot `-1`. That is a *request*. The server
may ignore it entirely. The client is not told, and the packet has no response.

So the module keeps a ledger:

```
send(itemId, count, tick)          -> request recorded, nothing counted
observe(itemId, count, tick)       -> request confirmed, now counted
poll(tick, timeout, maxAttempts)   -> retry, stall, or nothing
```

`observe` is called only from `EntityAddedEvent` when an `ItemEntity` spawns. That
is the moment the server has actually created the items.

### The credit ceiling

`observe` will never credit more items than were sent:

```
headroom = sentTotal - observed
credit   = min(reported, max(0, headroom))
```

This is what makes a duplicated entity event harmless. The same item entity
reported twice is counted once. Without the ceiling, a duplicate would confirm a
later request that was never dropped, and the run would report a material as
delivered while the items were never created.

`sentTotal` rises on each send *and* on each retry, because a retry genuinely does
ask for a second batch of items.

### Retries are off by default

With `allow-resend: false` (the default), an unconfirmed drop is not resent. The
request is kept outstanding and the material is reported as waiting on the server:

```
Waiting for the server to confirm 64 x minecraft:stone; not resending to avoid duplicates
```

This is deliberate. A server that is merely slow would receive a second drop on a
retry, and the world would end up with items the user did not ask for. Keeping the
request outstanding is the safe choice: nothing is lost, a late confirmation is
still honoured, and the user can see what the scheduler is waiting on.

Setting `allow-resend` to true trades that safety for resilience on a genuinely
lossy connection. It is the user's call.

### The observation radius

Only item entities within 8 blocks of the player can confirm a request. Without
that guard, another player dropping the same item - or a mob dying near the player
and dropping it - would be credited as a delivery of ours. 8 blocks comfortably
covers a drop thrown at a target a few blocks away.

### The server's own ceiling

`ServerPlayNetworkHandler` adds 20 to an anti-spam budget each tick and spends 20
per creative drop, refusing drops once the budget reaches 200. That is ten drops
per tick, or 200 per second, sustained. Sending faster does not drop more items -
the extra packets are discarded. The scheduler clamps to this ceiling
(`SERVER_MAX_DROPS_PER_TICK`) rather than pretending a higher rate works.

---

## 6. Creative inventory strategies

### Direct drop

```
manager.dropCreativeStack(stack)
```

One packet. The server validates the item against its registry and performs the
drop. This is the default.

### Hotbar then drop

Two packets, and they must be on **different ticks**:

1. Tick N: `clickCreativeStack(stack, 36 + slot)` writes the item into a hotbar
   slot, and `player.getInventory().selectedSlot` is set to that slot.
2. Tick N+1: `player.dropSelectedItem(true)` throws it.

The gap is not padding. The client only emits `UpdateSelectedSlotC2SPacket` from
`ClientPlayerInteractionManager.tick`, and that method runs *before* the tick's
packets are flushed. A throw issued in the same tick as the give would therefore
be executed by the server against the *previous* slot, and would throw whatever
was already there - the wrong item, in the wrong quantity.

`CreativeInventoryHandler.tick()` advances this handshake once per client tick.
While a give is outstanding, the handler refuses a give for a different item, so
the slot can never be overwritten before its throw.

There is no packet spam in either strategy: sends are paced by the speed settings
and capped by the server's ceiling.

---

## 7. Architecture

```
SchematicDropper            Meteor module; owns the task, the handlers, the tick
|-- SchematicDropperSettings  all settings; applyTo(config, materials)
|-- MaterialManager           the material list: entries, cursor, sorting, progress
|   |-- MaterialEntry         one material: required / dropped / status
|   |-- MaterialListParser    txt / json / csv parsing and validation
|   |-- MaterialIds           identifier validation
|   `-- StackMath             stack arithmetic (the exact-quantity rules)
|-- DropTask                  scheduler: what to send, when, how much
|   |-- DropLedger            request / confirmation accounting
|   `-- Actions               the only interface to the game
|-- CreativeInventoryHandler  creative packets, both strategies
|-- DropTargetManager         feet vs looked-at entity
|-- SchematicReader           .litematic / .schem / .schematic parsing
|   |-- BitPackedArray        the bit-packed long arrays both formats use
|   `-- LitematicaIntegration the live-placement source
|-- SchematicDropperScreen    the material manager GUI
`-- SchematicDropperHud       the HUD element
```

The important boundary is `DropTask.Actions`. Everything that touches Minecraft
sits behind it, so the scheduler - with its rate limits, its ledger, its
cancellation and its resume behaviour - runs and is tested with no game at all.
The 23 tests in `DropTaskTest` drive the whole exact-quantity path through a fake
`Actions`.

### Meteor initialisation order

Meteor 0.5.8 runs addon initialisation in two passes:

| Pass | Called by | What may be done |
|---|---|---|
| `onRegisterCategories()` | `Categories.init()`, with `Categories.REGISTERING` set | `Modules.registerCategory(...)`, and nothing else - it throws outside this callback |
| `onInitialize()` | `MeteorClient.onInitializeClient`, after `Categories.init()` has returned | `Modules.get().add(...)`, HUD registration |

`Modules.add` rejects a module whose category is not in the registered list
(`Modules.addModule - Module's category was not registered`), so the category has
to be created in pass 1 and used in pass 2. It cannot be a field initialiser: the
class would be loaded before `REGISTERING` is set, so the registration would throw.

`SchematicDropperAddon.CATEGORY` is assigned inside `onRegisterCategories()`. It
is declared `public static` but deliberately left unassigned until then, so a
class-loading order mistake cannot produce a category Meteor has never seen.

### Threading

Schematic parsing runs on a single-thread daemon executor. The result is applied
on the client thread during the next tick, guarded by an `AtomicInteger`
generation counter so an abandoned import can never overwrite a newer one. Nothing
in the drop path runs off the client thread.

---

## 8. Input sources

### Litematica

`LitematicaIntegration` never references a Litematica type directly. Every
reference lives in the nested `Bridge` class, which is only class-loaded once
`FabricLoader.isModLoaded("litematica")` has confirmed the mod is present. This is
what keeps the addon loadable on a client without Litematica.

The materials come from `MaterialListUtils.createMaterialListFor(schematic,
regions)`, which is the same call Litematica itself uses. That matters: it already
handles waterlogged blocks needing a bucket, and blocks such as doors and slabs
that yield more than one item per block. Reimplementing that conversion would
produce counts that disagree with what Litematica shows the user.

Only enabled regions are read, via
`placement.getEnabledRelativeSubRegionPlacements()`. A region switched off in
Litematica is not going to be built, so counting it would overstate the
requirement.

### Schematic files

`SchematicReader` handles `.litematic` and `.schem` (Sponge v1-v3), both of which
store block indices in bit-packed long arrays - `BitPackedArray` decodes those for
either format. Palette entries are mapped to item ids; air is dropped;
`ItemResolver.itemForBlock` handles the handful of blocks whose item differs from
their name.

### Material lists

`.txt`, `.json` and `.csv`, parsed by `MaterialListParser`. Identifiers are
validated, duplicates merged, non-positive counts rejected, and errors carry the
line number. `.json` accepts both the documented `{"materials": [...]}` shape and a
plain object map.

---

## 9. Reliability

| Risk | Handling |
|---|---|
| Double start | `DropTask.start()` returns false while a run is active. |
| Stop with items in flight | Outstanding requests are discarded, never counted. |
| Disconnect mid-run | `GameLeftEvent` aborts and sets `requiresRestart`; the run is never resumed automatically. |
| World change mid-run | Same abort path; the user must restart deliberately. |
| Creative mode lost | The tick handler aborts before sending anything the server would ignore. |
| Module disabled | Task stopped, handshake cancelled, target lock cleared, HUD removed. |
| Stale parse result | Discarded by generation counter. |
| Registry change | `ItemResolver` cache cleared on join and leave. |
| Another player's drop | Ignored - outside the observation radius. |
| Duplicated entity event | Cannot over-credit; the ledger ceilings at what was sent. |
| Large schematic | Parsed off-thread; result applied on the client thread. |
| Failed drop | Classified, recorded and retried, waited out or blocked; the run continues. |
| Unconfirmed drop | Kept outstanding and never resent by default, so no duplicate is created. |
| Unavailable material | Blocked in place, keeping its queue position and remaining quantity. |
| Interrupted run | The queue, its order and confirmed progress are saved and restored. |
| Restart after a stop | Resumes the exact remaining quantities rather than starting over. |

---

## 10. Known limitations

- **Creative mode only.** The server ignores creative inventory packets from a
  non-creative player, and the module aborts rather than firing them into the void.
- **Looked-at entity requires a living entity.** The raycast will not aim at a
  block; the run reports "no target" rather than scattering items at a wall.
- **`.schematic` (legacy MCEdit)** support depends on the file's contents. The
  modern formats are the reliable path.
- **No automatic resume.** By design. See section 9. A stopped run keeps its
  unfinished queue, though: starting again resumes it rather than starting over.
- **Strict order waits indefinitely for the head material.** If it can never be
  obtained, switch to Best effort so the rest of the queue can proceed.
- **Litematica's own list is the source of truth** for the placement source, so any
  inaccuracy there is inherited here. That is preferable to a second, subtly
  different implementation.
