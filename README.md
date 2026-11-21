Scratch Code: Fabric mod for Minecraft 1.21.10


Build a program out of Scratch-style blocks, then run it.

## Controls
| Input | What it does |
|-------|--------------|
| **B** | Open the block editor |
| A script's keys (K by default; can be a combo like F3 + P) | Run that script, or stop it if it's running |
| `/walk edit`, `/walk run`, `/walk stop` | Open the editor, run the first script, stop |

## The block editor
* **Scripts:** every script starts with a yellow **when [K] key pressed** block, and blocks snap underneath it.
  Drag a new hat out of the **Events** section to add another script (up to 8); new scripts start empty.
  Click the key box, then hold the key or keys you want **together** (up to 3, like `F3 + P` or `Ctrl + Shift + K`)
  and let go; the whole combination is saved. Esc on its own cancels, Backspace on its own clears; right-click the
  box to clear it too. Two scripts can't share the same combination. If one script uses `K` and another `Ctrl + K`,
  pressing Ctrl + K only starts the Ctrl + K one. (Some combos also do things in vanilla, e.g. F3 + P toggles
  "pause on lost focus", and still will.)
  Click the green **▶** on a script to run it right away. Drag a script's hat block back to the palette to delete the whole script.
  Only one script moves you at a time: pressing another script's key switches to it.
* **Dragging:** a dark line shows where a block will land: under another block, or inside a repeat / if.
  Dragging a repeat or if block brings everything inside it along. Drop a block on the palette to delete it.
* **Motion blocks (blue):** `walk [dir] until wall`, `walk [dir] [5] blocks`, `set angle x [0] y [0]`, `go to x [] y [] z []`.
* **Control blocks (orange):**
  * **repeat [forever ▾]** / **repeat [5] [times ▾]**: runs the blocks inside it over and over.
  * **repeat until at x [] z []**: runs the blocks inside over and over until you're standing on that spot
    (checked before each loop).
  * **if [at ▾] x [] z []**: the blocks inside only run if you're on that spot (or, with **not at**, if you aren't).
  * **if touching wall**: runs the inside if there's a solid block right in front of you.
  * **if done ●**: click its ● and then another block to tie them with a string (in that block's colour).
    The inside runs only if the tied block completed the last time it ran. Tied to another if block, it checks
    whether that if was true. Right-click ● to cut the string.
  * **Every if block** can have an **else** section: click **+ else** on its bottom edge. The else blocks run when
    the check is false. Click the **×** on an empty else to remove it.
  * `wait [1] seconds`, `random wait [0.5] to [2.0] s`, **wait until at x [] z []** (waits until you get there).
  * `stop at x [] z []` (stops the script the moment you step on that spot, wherever it sits in the script),
    **stop script** (stops right there), **run [J] script** (switches to the script on that key; click the box
    and press a key to choose it).
* **Inventory blocks (purple):** **select slot [1]** (hotbar 1-9), **if holding [item]**, **if inventory full**, **if container full**.
  **if container full** (a C block) checks the chest / barrel / shulker etc. you have open; if none is open, the one
  you're looking at, using what was in it the last time you opened it (the game only shows what's inside a
  container while it's open). A container you haven't opened yet counts as not full.
  It also works in **server menus** (chest-style screens like storage or backpacks): glass panes and barriers are
  treated as menu decoration / buttons, not storage, so only the real storage slots decide whether it's full.
  If the container is **open and full**, the block **closes it**, waits **1 second**, then runs the blocks inside.
  **put all [diamond] in container**: type an item id (`diamond`, `minecraft:diamond`), a server item's custom id
  (like `ENCHANTED_DIAMOND`), or the item's name as shown in game. Once a container is open (it waits up to 5 s for one),
  it shift-clicks every matching stack from your inventory into it, 2 stacks per second. If the container fills up, it
  closes it and runs the next block 1 second later. If everything fits, it runs the next block and leaves it open.
  If no container opens within 5 s it skips (counts as not done).
  **close container** closes the open container. **wait for container, up to [5] s** waits for a menu to open
  (handy after a `say /command` that opens one); it counts as done only if one opened in time.
  For **if holding**, click the box to use the item in your hand right now (right-click clears it).
* **Sensing blocks (light blue):** **if looking at [block]** (click the box to use the block under your crosshair),
  **if health below [5] ❤** (hearts), **if y [below ▾] [64]**.
* **Keys block (light blue):** **hold [left click] for [1.0] s** holds a key or mouse button down for that long,
  then lets go and moves on. Click the key box, then press any key or click a mouse button (left / right / middle)
  to choose it; Esc cancels. Type the time in seconds, like 1.5. Holding a movement key (W, A, S, D)
  works too, and so does holding left click to keep mining or right click to keep using an item.
* **Other blocks:** **say [hello]** (sends the words in chat, like typing them yourself, so other players see them;
  start it with `/` to run a command instead), **play sound [ding ▾]**
  (ding, chime or level up), **note: [my note]** (a grey comment that does nothing).
  To type words, click the white box, type, then press Enter (or click anywhere).
* A block "completes" when it finishes normally. `walk N blocks` doesn't complete if a wall stops it early;
  an if block "completes" when its check was true.
* **Drop-downs (▾)** open a list when you click them.
* **Number boxes:** click one, type the number, then press Enter (or click anywhere). Esc cancels.
  Decimals work in time boxes (like `1.5`), and minus works for coordinates and angles. Numbers outside a box's range are pulled into range.
* When you drag out a block with coordinates (go to, stop at, if at, repeat until, wait until, if y), it fills in where you are.
* A script with no repeat runs once from top to bottom, then stops ("finished").
* Buttons: **Stop**, **Reset** (back to one left/right script), **Done**.

Your scripts are saved automatically to `.minecraft/config/scratchcode.txt`.

While a script runs, Minecraft keeps going when you click into another window or tab (the "pause on lost
focus" setting is turned off until the script stops, then put back). Mining / attacking with a held left click
only works while the Minecraft window is focused, because the game lets go of the mouse when you switch away.

Walk directions are relative to where you're looking. While it runs you can still look around and jump.
It pauses while a menu is open and stops if you die. Containers (chests, barrels, shulker boxes...) are the
exception: the script keeps running while one is open, except walk and go to blocks, which wait until it's closed.

## Install
Put `lanewalker-5.7.0.jar` and Fabric API (1.21.10) in `.minecraft/mods`, then launch with Fabric Loader for 1.21.10.
