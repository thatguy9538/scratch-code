package com.example.scratchcode;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.registry.Registries;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.item.ItemStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.nbt.NbtCompound;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Scratch Code (Fabric, Minecraft 1.21.10)
 *
 * Build walking scripts out of Scratch-style blocks, then run them.
 * Each script starts with "when [key] pressed"; that key runs / stops it.
 *
 *   B            open the block editor
 *   /walk edit   open the block editor
 *   /walk run    run the first script
 *   /walk stop   stop
 */
public class ScratchCodeClient implements ClientModInitializer {

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("scratchcode", "main"));

    /** Ticks to wait after a walk step starts before a wall can end it. */
    private static final int WALL_COOLDOWN = 6;

    private static KeyBinding editorKey;

    /** All scripts. */
    public static Workspace workspace;
    /** The script that is running (or last ran). */
    public static Program program;
    /** Script keys that were held down last tick, to spot new presses. */
    private static final java.util.Set<Integer> keysDown = new java.util.HashSet<>();
    private static Path configFile;

    private static boolean running = false;
    /** Where the script is: one frame per list being run (the script, then any repeat / if inside it). */
    private static final java.util.ArrayDeque<Frame> stack = new java.util.ArrayDeque<>();

    private static final class Frame {
        final java.util.List<Program.Step> list;
        final Program.Step owner;     // the repeat / if this list is inside, or null for the script itself
        int index = 0;
        int trips = 0;                // for repeat blocks: loops finished

        Frame(java.util.List<Program.Step> list, Program.Step owner) {
            this.list = list;
            this.owner = owner;
        }
    }
    private static int cooldown = 0;
    private static int waitTicks = 0;
    private static Vec3d legStart = null;
    private static boolean holdStarted = false;
    /** "if container full": what we saw in each container (by block position) the last time it was open. */
    private static final java.util.Map<BlockPos, Boolean> containerFull = new java.util.HashMap<>();
    private static BlockPos openContainerPos = null;      // container that's open right now (if we know where)
    private static Boolean lastContainerFull = null;       // the most recently opened container
    private static boolean containerWasOpen = false;
    /** Ticks to wait after closing a full container before running the next block. */
    private static int resumeDelay = 0;
    /** The player's own "pause on lost focus" setting, put back when the script stops (null = untouched). */
    private static Boolean savedPauseOnLostFocus = null;
    private static final int CLOSE_DELAY = 20;     // 1 second
    /** "put all in container": how long to wait for a container to open, and the slots already moved. */
    private static final int DEPOSIT_OPEN_WAIT = 100;   // 5 seconds
    private static final java.util.Set<Integer> depositTried = new java.util.HashSet<>();
    private static final int DEPOSIT_INTERVAL = 10;     // ticks between stacks: 2 per second
    private static int depositCooldown = 0;             // ticks until the next stack is moved
    private static boolean wasAtStopSpot = false;
    /** For "if" blocks: did each block (by id) complete the last time it ran? */
    private static final java.util.Map<Integer, Boolean> outcomes = new java.util.HashMap<>();
    private static boolean controllingVertical = false;   // jump / sneak keys held by "go to"
    private static double goBest = Double.MAX_VALUE;      // closest "go to" has got so far
    private static int goStuck = 0;                        // ticks without getting closer
    private static final int GO_STUCK_LIMIT = 200;         // 10 seconds
    private static final java.util.Random RANDOM = new java.util.Random();

    @Override
    public void onInitializeClient() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("scratchcode.txt");
        // renamed from Lane Walker: bring the old scripts along the first time
        java.nio.file.Path old = FabricLoader.getInstance().getConfigDir().resolve("lanewalker.txt");
        if (!java.nio.file.Files.exists(configFile) && java.nio.file.Files.exists(old)) {
            try {
                java.nio.file.Files.copy(old, configFile);
            } catch (java.io.IOException ignored) {
            }
        }
        workspace = Workspace.load(configFile);
        program = workspace.scripts.get(0);

        editorKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.scratchcode.editor", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));

        ClientTickEvents.START_CLIENT_TICK.register(ScratchCodeClient::onTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> walk = literal("walk");
            walk.then(literal("stop").executes(ctx -> {
                stop(MinecraftClient.getInstance(), "stopped");
                return 1;
            }));
            walk.then(literal("run").executes(ctx -> {
                for (Program p : workspace.scripts) {
                    if (!p.steps.isEmpty()) {
                        start(MinecraftClient.getInstance(), p);
                        break;
                    }
                }
                return 1;
            }));
            walk.then(literal("edit").executes(ctx -> {
                MinecraftClient client = MinecraftClient.getInstance();
                client.send(() -> openEditor(client));
                return 1;
            }));
            dispatcher.register(walk);
        });
    }

    /** Display name for a GLFW key code, e.g. "K" or "Left Shift". */
    public static String keyName(int key) {
        if (key < 0) return "?";
        return InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
    }

    public static void saveProgram() {
        workspace.save(configFile);
    }

    public static void openEditor(MinecraftClient client) {
        stop(client, "stopped (editing)");
        client.setScreen(new BlockEditorScreen());
    }

    public static void start(MinecraftClient client, Program script) {
        if (client.player == null || script == null) return;
        if (script.steps.isEmpty()) {
            say(client, Text.literal("Scratch Code: the [" + script.comboName()
                    + "] script is empty. Press B to add blocks.").formatted(Formatting.RED));
            return;
        }
        if (running) {                       // switching scripts: let go of the old one's keys first
            running = false;
            controllingVertical = false;
            KeyBinding.updatePressedStates();
        }
        program = script;
        running = true;
        // keep running when you click into another window: don't let the game open its pause menu
        if (savedPauseOnLostFocus == null) savedPauseOnLostFocus = client.options.pauseOnLostFocus;
        client.options.pauseOnLostFocus = false;
        resumeDelay = 0;
        outcomes.clear();
        stack.clear();
        stack.push(new Frame(script.steps, null));
        wasAtStopSpot = atStopSpot(client.player) != null;   // only stop when you *arrive* at a spot
        settle(client);
    }

    public static void stop(MinecraftClient client, String why) {
        if (!running) return;
        running = false;
        controllingVertical = false;
        stack.clear();
        if (savedPauseOnLostFocus != null) {          // put the player's setting back
            client.options.pauseOnLostFocus = savedPauseOnLostFocus;
            savedPauseOnLostFocus = null;
        }
        KeyBinding.updatePressedStates(); // hand movement keys back to the real keyboard
        say(client, Text.literal("Scratch Code: " + why).formatted(Formatting.YELLOW));
    }

    private static Program.Step current() {
        Frame f = stack.peek();
        return f == null || f.index >= f.list.size() ? null : f.list.get(f.index);
    }

    private static Program.Dir currentWalkDir() {
        Program.Step s = current();
        return s != null && s.kind.isWalk() ? s.dir : null;
    }

    /** Set up the block that just became current (it's never a repeat / if). */
    private static void startStep(MinecraftClient client) {
        Program.Step step = current();
        cooldown = WALL_COOLDOWN;
        goBest = Double.MAX_VALUE;
        goStuck = 0;
        String desc = step.describe();
        if (step.kind == Program.Kind.WAIT_RANDOM) {
            int lo = Math.min(step.amount, step.amount2), hi = Math.max(step.amount, step.amount2);
            int t = lo + RANDOM.nextInt(hi - lo + 1);          // tenths of a second
            waitTicks = Math.max(1, t * 2);
            desc = "random wait: " + Program.tenths(t) + " s";
        } else {
            waitTicks = step.amount * 20;
        }
        if (step.kind == Program.Kind.HOLD_KEY) waitTicks = Math.max(1, step.amount2 * 2);
        if (step.kind == Program.Kind.DEPOSIT) waitTicks = DEPOSIT_OPEN_WAIT;
        depositTried.clear();
        depositCooldown = 0;
        holdStarted = false;
        ClientPlayerEntity p = client.player;
        legStart = new Vec3d(p.getX(), p.getY(), p.getZ());

        String loop = "";
        for (Frame f : stack) {              // innermost repeat first
            if (f.owner != null && f.owner.kind == Program.Kind.REPEAT) {
                loop = f.owner.amount > 0
                        ? "  (loop " + (f.trips + 1) + "/" + f.owner.amount + ")"
                        : "  (loop " + (f.trips + 1) + ")";
                break;
            }
        }
        say(client, Text.literal("Scratch Code: ").formatted(Formatting.YELLOW)
                .append(Text.literal(desc).formatted(Formatting.WHITE))
                .append(Text.literal(loop).formatted(Formatting.GRAY)));
    }

    /** Record whether the current block completed, then move on. Returns false if the script ended. */
    private static boolean finish(MinecraftClient client, boolean completed) {
        Program.Step cur = current();
        if (cur != null) outcomes.put(cur.id, completed);
        return next(client) && settle(client);
    }

    /** Step past the current block, leaving / looping repeat and if blocks as needed. */
    private static boolean next(MinecraftClient client) {
        while (true) {
            Frame f = stack.peek();
            if (f == null) {
                stop(client, "finished");
                return false;
            }
            f.index++;
            if (f.index < f.list.size()) return true;
            if (f.owner != null && f.owner.kind == Program.Kind.REPEAT) {
                f.trips++;
                if (f.owner.amount == 0 || f.trips < f.owner.amount) {
                    f.index = 0;             // go round again
                    return true;
                }
            }
            if (f.owner != null && f.owner.kind == Program.Kind.REPEAT_UNTIL) {
                f.trips++;
                if (!atSpot(client.player, f.owner)) {
                    f.index = 0;             // not there yet: go round again
                    return true;
                }
            }
            stack.pop();                     // finished this list; carry on after its repeat / if
            if (f.owner != null && !f.owner.kind.isConditional()) outcomes.put(f.owner.id, true);
            if (stack.isEmpty()) {
                stop(client, "finished");
                return false;
            }
        }
    }

    /**
     * Make sure the current block is a normal block: go inside repeat / if blocks,
     * or skip them when their condition is false. Returns false if the script ended.
     */
    private static boolean settle(MinecraftClient client) {
        for (int guard = 0; guard < 200; guard++) {
            Program.Step s = current();
            if (s == null) {
                if (!next(client)) return false;
                continue;
            }
            if (!s.kind.isContainer()) {
                startStep(client);
                return true;
            }
            java.util.List<Program.Step> section = null;
            if (s.kind.isConditional()) {
                boolean cond = check(client, s);
                outcomes.put(s.id, cond);    // anything tied to it by a string sees this
                section = cond ? s.children : (s.hasElse ? s.elseChildren : null);
            } else if (s.kind == Program.Kind.REPEAT_UNTIL) {
                if (atSpot(client.player, s)) outcomes.put(s.id, true);   // already there: skip
                else section = s.children;
            } else {                         // REPEAT
                section = s.children;
                if (section.isEmpty()) outcomes.put(s.id, true);
            }
            if (section != null && !section.isEmpty()) {
                stack.push(new Frame(section, s));
            } else if (!next(client)) {      // skip the whole section
                return false;
            }
        }
        return true;   // lots of empty / skipped sections in a row: keep going next tick
    }

    /** Is the player standing on this block's x, z spot? */
    private static boolean atSpot(ClientPlayerEntity pl, Program.Step s) {
        return MathHelper.floor(pl.getX()) == s.amount && MathHelper.floor(pl.getZ()) == s.amount2;
    }

    /** The condition of an if block. */
    private static boolean check(MinecraftClient client, Program.Step s) {
        ClientPlayerEntity pl = client.player;
        return switch (s.kind) {
            case IF_AT -> atSpot(pl, s) != (s.mode == 1);
            case IF -> {                     // did the block on the end of the string complete?
                Program.Step linked = s.link >= 0 ? program.byId(s.link) : null;
                yield linked != null && outcomes.getOrDefault(linked.id, false);
            }
            case IF_WALL -> touchingWall(client);
            case IF_HOLDING -> Registries.ITEM.getId(pl.getMainHandStack().getItem()).toString().equals(s.text);
            case IF_INV_FULL -> pl.getInventory().getEmptySlot() == -1;
            case IF_CONTAINER_FULL -> {
                boolean full = containerIsFull(client);
                if (full && isContainerOpen(client)) {     // close it, then carry on 1 second later
                    closeContainer(client);
                    resumeDelay = CLOSE_DELAY;
                }
                yield full;
            }
            case IF_LOOKING -> s.text.equals(lookedAtBlock(client));
            case IF_HEALTH -> pl.getHealth() < s.amount * 2f;
            case IF_Y -> s.mode == 1 ? pl.getY() > s.amount : pl.getY() < s.amount;
            default -> false;
        };
    }

    private static void closeContainer(MinecraftClient client) {
        client.player.closeHandledScreen();
        containerWasOpen = false;
    }

    /**
     * Does this stack match what was typed? Accepts a vanilla id ("diamond", "minecraft:diamond"),
     * a server's custom item id (stored in the item's custom data, e.g. "ENCHANTED_DIAMOND"),
     * or the item's name as shown in game.
     */
    static boolean matchesItem(ItemStack stack, String typed) {
        String q = typed == null ? "" : typed.trim();
        if (q.isEmpty() || stack.isEmpty()) return false;
        String lower = q.toLowerCase(java.util.Locale.ROOT);
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        if (id.equals(lower) || id.equals("minecraft:" + lower)) return true;
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (custom != null) {
            NbtCompound nbt = custom.copyNbt();
            if (nbt.getString("id", "").equalsIgnoreCase(q)) return true;
            if (nbt.getCompoundOrEmpty("ExtraAttributes").getString("id", "").equalsIgnoreCase(q)) return true;
        }
        String name = Formatting.strip(stack.getName().getString());
        return name != null && name.trim().equalsIgnoreCase(q);
    }

    private static boolean isContainerOpen(MinecraftClient client) {
        ClientPlayerEntity pl = client.player;
        return pl != null && client.currentScreen instanceof HandledScreen<?> && pl.currentScreenHandler != null
                && pl.currentScreenHandler != pl.playerScreenHandler;
    }

    private static void releaseMovement(MinecraftClient client) {
        GameOptions o = client.options;
        o.forwardKey.setPressed(false);
        o.backKey.setPressed(false);
        o.leftKey.setPressed(false);
        o.rightKey.setPressed(false);
    }

    /** Remember how full each container is while it's open (the game only sends what's inside then). */
    private static void trackContainer(MinecraftClient client) {
        ClientPlayerEntity pl = client.player;
        ScreenHandler handler = pl.currentScreenHandler;
        boolean open = client.currentScreen instanceof HandledScreen<?> && handler != null
                && handler != pl.playerScreenHandler;
        if (open && !containerWasOpen) {          // just opened: it's the block we were looking at
            openContainerPos = null;
            if (client.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK
                    && client.world.getBlockEntity(bhr.getBlockPos()) != null) {   // a real chest / barrel etc.
                openContainerPos = bhr.getBlockPos().toImmutable();
            }                                     // otherwise it's a server menu opened some other way
        }
        if (open) {
            boolean any = false, full = true;
            for (Slot slot : handler.slots) {
                if (slot.inventory == pl.getInventory()) continue;   // skip your own inventory
                if (slot.hasStack() && isMenuFiller(slot.getStack())) continue;   // server menu decoration
                any = true;
                if (!slot.hasStack()) full = false;
            }
            if (any) {
                lastContainerFull = full;
                if (openContainerPos != null) containerFull.put(openContainerPos, full);
            }
        }
        containerWasOpen = open;
    }

    /** Glass panes and barriers: what server menus use as filler and "close" buttons, not storage. */
    private static boolean isMenuFiller(net.minecraft.item.ItemStack stack) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        return path.endsWith("glass_pane") || path.equals("barrier");
    }

    /** The open container, else the one you're looking at, else the last one you opened. */
    private static boolean containerIsFull(MinecraftClient client) {
        if (isContainerOpen(client) && lastContainerFull != null) return lastContainerFull;   // the open one
        if (client.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            Boolean seen = containerFull.get(bhr.getBlockPos());
            if (seen != null) return seen;
        }
        return lastContainerFull != null && lastContainerFull;
    }

    /** Is there a solid block right in front of the player (at feet or head height)? */
    private static boolean touchingWall(MinecraftClient client) {
        ClientPlayerEntity pl = client.player;
        Direction facing = pl.getHorizontalFacing();
        BlockPos feet = pl.getBlockPos().offset(facing);
        for (BlockPos p : new BlockPos[]{feet, feet.up()}) {
            if (!client.world.getBlockState(p).getCollisionShape(client.world, p).isEmpty()) return true;
        }
        return false;
    }

    /** Id of the block under the crosshair, e.g. "minecraft:stone", or "" if none. */
    static String lookedAtBlock(MinecraftClient client) {
        if (client.world != null && client.crosshairTarget instanceof BlockHitResult bhr
                && bhr.getType() == HitResult.Type.BLOCK) {
            return Registries.BLOCK.getId(client.world.getBlockState(bhr.getBlockPos()).getBlock()).toString();
        }
        return "";
    }

    /** Id of the item in the player's main hand, e.g. "minecraft:diamond_pickaxe". */
    static String heldItem(MinecraftClient client) {
        if (client.player == null) return "";
        return Registries.ITEM.getId(client.player.getMainHandStack().getItem()).toString();
    }

    private static void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            running = false;
            return;
        }

        while (editorKey.wasPressed()) {
            if (client.currentScreen == null) openEditor(client);
        }
        // each script's "when [keys] pressed" combination runs it, or stops it if it's already running
        if (client.currentScreen == null) {
            long handle = client.getWindow().getHandle();
            java.util.Set<Integer> nowDown = new java.util.HashSet<>();
            for (Program script : workspace.scripts) {
                for (int k : script.combo()) {
                    if (k >= GLFW.GLFW_KEY_SPACE && k <= GLFW.GLFW_KEY_LAST
                            && GLFW.glfwGetKey(handle, k) == GLFW.GLFW_PRESS) nowDown.add(k);
                }
            }
            // the combination that was just completed; if several, the one with the most keys
            // (so Ctrl + K doesn't also start the plain K script)
            Program pressed = null;
            int most = 0;
            for (Program script : workspace.scripts) {
                int[] c = script.combo();
                if (c.length == 0) continue;
                boolean all = true, allBefore = true;
                for (int k : c) {
                    if (!nowDown.contains(k)) all = false;
                    if (!keysDown.contains(k)) allBefore = false;
                }
                if (all && !allBefore && c.length > most) {
                    pressed = script;
                    most = c.length;
                }
            }
            keysDown.clear();
            keysDown.addAll(nowDown);
            if (pressed != null) {
                if (running && program == pressed) stop(client, "stopped");
                else start(client, pressed);
            }
        } else {
            keysDown.clear();
        }
        trackContainer(client);
        if (!running) return;
        if (program.steps.isEmpty()) {
            stop(client, "stopped (empty program)");
            return;
        }
        if (player.isDead()) {
            stop(client, "stopped (you died)");
            return;
        }
        // menus pause the script, except containers (chests etc.), so it can check and close them
        boolean containerOpen = isContainerOpen(client);
        if (client.currentScreen != null && !containerOpen) return;
        if (resumeDelay > 0) {                    // just closed a full container: wait a moment
            resumeDelay--;
            releaseMovement(client);
            return;
        }
        if (current() == null || current().kind.isContainer()) {
            if (!settle(client)) return;
            if (current() == null || current().kind.isContainer()) return;
        }

        // "stop at x z" blocks are checked every tick, wherever they are in the script
        Program.Step spot = atStopSpot(player);
        if (spot != null && !wasAtStopSpot) {
            stop(client, "reached x " + spot.amount + " z " + spot.amount2 + ", stopped");
            return;
        }
        wasAtStopSpot = spot != null;

        Program.Step step = current();
        if (containerOpen && (step.kind.isWalk() || step.kind == Program.Kind.GO_TO)) return;   // can't move in a chest
        Program.Dir walkDir = null;
        boolean goToActive = false, wantJump = false, wantSneak = false;

        switch (step.kind) {
            case GO_TO -> {
                double dx = step.amount + 0.5 - player.getX();
                double dy = step.amount2 - player.getY();
                double dz = step.amount3 + 0.5 - player.getZ();
                double h = Math.sqrt(dx * dx + dz * dz);
                boolean canFly = player.getAbilities().allowFlying;
                boolean arrived = h < 0.6 && (!canFly || Math.abs(dy) < 0.6);

                if (arrived) {
                    if (!finish(client, true)) return;
                    walkDir = currentWalkDir();
                    break;
                }

                double dist = canFly ? Math.sqrt(h * h + dy * dy) : h;
                if (dist < goBest - 0.05) {
                    goBest = dist;
                    goStuck = 0;
                } else if (++goStuck > GO_STUCK_LIMIT) {
                    stop(client, "couldn't get to " + step.amount + " " + step.amount2 + " " + step.amount3
                            + ", stopped");
                    return;
                }

                // only fly when the game lets you (creative, or a server that allows flying)
                if (canFly && !player.getAbilities().flying) {
                    player.getAbilities().flying = true;
                    player.sendAbilitiesUpdate();
                }

                if (h >= 0.6) {
                    float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                    player.setYaw(yaw);
                    player.setHeadYaw(yaw);
                    walkDir = Program.Dir.FORWARD;
                }
                goToActive = true;
                if (player.getAbilities().flying) {
                    wantJump = dy > 0.4;     // fly up
                    wantSneak = dy < -0.4;   // fly down
                } else {
                    wantJump = player.horizontalCollision && player.isOnGround();   // hop up steps
                }
            }
            case STOP_AT -> {                 // nothing to do in order; it's checked every tick above
                if (!finish(client, true)) return;
                walkDir = currentWalkDir();
            }
            case SET_ANGLE -> {
                float yaw = MathHelper.wrapDegrees((float) step.amount);
                float pitch = MathHelper.clamp((float) step.amount2, -90f, 90f);
                player.setYaw(yaw);
                player.setPitch(pitch);
                player.setHeadYaw(yaw);
                if (!finish(client, true)) return;
                walkDir = currentWalkDir();
            }
            case HOLD_KEY -> {
                InputUtil.Key key = holdKey(step);
                if (!holdStarted) {
                    KeyBinding.onKeyPressed(key);    // counts as a click, like pressing it for real
                    holdStarted = true;
                }
                if (--waitTicks <= 0) {
                    KeyBinding.setKeyPressed(key, false);
                    if (!finish(client, true)) return;
                    walkDir = currentWalkDir();
                }
            }
            case DEPOSIT -> {
                if (!containerOpen) {                 // wait (up to 5 s) for a container to open
                    if (--waitTicks <= 0) {
                        if (!finish(client, false)) return;
                        walkDir = currentWalkDir();
                    }
                    break;
                }
                if (containerIsFull(client)) {        // filled up: close it, next block 1 second later
                    closeContainer(client);
                    resumeDelay = CLOSE_DELAY;
                    if (!finish(client, true)) return;
                    break;
                }
                if (depositCooldown > 0) {            // 2 stacks a second
                    depositCooldown--;
                    break;
                }
                ScreenHandler handler = player.currentScreenHandler;
                Slot nextSlot = null;
                for (Slot sl : handler.slots) {
                    if (sl.inventory == player.getInventory() && sl.hasStack() && !depositTried.contains(sl.id)
                            && matchesItem(sl.getStack(), step.text)) {
                        nextSlot = sl;
                        break;
                    }
                }
                if (nextSlot == null) {               // everything matching has been put away
                    if (!finish(client, true)) return;
                    break;
                }
                depositTried.add(nextSlot.id);        // shift-click one stack
                depositCooldown = DEPOSIT_INTERVAL - 1;
                client.interactionManager.clickSlot(handler.syncId, nextSlot.id, 0, SlotActionType.QUICK_MOVE, player);
            }
            case CLOSE_CONTAINER -> {
                if (containerOpen) closeContainer(client);
                if (!finish(client, true)) return;
                walkDir = containerOpen ? null : currentWalkDir();
            }
            case WAIT_OPEN -> {
                if (containerOpen) {
                    if (!finish(client, true)) return;
                } else if (--waitTicks <= 0) {        // gave up waiting
                    if (!finish(client, false)) return;
                    walkDir = currentWalkDir();
                }
            }
            case WAIT_UNTIL -> {
                if (atSpot(player, step)) {
                    if (!finish(client, true)) return;
                    walkDir = currentWalkDir();
                }
            }
            case STOP_SCRIPT -> {
                stop(client, "stopped by a stop script block");
                return;
            }
            case RUN_SCRIPT -> {
                Program target = null;
                for (Program p : workspace.scripts) if (p.key == step.amount && !p.steps.isEmpty()) target = p;
                if (target != null) {
                    start(client, target);            // switches to that script
                    return;
                }
                if (!finish(client, false)) return;   // no script on that key: skip
                walkDir = currentWalkDir();
            }
            case SELECT_SLOT -> {
                player.getInventory().setSelectedSlot(step.amount - 1);
                if (!finish(client, true)) return;
                walkDir = currentWalkDir();
            }
            case SAY -> {                     // sends it in chat, like typing it yourself
                String msg = step.text.trim();
                if (msg.startsWith("/")) {
                    if (msg.length() > 1) player.networkHandler.sendChatCommand(msg.substring(1));
                } else if (!msg.isEmpty()) {
                    player.networkHandler.sendChatMessage(msg);
                }
                if (!finish(client, true)) return;
                walkDir = currentWalkDir();
            }
            case SOUND -> {
                PositionedSoundInstance sound = switch (step.amount) {
                    case 1 -> PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME, 1.0f);
                    case 2 -> PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f);
                    default -> PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 1.0f);
                };
                client.getSoundManager().play(sound);
                if (!finish(client, true)) return;
                walkDir = currentWalkDir();
            }
            case COMMENT -> {                 // notes don't do anything
                if (!finish(client, true)) return;
                walkDir = currentWalkDir();
            }
            case WAIT, WAIT_RANDOM -> {
                if (--waitTicks <= 0) {
                    if (!finish(client, true)) return;
                }
            }
            case WALK_WALL, WALK_BLOCKS -> {
                walkDir = step.dir;
                if (cooldown > 0) {
                    cooldown--;
                } else {
                    boolean wall = player.horizontalCollision;
                    boolean distance = false;
                    if (step.kind == Program.Kind.WALK_BLOCKS && legStart != null) {
                        double dx = player.getX() - legStart.x, dz = player.getZ() - legStart.z;
                        distance = dx * dx + dz * dz >= (double) step.amount * step.amount;
                    }
                    if (wall || distance) {
                        // "until wall" completes at a wall; "N blocks" only completes if it went the full distance
                        boolean completed = step.kind == Program.Kind.WALK_WALL || distance;
                        if (!finish(client, completed)) return;
                        walkDir = currentWalkDir();
                    }
                }
            }
        }

        GameOptions o = client.options;
        if (controllingVertical && !goToActive) KeyBinding.updatePressedStates();   // give jump/sneak back
        if (goToActive) {
            o.jumpKey.setPressed(wantJump);
            o.sneakKey.setPressed(wantSneak);
        }
        controllingVertical = goToActive;
        o.forwardKey.setPressed(walkDir == Program.Dir.FORWARD);
        o.backKey.setPressed(walkDir == Program.Dir.BACK);
        o.leftKey.setPressed(walkDir == Program.Dir.LEFT);
        o.rightKey.setPressed(walkDir == Program.Dir.RIGHT);
        Program.Step now = current();
        if (running && now != null && now.kind == Program.Kind.HOLD_KEY) {
            KeyBinding.setKeyPressed(holdKey(now), true);   // keep holding (after the walk keys above)
        }
    }

    /** The "stop at" block whose spot the player is standing on, or null. */
    private static Program.Step atStopSpot(ClientPlayerEntity player) {
        int bx = MathHelper.floor(player.getX()), bz = MathHelper.floor(player.getZ());
        for (Program.Step s : program.allSteps()) {
            if (s.kind == Program.Kind.STOP_AT && s.amount == bx && s.amount2 == bz) return s;
        }
        return null;
    }

    private static InputUtil.Key holdKey(Program.Step step) {
        return step.amount3 == 1
                ? InputUtil.Type.MOUSE.createFromCode(step.amount)
                : InputUtil.Type.KEYSYM.createFromCode(step.amount);
    }

    static void say(MinecraftClient client, Text msg) {
        client.inGameHud.setOverlayMessage(msg, false);
    }
}
