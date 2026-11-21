package com.example.scratchcode;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Scratch-style block editor for Scratch Code scripts.
 *
 * Each script starts with a "when [key] pressed" hat block. Blocks snap under
 * it, and "C" blocks (repeat, if at, if done) hold their own section of blocks.
 * Drag a hat or block back to the palette to delete it.
 */
public class BlockEditorScreen extends Screen {

    // Scratch colours
    private static final int MOTION = 0xFF4C97FF, MOTION_DARK = 0xFF3373CC;
    private static final int CONTROL = 0xFFFFAB19, CONTROL_DARK = 0xFFCF8B17;
    private static final int EVENTS = 0xFFFFBF00, EVENTS_DARK = 0xFFCC9900;
    private static final int GREEN = 0xFF59C059;
    private static final int SENSING = 0xFF5CB1D6, SENSING_DARK = 0xFF2E8EB8;
    private static final int LOOKS = 0xFF9966FF, LOOKS_DARK = 0xFF774DCB;
    private static final int SOUND = 0xFFCF63CF, SOUND_DARK = 0xFFBD42BD;
    private static final int NOTE = 0xFF9AA0B4, NOTE_DARK = 0xFF7B8099;
    private static final int PALETTE_BG = 0xFFE9EEF2, SCRIPT_BG = 0xFFF9F9F9, BORDER = 0xFFC6CCD8;
    private static final int WHITE = 0xFFFFFFFF, FIELD_TEXT = 0xFF575E75, HINT = 0xFF6B7290;
    private static final int CAPTURE_BG = 0xFFFFF3B0, DROP_LINE = 0xFF575E75;

    private static final int BH = 22;      // stack block height
    private static final int ARM = 14;     // width of a C block's left arm
    private static final int C_BOTTOM = 12;
    private static final int EMPTY_C = 16; // height of an empty C block's inside
    private static final int TOP = 24;
    private static final int PAL_X = 8, PAL_W = 176;
    private static final int SCRIPT_GAP = 30;

    private enum HitType {
        PALETTE, HAT_TEMPLATE, HAT, KEY, HOLDKEY, RUN, BLOCK, DIR, MODE, NUM, NUM2, NUM3, LINK, REPEAT, RMODE, BUTTON, MENU_ITEM,
        ATMODE, YMODE, SOUNDSEL, PICK, TEXT, SCRIPTKEY, ELSE_ADD, ELSE_REMOVE
    }

    /**
     * A clickable area. script = which script (-1 = none); step = the block it belongs to;
     * list = the list that block sits in; index = palette / button / menu item number.
     */
    private record Hit(int x1, int y1, int x2, int y2, HitType type, int script,
                       Program.Step step, List<Program.Step> list, int index) {
        boolean contains(int x, int y) { return x >= x1 && x < x2 && y >= y1 && y < y2; }
    }

    /** A place a dragged block could be dropped: position index in a list. */
    private record DropPoint(List<Program.Step> list, int index, int x, int y) {}

    private enum SegType { LABEL, DIR, MODE, NUM, NUM2, NUM3, LINK, REPEAT, RMODE, KEY, HOLDKEY, RUN,
        ATMODE, YMODE, SOUNDSEL, PICK, TEXT, SCRIPTKEY }

    private record Seg(SegType type, String text) {}

    /** An open drop-down list. */
    private record Menu(HitType kind, Program.Step step, int x, int y, int w, List<String> options, int selected) {}

    private static final List<String> DIR_OPTIONS = List.of("forward", "back", "left", "right");
    private static final List<String> WALK_MODE_OPTIONS = List.of("until wall", "blocks");
    private static final List<String> REPEAT_MODE_OPTIONS = List.of("forever", "times");
    private static final List<String> AT_OPTIONS = List.of("at", "not at");
    private static final List<String> Y_OPTIONS = List.of("below", "above");
    private static int rememberedRepeats = 5;
    private static final int ITEM_H = 14;

    private static final Program.Step[] TEMPLATES = {
            // motion
            new Program.Step(Program.Kind.WALK_WALL, Program.Dir.LEFT, 5),
            new Program.Step(Program.Kind.WALK_BLOCKS, Program.Dir.RIGHT, 5),
            new Program.Step(Program.Kind.SET_ANGLE, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.GO_TO, Program.Dir.LEFT, 0, 64, 0),
            // control
            new Program.Step(Program.Kind.REPEAT, Program.Dir.LEFT, 0),
            new Program.Step(Program.Kind.REPEAT_UNTIL, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.IF_AT, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.IF_WALL, Program.Dir.LEFT, 0),
            new Program.Step(Program.Kind.IF, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.WAIT, Program.Dir.LEFT, 1),
            new Program.Step(Program.Kind.WAIT_RANDOM, Program.Dir.LEFT, 5, 20),
            new Program.Step(Program.Kind.WAIT_UNTIL, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.STOP_AT, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.STOP_SCRIPT, Program.Dir.LEFT, 0),
            new Program.Step(Program.Kind.RUN_SCRIPT, Program.Dir.LEFT, GLFW.GLFW_KEY_J),
            // inventory
            new Program.Step(Program.Kind.SELECT_SLOT, Program.Dir.LEFT, 1),
            new Program.Step(Program.Kind.IF_HOLDING, Program.Dir.LEFT, 0),
            new Program.Step(Program.Kind.IF_INV_FULL, Program.Dir.LEFT, 0),
            new Program.Step(Program.Kind.IF_CONTAINER_FULL, Program.Dir.LEFT, 0),
            new Program.Step(Program.Kind.DEPOSIT, Program.Dir.LEFT, 0).withText("diamond"),
            new Program.Step(Program.Kind.CLOSE_CONTAINER, Program.Dir.LEFT, 0),
            new Program.Step(Program.Kind.WAIT_OPEN, Program.Dir.LEFT, 5),
            // sensing
            new Program.Step(Program.Kind.IF_LOOKING, Program.Dir.LEFT, 0),
            new Program.Step(Program.Kind.IF_HEALTH, Program.Dir.LEFT, 5),
            new Program.Step(Program.Kind.IF_Y, Program.Dir.LEFT, 64),
            // keys
            new Program.Step(Program.Kind.HOLD_KEY, Program.Dir.LEFT, 0, 10, 1),   // hold left click for 1.0 s
            // other
            new Program.Step(Program.Kind.SAY, Program.Dir.LEFT, 0).withText("hello"),
            new Program.Step(Program.Kind.SOUND, Program.Dir.LEFT, 0),
            new Program.Step(Program.Kind.COMMENT, Program.Dir.LEFT, 0).withText("my note"),
    };
    /** Palette section headings, by the template they come before. */
    private static final Map<Integer, String> SECTIONS = Map.of(
            0, "Motion", 4, "Control", 15, "Inventory", 22, "Sensing", 25, "Keys", 26, "Other");

    /** Keys handed out to new scripts, in order. */
    private static final int[] DEFAULT_KEYS = {
            GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_U,
            GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_M, GLFW.GLFW_KEY_O,
    };

    /** Every valid GLFW keyboard key code (asking GLFW about other codes logs errors). */
    private static final int[] VALID_KEYS = buildValidKeys();

    private static int[] buildValidKeys() {
        int[][] ranges = {{32, 32}, {39, 39}, {44, 57}, {59, 59}, {61, 61}, {65, 93}, {96, 96}, {161, 162},
                {256, 269}, {280, 284}, {290, 314}, {320, 336}, {340, 348}};
        List<Integer> keys = new ArrayList<>();
        for (int[] r : ranges) for (int k = r[0]; k <= r[1]; k++) keys.add(k);
        int[] out = new int[keys.size()];
        for (int i = 0; i < out.length; i++) out[i] = keys.get(i);
        return out;
    }

    private static final String[] BUTTONS = {"\u25A0 Stop", "Reset", "Done"};
    private static final int[] BUTTON_COLORS = {0xFFFF6680, 0xFF9AA0B4, MOTION};

    private List<Hit> hits = new ArrayList<>();
    private List<Hit> nextHits = new ArrayList<>();
    private final List<DropPoint> dropPoints = new ArrayList<>();
    private DropPoint bestDrop = null;

    private boolean prevLeft, prevRight;

    // dragging
    private Program.Step dragStep = null;   // a block (with anything inside it) being dragged
    private boolean dragHatNew = false;     // a new "when key pressed" from the palette
    private int dragHatScript = -1;         // an existing script's hat (drop on the palette to delete)
    private int dragOffX, dragOffY;

    private int scrollY = 0, contentHeight = 0;
    private int palScroll = 0, palContentHeight = 0;
    private Menu menu = null;

    /** The "if done" block whose string is being connected, and its script. */
    private Program.Step linkFrom = null;
    private int linkScript = -1;

    /** Script whose key is being picked ("press a key"), or -1. */
    private int capturing = -1;
    /** "hold key" block whose key is being picked, or null. */
    private Program.Step capturingStep = null;
    /** "say" / "note" block whose words are being typed, or null. */
    private Program.Step editingText = null;
    /** Number box being typed into: its block, which box, and what's typed so far. */
    private Program.Step editNumStep = null;
    private SegType editNumSeg = null;
    private String numBuffer = "";
    private final Set<Integer> captureHeld = new HashSet<>();
    /** Keys pressed so far while picking a script's key combination. */
    private final List<Integer> captureCombo = new ArrayList<>();

    /** Per frame: block rectangles {x1, y1, x2, color} and "if" sockets {x, y}, by block id. */
    private final Map<Integer, int[]> blockRects = new HashMap<>();
    private final Map<Integer, int[]> sockets = new HashMap<>();
    /** Per frame: little connector tabs {x, y, color}, drawn after the blocks. */
    private final List<int[]> tabs = new ArrayList<>();

    public BlockEditorScreen() {
        super(Text.literal("Scratch Code Blocks"));
    }

    private static Workspace ws() {
        return ScratchCodeClient.workspace;
    }

    private static Program script(int index) {
        List<Program> s = ws().scripts;
        return index >= 0 && index < s.size() ? s.get(index) : null;
    }

    @Override
    protected void init() {
        long h = client.getWindow().getHandle();
        prevLeft = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        prevRight = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return capturing < 0 && capturingStep == null && editingText == null && editNumStep == null;   // while picking a key, Esc cancels instead of closing
    }

    @Override
    public void removed() {
        capturing = -1;
        capturingStep = null;
        editingText = null;
        if (editNumStep != null) applyNumber();
        ScratchCodeClient.saveProgram();
    }

    // ------------------------------------------------------------------ layout

    private int scriptX1() { return PAL_X + PAL_W + 8; }
    private int scriptX2() { return width - 8; }
    private int areaY2() { return height - 34; }

    private boolean inScript(int x, int y) {
        return x >= scriptX1() && x < scriptX2() && y >= TOP && y < areaY2();
    }

    private boolean inPalette(int x, int y) {
        return x >= PAL_X && x < PAL_X + PAL_W && y >= TOP && y < areaY2();
    }

    // ------------------------------------------------------------------ input

    private boolean shift() {
        long h = client.getWindow().getHandle();
        return GLFW.glfwGetKey(h, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    private Hit hitAt(int mx, int my) {
        for (int i = hits.size() - 1; i >= 0; i--) {   // fields are recorded after their blocks
            Hit hit = hits.get(i);
            if (!hit.contains(mx, my)) continue;
            switch (hit.type) {
                case MENU_ITEM -> { continue; }
                case PALETTE, HAT_TEMPLATE -> { if (!inPalette(mx, my)) continue; }
                case BUTTON -> { }
                default -> { if (!inScript(mx, my)) continue; }
            }
            return hit;
        }
        return null;
    }

    private void handleInput(int mx, int my) {
        long h = client.getWindow().getHandle();
        boolean left = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean right = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean leftDown = left && !prevLeft, rightDown = right && !prevRight, leftUp = !left && prevLeft;
        prevLeft = left;
        prevRight = right;

        if (capturing >= 0) {                // waiting for "press a key"
            if (leftDown || rightDown) capturing = -1;   // clicking anywhere cancels
            else pollCapture(h);
            return;
        }
        if (capturingStep != null) {         // "hold key" block: a key OR a mouse button
            boolean middleDown = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
            if ((leftDown || rightDown || middleDown) && capturingStep.kind != Program.Kind.HOLD_KEY) {
                capturingStep = null;                // "run script" only takes keys: clicking cancels
            } else if (leftDown || rightDown || middleDown) {
                capturingStep.amount = leftDown ? 0 : rightDown ? 1 : 2;
                capturingStep.amount3 = 1;
                capturingStep = null;
            } else {
                pollCapture(h);
            }
            return;
        }

        if (editNumStep != null) {           // typing a number
            if (leftDown || rightDown) applyNumber();        // clicking anywhere finishes
            else pollNumber(h);
            return;
        }

        if (editingText != null) {           // typing into a "say" / "note" block
            if (leftDown || rightDown) editingText = null;   // clicking anywhere finishes
            else pollText(h);
            return;
        }

        if (dragStep != null || dragHatNew || dragHatScript >= 0) {
            if (leftUp) drop(mx, my);
            return;
        }
        if (!leftDown && !rightDown) return;

        if (menu != null) {                 // a drop-down is open: pick an option or close it
            Hit item = null;
            for (Hit h2 : hits) if (h2.type == HitType.MENU_ITEM && h2.contains(mx, my)) item = h2;
            if (item != null && leftDown) choose(menu, item.index);
            menu = null;
            return;
        }

        if (linkFrom != null) {             // connecting an "if done": click a block in the same script
            Hit target = leftDown ? hitAt(mx, my) : null;
            if (target != null && target.step != null && target.script == linkScript && target.step != linkFrom) {
                linkFrom.link = target.step.id;
            }
            linkFrom = null;
            linkScript = -1;
            return;
        }

        Hit hit = hitAt(mx, my);
        if (hit == null) return;
        int sign = leftDown ? 1 : -1;
        int step = shift() ? 10 : 1;
        Program p = script(hit.script);
        Program.Step s = hit.step;

        switch (hit.type) {
            case DIR -> openMenu(hit, DIR_OPTIONS, s.dir.ordinal());
            case MODE -> openMenu(hit, WALK_MODE_OPTIONS, s.kind == Program.Kind.WALK_WALL ? 0 : 1);
            case RMODE -> openMenu(hit, REPEAT_MODE_OPTIONS, s.amount == 0 ? 0 : 1);
            case LINK -> {
                if (leftDown) {                  // start connecting
                    linkFrom = s;
                    linkScript = hit.script;
                } else {
                    s.link = -1;                 // right-click: cut the string
                }
            }
            case NUM, NUM2, NUM3, REPEAT -> {
                if (leftDown) {                  // click, then type the number
                    startCapture(-1, h);
                    editNumStep = s;
                    editNumSeg = switch (hit.type) {
                        case NUM2 -> SegType.NUM2;
                        case NUM3 -> SegType.NUM3;
                        case REPEAT -> SegType.REPEAT;
                        default -> SegType.NUM;
                    };
                    numBuffer = "";
                }
            }
            case KEY -> {
                if (leftDown) startCapture(hit.script, h);
                else if (p != null) p.clearCombo();
            }
            case ATMODE -> openMenu(hit, AT_OPTIONS, s.mode);
            case YMODE -> openMenu(hit, Y_OPTIONS, s.mode);
            case SOUNDSEL -> openMenu(hit, Program.SOUND_NAMES, s.amount);
            case PICK -> {
                if (leftDown) {                  // use what you're holding / looking at right now
                    String id = s.kind == Program.Kind.IF_HOLDING
                            ? ScratchCodeClient.heldItem(client) : ScratchCodeClient.lookedAtBlock(client);
                    if (!id.isEmpty()) s.text = id;
                } else {
                    s.text = "";
                }
            }
            case TEXT -> {
                if (leftDown) {
                    startCapture(-1, h);
                    editingText = s;
                } else {
                    s.text = "";
                }
            }
            case SCRIPTKEY -> {
                if (leftDown) {
                    startCapture(-1, h);
                    capturingStep = s;
                }
            }
            case ELSE_ADD -> {
                if (leftDown) s.hasElse = true;
            }
            case ELSE_REMOVE -> {
                if (leftDown && s.elseChildren.isEmpty()) s.hasElse = false;
            }
            case HOLDKEY -> {
                if (leftDown) {
                    startCapture(-1, h);
                    capturingStep = s;
                }
            }
            case RUN -> {
                if (leftDown && p != null) {
                    ScratchCodeClient.saveProgram();
                    close();
                    ScratchCodeClient.start(client, p);
                }
            }
            case HAT -> {
                if (leftDown) {
                    dragHatScript = hit.script;
                    dragOffX = mx - hit.x1;
                    dragOffY = my - hit.y1 - 7;
                }
            }
            case HAT_TEMPLATE -> {
                if (leftDown && ws().scripts.size() < Workspace.MAX_SCRIPTS) {
                    dragHatNew = true;
                    dragOffX = mx - hit.x1;
                    dragOffY = my - hit.y1 - 7;
                }
            }
            case PALETTE -> {
                if (leftDown) {
                    Program.Step n = TEMPLATES[hit.index].copy();
                    if (n.kind == Program.Kind.IF_Y && client.player != null) {
                        n.amount = MathHelper.floor(client.player.getY());
                    }
                    if (n.kind == Program.Kind.IF_HOLDING) n.text = ScratchCodeClient.heldItem(client);
                    if (n.kind == Program.Kind.IF_LOOKING) n.text = ScratchCodeClient.lookedAtBlock(client);
                    if (n.kind.usesXZ() && client.player != null) {
                        n.amount = MathHelper.floor(client.player.getX());   // start at your spot
                        n.amount2 = MathHelper.floor(client.player.getZ());
                    }
                    if (n.kind == Program.Kind.GO_TO && client.player != null) {
                        n.amount = MathHelper.floor(client.player.getX());
                        n.amount2 = MathHelper.floor(client.player.getY());
                        n.amount3 = MathHelper.floor(client.player.getZ());
                    }
                    startDrag(n, hit, mx, my);
                }
            }
            case BLOCK -> {
                if (leftDown && hit.list != null) {
                    hit.list.remove(s);          // takes anything inside it along too
                    startDrag(s, hit, mx, my);
                }
            }
            case BUTTON -> {
                if (leftDown) pressButton(hit.index);
            }
            case MENU_ITEM -> { }
        }
    }

    // ---- picking a key for a script

    private void startCapture(int scriptIndex, long handle) {
        capturing = scriptIndex;
        captureHeld.clear();
        captureCombo.clear();
        for (int k : VALID_KEYS) if (GLFW.glfwGetKey(handle, k) == GLFW.GLFW_PRESS) captureHeld.add(k);
    }

    private void pollCapture(long handle) {
        for (int k : VALID_KEYS) {
            boolean down = GLFW.glfwGetKey(handle, k) == GLFW.GLFW_PRESS;
            if (!down) {
                captureHeld.remove(k);
                continue;
            }
            if (captureHeld.contains(k)) continue;
            if (capturingStep != null) {
                if (k != GLFW.GLFW_KEY_ESCAPE) {
                    capturingStep.amount = k;
                    capturingStep.amount3 = 0;
                }
                capturingStep = null;
                return;
            }
            // a script's combination: collect keys while they're held (up to 3)
            if (!captureCombo.contains(k) && captureCombo.size() < 3) captureCombo.add(k);
        }
        if (capturingStep != null || captureCombo.isEmpty()) return;
        for (int k : captureCombo) {
            if (GLFW.glfwGetKey(handle, k) == GLFW.GLFW_PRESS) return;      // still holding: keep collecting
        }
        // all let go: save the combination
        Program p = script(capturing);
        int only = captureCombo.size() == 1 ? captureCombo.get(0) : -1;
        if (only == GLFW.GLFW_KEY_ESCAPE) {
            // cancel
        } else if (only == GLFW.GLFW_KEY_BACKSPACE || only == GLFW.GLFW_KEY_DELETE) {
            if (p != null) p.clearCombo();
        } else if (p != null) {
            p.setCombo(captureCombo);
            for (Program other : ws().scripts) if (other != p && other.sameCombo(p)) other.clearCombo();   // no duplicates
        }
        captureCombo.clear();
        capturing = -1;
    }

    // ---- typing numbers

    private void pollNumber(long handle) {
        for (int k : VALID_KEYS) {
            boolean down = GLFW.glfwGetKey(handle, k) == GLFW.GLFW_PRESS;
            if (!down) {
                captureHeld.remove(k);
                continue;
            }
            if (!captureHeld.add(k)) continue;           // already held
            if (k == GLFW.GLFW_KEY_ESCAPE) {             // cancel
                editNumStep = null;
                return;
            }
            if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
                applyNumber();
                return;
            }
            if (k == GLFW.GLFW_KEY_BACKSPACE) {
                if (!numBuffer.isEmpty()) numBuffer = numBuffer.substring(0, numBuffer.length() - 1);
                continue;
            }
            String c = null;
            if (k >= GLFW.GLFW_KEY_0 && k <= GLFW.GLFW_KEY_9) c = String.valueOf((char) ('0' + k - GLFW.GLFW_KEY_0));
            if (k >= GLFW.GLFW_KEY_KP_0 && k <= GLFW.GLFW_KEY_KP_9) c = String.valueOf((char) ('0' + k - GLFW.GLFW_KEY_KP_0));
            if ((k == GLFW.GLFW_KEY_MINUS || k == GLFW.GLFW_KEY_KP_SUBTRACT) && numBuffer.isEmpty()) c = "-";
            if ((k == GLFW.GLFW_KEY_PERIOD || k == GLFW.GLFW_KEY_KP_DECIMAL) && !numBuffer.contains(".")) c = ".";
            if (c != null && numBuffer.length() < 10) numBuffer += c;
        }
    }

    /** Is this box in tenths of a second (so it takes a decimal point)? */
    private static boolean isTenths(Program.Step s, SegType box) {
        return s.kind == Program.Kind.WAIT_RANDOM || (s.kind == Program.Kind.HOLD_KEY && box == SegType.NUM2);
    }

    /** Puts the typed number into the box (kept in range like before); empty = no change. */
    private void applyNumber() {
        Program.Step s = editNumStep;
        SegType box = editNumSeg;
        editNumStep = null;
        if (s == null || numBuffer.isEmpty() || numBuffer.equals("-") || numBuffer.equals(".")) return;
        int value;
        try {
            double d = Double.parseDouble(numBuffer);
            if (isTenths(s, box)) d *= 10;
            value = (int) Math.max(-2_000_000_000L, Math.min(2_000_000_000L, Math.round(d)));
        } catch (NumberFormatException e) {
            return;
        }
        int current = switch (box) {
            case NUM2 -> s.amount2;
            case NUM3 -> s.amount3;
            default -> s.amount;
        };
        int delta = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) value - current));
        switch (box) {
            case NUM2 -> changeAmount2(s, delta);
            case NUM3 -> changeAmount3(s, delta);
            case REPEAT -> changeRepeats(s, delta);
            default -> {
                if (s.kind == Program.Kind.SET_ANGLE) {          // wrap any angle into -180..180
                    s.amount = Math.floorMod(value + 180, 360) - 180;
                } else {
                    changeAmount(s, delta);
                }
            }
        }
    }

    // ---- typing words into "say" / "note" blocks

    private void pollText(long handle) {
        boolean shift = shift();
        for (int k : VALID_KEYS) {
            boolean down = GLFW.glfwGetKey(handle, k) == GLFW.GLFW_PRESS;
            if (!down) {
                captureHeld.remove(k);
                continue;
            }
            if (!captureHeld.add(k)) continue;           // already held
            if (k == GLFW.GLFW_KEY_ESCAPE || k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
                editingText = null;
                return;
            }
            String t = editingText.text;
            if (k == GLFW.GLFW_KEY_BACKSPACE) {
                if (!t.isEmpty()) editingText.text = t.substring(0, t.length() - 1);
                continue;
            }
            String c = charFor(k, shift);
            if (c != null && t.length() < Program.MAX_TEXT) editingText.text = t + c;
        }
    }

    private static String charFor(int k, boolean shift) {
        if (k >= GLFW.GLFW_KEY_A && k <= GLFW.GLFW_KEY_Z) {
            char c = (char) ('a' + (k - GLFW.GLFW_KEY_A));
            return String.valueOf(shift ? Character.toUpperCase(c) : c);
        }
        if (k >= GLFW.GLFW_KEY_0 && k <= GLFW.GLFW_KEY_9) {
            int d = k - GLFW.GLFW_KEY_0;
            return shift ? String.valueOf(")!@#$%^&*(".charAt(d)) : String.valueOf((char) ('0' + d));
        }
        if (k >= GLFW.GLFW_KEY_KP_0 && k <= GLFW.GLFW_KEY_KP_9) return String.valueOf((char) ('0' + k - GLFW.GLFW_KEY_KP_0));
        return switch (k) {
            case GLFW.GLFW_KEY_SPACE -> " ";
            case GLFW.GLFW_KEY_MINUS -> shift ? "_" : "-";
            case GLFW.GLFW_KEY_PERIOD -> shift ? ">" : ".";
            case GLFW.GLFW_KEY_COMMA -> shift ? "<" : ",";
            case GLFW.GLFW_KEY_SLASH -> shift ? "?" : "/";
            case GLFW.GLFW_KEY_APOSTROPHE -> shift ? "\"" : "'";
            case GLFW.GLFW_KEY_SEMICOLON -> shift ? ":" : ";";
            case GLFW.GLFW_KEY_EQUAL -> shift ? "+" : "=";
            default -> null;
        };
    }

    private static String comboText(List<Integer> keys) {
        StringBuilder sb = new StringBuilder();
        for (int k : keys) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(ScratchCodeClient.keyName(k));
        }
        return sb.toString();
    }

    private static int freeKey() {
        for (int k : DEFAULT_KEYS) {
            boolean used = false;
            for (Program p : ws().scripts) if (p.combo().length == 1 && p.key == k) used = true;
            if (!used) return k;
        }
        return -1;
    }

    // ---- drop-down menus

    private void openMenu(Hit hit, List<String> options, int selected) {
        int w = hit.x2 - hit.x1;
        for (String o : options) w = Math.max(w, textRenderer.getWidth("\u2714 " + o) + 12);
        menu = new Menu(hit.type, hit.step, hit.x1, hit.y2 + 1, w, options, selected);
    }

    private void choose(Menu m, int option) {
        Program.Step s = m.step;
        if (s == null) return;
        switch (m.kind) {
            case DIR -> s.dir = Program.Dir.values()[option];
            case ATMODE, YMODE -> s.mode = option;
            case SOUNDSEL -> s.amount = option;
            case MODE -> s.kind = option == 0 ? Program.Kind.WALK_WALL : Program.Kind.WALK_BLOCKS;
            case RMODE -> {
                if (option == 0) {
                    if (s.amount > 0) rememberedRepeats = s.amount;
                    s.amount = 0;
                } else if (s.amount == 0) {
                    s.amount = Math.max(1, rememberedRepeats);
                }
            }
            default -> { }
        }
    }

    // ---- number fields

    private void changeAmount3(Program.Step s, int delta) {
        s.amount3 = Program.clamp(s.amount3 + delta, -Program.COORD_LIMIT, Program.COORD_LIMIT);
    }

    private void changeAmount(Program.Step s, int delta) {
        if (s.kind.usesXZ() || s.kind == Program.Kind.GO_TO) {
            s.amount = Program.clamp(s.amount + delta, -Program.COORD_LIMIT, Program.COORD_LIMIT);
        } else if (s.kind == Program.Kind.SELECT_SLOT) {
            s.amount = Program.clamp(s.amount + delta, 1, 9);
        } else if (s.kind == Program.Kind.IF_HEALTH) {
            s.amount = Program.clamp(s.amount + delta, 1, 20);
        } else if (s.kind == Program.Kind.IF_Y) {
            s.amount = Program.clamp(s.amount + delta, Program.MIN_Y, Program.MAX_Y);
        } else if (s.kind == Program.Kind.SET_ANGLE) {            // yaw wraps around
            int v = s.amount + delta;
            while (v > 180) v -= 360;
            while (v < -180) v += 360;
            s.amount = v;
        } else if (s.kind == Program.Kind.WAIT_RANDOM) {          // tenths of a second
            s.amount = Program.clamp(s.amount + delta, 0, Program.MAX_TENTHS);
            if (s.amount2 < s.amount) s.amount2 = s.amount;
        } else {
            s.amount = Program.clamp(s.amount + delta, 1, Program.MAX_AMOUNT);
        }
    }

    private void changeAmount2(Program.Step s, int delta) {
        if (s.kind == Program.Kind.HOLD_KEY) {              // tenths of a second, at least 0.1
            s.amount2 = Program.clamp(s.amount2 + delta, 1, Program.MAX_TENTHS);
            return;
        }
        if (s.kind == Program.Kind.GO_TO) {
            s.amount2 = Program.clamp(s.amount2 + delta, Program.MIN_Y, Program.MAX_Y);
            return;
        }
        if (s.kind.usesXZ()) {
            s.amount2 = Program.clamp(s.amount2 + delta, -Program.COORD_LIMIT, Program.COORD_LIMIT);
            return;
        }
        if (s.kind == Program.Kind.SET_ANGLE) {            // pitch: -90 up .. 90 down
            s.amount2 = Program.clamp(s.amount2 + delta, -90, 90);
            return;
        }
        s.amount2 = Program.clamp(s.amount2 + delta, 0, Program.MAX_TENTHS);
        if (s.amount > s.amount2) s.amount = s.amount2;
    }

    private void changeRepeats(Program.Step s, int delta) {
        if (s.amount == 0) return;                                 // forever: use the drop-down
        s.amount = Program.clamp(s.amount + delta, 1, 100000);
        rememberedRepeats = s.amount;
    }

    // ---- dragging

    private void startDrag(Program.Step s, Hit hit, int mx, int my) {
        dragStep = s;
        dragOffX = mx - hit.x1;
        dragOffY = my - hit.y1;
    }

    private void drop(int mx, int my) {
        List<Program> scripts = ws().scripts;
        if (dragStep != null) {
            DropPoint d = bestDrop;
            if (inScript(mx, my) && d != null && d.list.size() < Program.MAX_STEPS) {
                d.list.add(Math.min(d.index, d.list.size()), dragStep);
            }
            // dropped anywhere else (e.g. back on the palette) = deleted
            dragStep = null;
        } else if (dragHatNew) {
            if (inScript(mx, my) && scripts.size() < Workspace.MAX_SCRIPTS) {
                Program np = new Program();      // new scripts start empty: just the hat
                np.key = freeKey();
                np.key2 = -1;
                np.key3 = -1;
                scripts.add(np);
            }
            dragHatNew = false;
        } else if (dragHatScript >= 0) {
            if (!inScript(mx, my) && dragHatScript < scripts.size()) scripts.remove(dragHatScript);   // delete script
            dragHatScript = -1;
        }
        bestDrop = null;
    }

    private void pressButton(int index) {
        switch (index) {
            case 0 -> ScratchCodeClient.stop(client, "stopped");
            case 1 -> {                       // Reset
                ws().scripts.clear();
                ws().scripts.addAll(Workspace.defaults().scripts);
                scrollY = 0;
            }
            case 2 -> close();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int mx = (int) mouseX, my = (int) mouseY;
        int dir = verticalAmount > 0 ? 1 : verticalAmount < 0 ? -1 : 0;
        if (dir == 0) return false;
        if (menu != null || capturing >= 0 || capturingStep != null || editingText != null
                || editNumStep != null) return true;
        if (inPalette(mx, my)) {
            int max = Math.max(0, palContentHeight - (areaY2() - TOP) + 8);
            palScroll = Program.clamp(palScroll - dir * 16, 0, max);
            return true;
        }
        if (inScript(mx, my)) {
            int max = Math.max(0, contentHeight - (areaY2() - TOP) + 16);
            scrollY = Program.clamp(scrollY - dir * 16, 0, max);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        handleInput(mouseX, mouseY);
        super.render(ctx, mouseX, mouseY, delta);   // dimmed / blurred background
        nextHits = new ArrayList<>();
        blockRects.clear();
        sockets.clear();
        dropPoints.clear();

        ctx.drawCenteredTextWithShadow(textRenderer, "Scratch Code \u2014 Block Editor", width / 2, 8, WHITE);

        drawPalette(ctx);
        drawScripts(ctx, mouseX, mouseY);
        drawButtons(ctx, mouseX, mouseY);

        if (dragStep != null) {
            ctx.createNewRootLayer();
            tabs.clear();
            drawBlock(ctx, dragStep, null, mouseX - dragOffX, mouseY - dragOffY, -1, false, SCRIPT_BG, null);
            for (int[] t : tabs) ctx.fill(t[0] + 9, t[1], t[0] + 19, t[1] + 2, t[2]);
            tabs.clear();
        }
        if (dragHatNew || dragHatScript >= 0) {
            ctx.createNewRootLayer();
            drawHat(ctx, mouseX - dragOffX, mouseY - dragOffY, script(dragHatScript), -1, false);
        }
        if (menu != null) drawMenu(ctx, mouseX, mouseY);
        hits = nextHits;
    }

    private void drawPalette(DrawContext ctx) {
        int x1 = PAL_X, y1 = TOP, x2 = PAL_X + PAL_W, y2 = areaY2();
        ctx.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, BORDER);
        ctx.fill(x1, y1, x2, y2, PALETTE_BG);
        ctx.enableScissor(x1, y1, x2, y2);

        int y = y1 + 6 - palScroll;
        ctx.drawText(textRenderer, "Events", x1 + 6, y, HINT, false);
        y += 12 + 7;                                     // room for the hat's hump
        int hw = drawHat(ctx, x1 + 6, y, null, -1, false);
        nextHits.add(new Hit(x1 + 6, y - 7, x1 + 6 + hw, y + BH, HitType.HAT_TEMPLATE, -1, null, null, -1));
        y += BH + 8;

        tabs.clear();
        for (int i = 0; i < TEMPLATES.length; i++) {
            String section = SECTIONS.get(i);
            if (section != null) {
                if (i > 0) y += 3;
                ctx.drawText(textRenderer, section, x1 + 6, y, HINT, false);
                y += 12;
            }
            int[] size = new int[1];
            int bottom = drawBlock(ctx, TEMPLATES[i], null, x1 + 6, y, -1, false, PALETTE_BG, size);
            nextHits.add(new Hit(x1 + 6, y, x1 + 6 + size[0], bottom, HitType.PALETTE, -1, null, null, i));
            y = bottom + 5;
        }
        for (int[] t : tabs) ctx.fill(t[0] + 9, t[1], t[0] + 19, t[1] + 2, t[2]);
        tabs.clear();
        y += 6;

        String[] tips = {
                "Drag \"when key pressed\"", "out to add a script.",
                "Click its key box, hold the", "keys (up to 3, e.g. F3 + P),", "then let go. \u25B6 runs it.",
                "Blocks snap under a hat", "or inside repeat / if.",
                "Drag a block or hat back", "here to delete it.",
                "Click \u25BE to open a list.", "Numbers: click the box,", "type, then press Enter.",
                "If done: click its \u25CF,", "then a block to tie it.", "Right-click \u25CF to untie.",
                "Hold key: click its key box,", "then press a key or click", "a mouse button.",
                "If blocks: click \"+ else\" on", "the bottom to add an else.",
                "If holding / looking at:", "click the box to use what", "you hold / look at now.",
                "Say / note: click the box,", "type, then press Enter.",
                "Scroll here for more.",
        };
        for (String tip : tips) {
            ctx.drawText(textRenderer, tip, x1 + 6, y, HINT, false);
            y += 10;
        }
        palContentHeight = y + palScroll - y1;
        ctx.disableScissor();
    }

    /** Draws a "when [key] pressed" hat block and returns its width. */
    private int drawHat(DrawContext ctx, int x, int y, Program p, int si, boolean editable) {
        String keyText;
        if (p == null) keyText = "?";
        else if (capturing == si && si >= 0) keyText = captureCombo.isEmpty() ? "hold keys" : comboText(captureCombo) + " ...";
        else keyText = p.comboName();
        List<Seg> segs = editable
                ? List.of(new Seg(SegType.LABEL, "when"), new Seg(SegType.KEY, keyText),
                new Seg(SegType.LABEL, "key pressed"), new Seg(SegType.RUN, "\u25B6"))
                : List.of(new Seg(SegType.LABEL, "when"), new Seg(SegType.KEY, keyText),
                new Seg(SegType.LABEL, "key pressed"));
        int w = Math.max(130, segmentsWidth(segs) + 16);
        ctx.fill(x + 6, y - 7, x + 54, y - 5, EVENTS);
        ctx.fill(x + 2, y - 5, x + 62, y - 2, EVENTS);
        ctx.fill(x, y - 2, x + w, y + BH, EVENTS);
        ctx.fill(x, y + BH - 1, x + w, y + BH, EVENTS_DARK);
        if (editable) nextHits.add(0, new Hit(x, y - 7, x + w, y + BH, HitType.HAT, si, null, null, -1));
        drawSegments(ctx, x + 8, y, segs, si, null, editable, EVENTS_DARK);
        return w;
    }

    private void drawScripts(DrawContext ctx, int mx, int my) {
        int x1 = scriptX1(), y1 = TOP, x2 = scriptX2(), y2 = areaY2();
        ctx.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, BORDER);
        ctx.fill(x1, y1, x2, y2, SCRIPT_BG);
        ctx.enableScissor(x1, y1, x2, y2);

        List<Program> scripts = ws().scripts;
        int x = x1 + 14;
        int startY = y1 + 16 - scrollY;
        int y = startY;

        for (int si = 0; si < scripts.size(); si++) {
            Program p = scripts.get(si);
            tabs.clear();
            drawHat(ctx, x, y, p, si, true);
            int listTop = y + BH;
            if (!p.steps.isEmpty()) tabs.add(new int[]{x, listTop, EVENTS});
            int bottom = drawList(ctx, p.steps, x, listTop, si, true, SCRIPT_BG);
            if (p.steps.isEmpty()) {
                ctx.drawText(textRenderer, "drag blocks under here", x + 8, listTop + 6, HINT, false);
                bottom = listTop + BH;
            }
            for (int[] t : tabs) ctx.fill(t[0] + 9, t[1], t[0] + 19, t[1] + 2, t[2]);
            tabs.clear();
            drawStrings(ctx, p, mx, my);
            y = bottom + SCRIPT_GAP;
        }
        if (scripts.isEmpty()) {
            ctx.drawText(textRenderer, "Drag a \"when key pressed\" block here to start a script.",
                    x, y, HINT, false);
            y += 20;
        }
        contentHeight = y - startY;

        // where would the dragged block land? show a line there
        bestDrop = null;
        if (dragStep != null && inScript(mx, my)) {
            int bx = mx - dragOffX, by = my - dragOffY;
            int best = Integer.MAX_VALUE;
            for (DropPoint d : dropPoints) {
                int dx = Math.abs(bx - d.x), dy = Math.abs(by - d.y);
                if (dx > 70 || dy > 18) continue;
                int score = dy * 3 + dx;
                if (score < best) {
                    best = score;
                    bestDrop = d;
                }
            }
            if (bestDrop != null) ctx.fill(bestDrop.x, bestDrop.y - 2, bestDrop.x + 120, bestDrop.y + 2, DROP_LINE);
        }
        ctx.disableScissor();
    }

    /** Draws a list of blocks stacked from (x, y) and returns the y under the last one. */
    private int drawList(DrawContext ctx, List<Program.Step> list, int x, int y, int si, boolean live, int bg) {
        for (int i = 0; i <= list.size(); i++) {
            if (live) dropPoints.add(new DropPoint(list, i, x, y));
            if (i == list.size()) break;
            y = drawBlock(ctx, list.get(i), list, x, y, si, live, bg, null);
        }
        return y;
    }

    /**
     * Draws one block (and, for repeat / if, everything inside it). Returns the y under it.
     * live = it's part of a script (clickable, droppable); size[0] gets its width if given.
     */
    private int drawBlock(DrawContext ctx, Program.Step s, List<Program.Step> list, int x, int y, int si,
                          boolean live, int bg, int[] size) {
        int color = colorOf(s), dark = darkOf(s);
        List<Seg> segs = segmentsFor(s);

        if (!s.kind.isContainer()) {
            int w = Math.max(120, segmentsWidth(segs) + 16);
            ctx.fill(x, y, x + w, y + BH, color);
            ctx.fill(x, y + BH - 1, x + w, y + BH, dark);
            ctx.fill(x + 8, y, x + 20, y + 2, bg);           // notch on top
            tabs.add(new int[]{x, y + BH, color});            // tab underneath
            if (live) {
                nextHits.add(0, new Hit(x, y, x + w, y + BH, HitType.BLOCK, si, s, list, -1));
                blockRects.put(s.id, new int[]{x, y, x + w, color});
            }
            drawSegments(ctx, x + 8, y, segs, si, s, live, dark);
            if (size != null) size[0] = w;
            return y + BH;
        }

        // a C block: top bar, the blocks inside, (an "else" bar and its blocks), then the bottom bar
        int w = Math.max(150, segmentsWidth(segs) + 16);
        ctx.fill(x, y, x + w, y + BH, color);
        ctx.fill(x + 8, y, x + 20, y + 2, bg);               // notch on top
        int innerTop = y + BH;
        int innerBottom = drawSection(ctx, s.children, x, innerTop, si, live, bg, color);
        if (live) {
            nextHits.add(0, new Hit(x, y, x + w, innerTop, HitType.BLOCK, si, s, list, -1));
            nextHits.add(0, new Hit(x, innerTop, x + ARM, innerBottom, HitType.BLOCK, si, s, list, -1));
        }
        int bottomTop = innerBottom;
        if (s.hasElse) {
            ctx.fill(x, innerBottom, x + w, innerBottom + BH, color);           // "else" bar
            ctx.drawText(textRenderer, "else", x + 8, innerBottom + (BH - 8) / 2, WHITE, false);
            if (live) {
                nextHits.add(0, new Hit(x, innerBottom, x + w, innerBottom + BH, HitType.BLOCK, si, s, list, -1));
                if (s.elseChildren.isEmpty()) {                                // x removes an empty else
                    int bx = x + w - 20;
                    pill(ctx, bx, innerBottom + 4, 14, BH - 8, dark);
                    ctx.drawText(textRenderer, "\u00D7", bx + 4, innerBottom + (BH - 8) / 2, WHITE, false);
                    nextHits.add(new Hit(bx, innerBottom + 4, bx + 14, innerBottom + BH - 4,
                            HitType.ELSE_REMOVE, si, s, null, -1));
                }
            }
            int elseTop = innerBottom + BH;
            int elseBottom = drawSection(ctx, s.elseChildren, x, elseTop, si, live, bg, color);
            if (live) nextHits.add(0, new Hit(x, elseTop, x + ARM, elseBottom, HitType.BLOCK, si, s, list, -1));
            bottomTop = elseBottom;
        }
        ctx.fill(x, bottomTop, x + w, bottomTop + C_BOTTOM, color);              // bottom bar
        ctx.fill(x, bottomTop + C_BOTTOM - 1, x + w, bottomTop + C_BOTTOM, dark);
        tabs.add(new int[]{x, bottomTop + C_BOTTOM, color});
        if (live) {
            nextHits.add(0, new Hit(x, bottomTop, x + w, bottomTop + C_BOTTOM, HitType.BLOCK, si, s, list, -1));
            blockRects.put(s.id, new int[]{x, y, x + w, color});
            if (s.kind.isConditional() && !s.hasElse) {                         // "+ else" button
                String t = "+ else";
                int tw = textRenderer.getWidth(t), bx = x + w - tw - 8;
                ctx.drawText(textRenderer, t, bx, bottomTop + 2, WHITE, false);
                nextHits.add(new Hit(bx - 2, bottomTop, bx + tw + 2, bottomTop + C_BOTTOM,
                        HitType.ELSE_ADD, si, s, null, -1));
            }
        }
        // darker line down the left side, from where the C block starts to where it ends
        ctx.fill(x + 3, y + 3, x + 5, bottomTop + C_BOTTOM - 3, dark);
        drawSegments(ctx, x + 8, y, segs, si, s, live, dark);
        if (size != null) size[0] = w;
        return bottomTop + C_BOTTOM;
    }

    /** One section inside a C block, with its left arm. Returns the y under it. */
    private int drawSection(DrawContext ctx, List<Program.Step> section, int x, int top, int si,
                            boolean live, int bg, int color) {
        if (!section.isEmpty()) tabs.add(new int[]{x + ARM, top, color});   // tab into the first block
        int bottom = drawList(ctx, section, x + ARM, top, si, live, bg);
        if (section.isEmpty()) bottom = top + EMPTY_C;
        ctx.fill(x, top, x + ARM, bottom, color);                          // left arm
        return bottom;
    }

    /** The coloured "strings" from each "if done" socket in a script to the block it's tied to. */
    private void drawStrings(DrawContext ctx, Program p, int mx, int my) {
        List<Program.Step> all = p.allSteps();
        int laneBase = 0;
        for (Program.Step s : all) {
            int[] rect = blockRects.get(s.id);
            if (rect != null) laneBase = Math.max(laneBase, rect[2]);
        }
        laneBase += 8;
        int lane = 0;
        for (Program.Step s : all) {
            if (s.kind != Program.Kind.IF || s.link < 0 || p.byId(s.link) == null) continue;
            int[] sock = sockets.get(s.id), target = blockRects.get(s.link);
            if (sock == null || target == null) continue;
            int lx = laneBase + lane * 5;
            lane++;
            int ty = target[1] + BH / 2, color = target[3];
            hLine(ctx, sock[0], lx, sock[1], color);
            vLine(ctx, lx, sock[1], ty, color);
            hLine(ctx, target[2], lx, ty, color);
            ctx.fill(target[2] - 2, ty - 2, target[2] + 1, ty + 3, color);   // knot on the target block
        }
        if (linkFrom != null && all.contains(linkFrom) && sockets.containsKey(linkFrom.id)) {   // string following the mouse
            int[] sock = sockets.get(linkFrom.id);
            hLine(ctx, sock[0], mx, sock[1], FIELD_TEXT);
            vLine(ctx, mx, sock[1], my, FIELD_TEXT);
        }
    }

    private static void hLine(DrawContext ctx, int xa, int xb, int y, int color) {
        ctx.fill(Math.min(xa, xb), y - 1, Math.max(xa, xb) + 1, y + 1, color);
    }

    private static void vLine(DrawContext ctx, int x, int ya, int yb, int color) {
        ctx.fill(x - 1, Math.min(ya, yb), x + 1, Math.max(ya, yb) + 1, color);
    }

    private static int colorOf(Program.Step s) {
        return switch (s.kind.category()) {
            case MOTION -> MOTION;
            case CONTROL -> CONTROL;
            case INVENTORY -> LOOKS;
            case SENSING -> SENSING;
            case SOUND -> SOUND;
            case NOTE -> NOTE;
        };
    }

    private static int darkOf(Program.Step s) {
        return switch (s.kind.category()) {
            case MOTION -> MOTION_DARK;
            case CONTROL -> CONTROL_DARK;
            case INVENTORY -> LOOKS_DARK;
            case SENSING -> SENSING_DARK;
            case SOUND -> SOUND_DARK;
            case NOTE -> NOTE_DARK;
        };
    }

    private void drawMenu(DrawContext ctx, int mx, int my) {
        ctx.createNewRootLayer();
        Menu m = menu;
        int h = m.options.size() * ITEM_H + 4;
        int y = m.y;
        if (y + h > height - 2) y = Math.max(2, m.y - h - 18);   // flip up if it would go off screen
        int color = m.step != null ? colorOf(m.step) : MOTION;
        int dark = m.step != null ? darkOf(m.step) : MOTION_DARK;
        ctx.fill(m.x - 1, y - 1, m.x + m.w + 1, y + h + 1, dark);
        ctx.fill(m.x, y, m.x + m.w, y + h, color);
        for (int i = 0; i < m.options.size(); i++) {
            int iy = y + 2 + i * ITEM_H;
            boolean hover = mx >= m.x && mx < m.x + m.w && my >= iy && my < iy + ITEM_H;
            if (hover) ctx.fill(m.x + 1, iy, m.x + m.w - 1, iy + ITEM_H, dark);
            String label = (i == m.selected ? "\u2714 " : "    ") + m.options.get(i);
            ctx.drawText(textRenderer, label, m.x + 4, iy + 3, WHITE, false);
            nextHits.add(new Hit(m.x, iy, m.x + m.w, iy + ITEM_H, HitType.MENU_ITEM, -1, null, null, i));
        }
    }

    private void drawButtons(DrawContext ctx, int mx, int my) {
        int bw = 64, gap = 8;
        int total = BUTTONS.length * bw + (BUTTONS.length - 1) * gap;
        int x = width / 2 - total / 2, y = height - 27;
        for (int i = 0; i < BUTTONS.length; i++) {
            int bx = x + i * (bw + gap);
            boolean hover = mx >= bx && mx < bx + bw && my >= y && my < y + 20;
            int c = BUTTON_COLORS[i];
            ctx.fill(bx + 1, y, bx + bw - 1, y + 20, c);
            ctx.fill(bx, y + 1, bx + bw, y + 19, c);
            ctx.fill(bx + 1, y + 19, bx + bw - 1, y + 20, darken(c));
            if (hover) ctx.fill(bx, y + 1, bx + bw, y + 19, 0x30FFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer, BUTTONS[i], bx + bw / 2, y + 6, WHITE);
            nextHits.add(new Hit(bx, y, bx + bw, y + 20, HitType.BUTTON, -1, null, null, i));
        }
    }

    // ------------------------------------------------------------------ block text

    private List<Seg> segmentsFor(Program.Step s) {
        List<Seg> segs = rawSegmentsFor(s);
        if (s != editNumStep) return segs;
        List<Seg> out = new ArrayList<>();
        for (Seg seg : segs) out.add(seg.type == editNumSeg ? new Seg(seg.type, numBuffer + "_") : seg);
        return out;
    }

    private List<Seg> rawSegmentsFor(Program.Step s) {
        return switch (s.kind) {
            case WALK_WALL -> List.of(new Seg(SegType.LABEL, "walk"), new Seg(SegType.DIR, s.dir.label()),
                    new Seg(SegType.MODE, "until wall"));
            case WALK_BLOCKS -> List.of(new Seg(SegType.LABEL, "walk"), new Seg(SegType.DIR, s.dir.label()),
                    new Seg(SegType.NUM, String.valueOf(s.amount)),
                    new Seg(SegType.MODE, "blocks"));
            case WAIT -> List.of(new Seg(SegType.LABEL, "wait"), new Seg(SegType.NUM, String.valueOf(s.amount)),
                    new Seg(SegType.LABEL, s.amount == 1 ? "second" : "seconds"));
            case REPEAT -> s.amount == 0
                    ? List.of(new Seg(SegType.LABEL, "repeat"), new Seg(SegType.RMODE, "forever"))
                    : List.of(new Seg(SegType.LABEL, "repeat"), new Seg(SegType.REPEAT, String.valueOf(s.amount)),
                    new Seg(SegType.RMODE, "times"));
            case IF -> List.of(new Seg(SegType.LABEL, "if"), new Seg(SegType.LABEL, "done"),
                    new Seg(SegType.LINK, ""));
            case IF_AT -> List.of(new Seg(SegType.LABEL, "if"), new Seg(SegType.ATMODE, s.mode == 1 ? "not at" : "at"),
                    new Seg(SegType.LABEL, "x"), new Seg(SegType.NUM, String.valueOf(s.amount)),
                    new Seg(SegType.LABEL, "z"), new Seg(SegType.NUM2, String.valueOf(s.amount2)));
            case IF_WALL -> List.of(new Seg(SegType.LABEL, "if touching wall"));
            case REPEAT_UNTIL -> List.of(new Seg(SegType.LABEL, "repeat until at x"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)), new Seg(SegType.LABEL, "z"),
                    new Seg(SegType.NUM2, String.valueOf(s.amount2)));
            case WAIT_UNTIL -> List.of(new Seg(SegType.LABEL, "wait until at x"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)), new Seg(SegType.LABEL, "z"),
                    new Seg(SegType.NUM2, String.valueOf(s.amount2)));
            case STOP_SCRIPT -> List.of(new Seg(SegType.LABEL, "stop script"));
            case RUN_SCRIPT -> List.of(new Seg(SegType.LABEL, "run"),
                    new Seg(SegType.SCRIPTKEY, capturingStep == s ? "press a key" : ScratchCodeClient.keyName(s.amount)),
                    new Seg(SegType.LABEL, "script"));
            case SELECT_SLOT -> List.of(new Seg(SegType.LABEL, "select slot"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)));
            case IF_HOLDING -> List.of(new Seg(SegType.LABEL, "if holding"),
                    new Seg(SegType.PICK, s.text.isEmpty() ? "click: held item" : Program.prettyId(s.text)));
            case IF_INV_FULL -> List.of(new Seg(SegType.LABEL, "if inventory full"));
            case IF_CONTAINER_FULL -> List.of(new Seg(SegType.LABEL, "if container full"));
            case DEPOSIT -> List.of(new Seg(SegType.LABEL, "put all"), new Seg(SegType.TEXT, textShown(s)),
                    new Seg(SegType.LABEL, "in container"));
            case CLOSE_CONTAINER -> List.of(new Seg(SegType.LABEL, "close container"));
            case WAIT_OPEN -> List.of(new Seg(SegType.LABEL, "wait for container, up to"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)), new Seg(SegType.LABEL, "s"));
            case IF_LOOKING -> List.of(new Seg(SegType.LABEL, "if looking at"),
                    new Seg(SegType.PICK, s.text.isEmpty() ? "click: block" : Program.prettyId(s.text)));
            case IF_HEALTH -> List.of(new Seg(SegType.LABEL, "if health below"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)), new Seg(SegType.LABEL, "\u2764"));
            case IF_Y -> List.of(new Seg(SegType.LABEL, "if y"), new Seg(SegType.YMODE, s.mode == 1 ? "above" : "below"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)));
            case SAY -> List.of(new Seg(SegType.LABEL, "say"), new Seg(SegType.TEXT, textShown(s)));
            case SOUND -> List.of(new Seg(SegType.LABEL, "play sound"),
                    new Seg(SegType.SOUNDSEL, Program.SOUND_NAMES.get(Program.clamp(s.amount, 0, 2))));
            case COMMENT -> List.of(new Seg(SegType.LABEL, "note:"), new Seg(SegType.TEXT, textShown(s)));
            case GO_TO -> List.of(new Seg(SegType.LABEL, "go to x"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)), new Seg(SegType.LABEL, "y"),
                    new Seg(SegType.NUM2, String.valueOf(s.amount2)), new Seg(SegType.LABEL, "z"),
                    new Seg(SegType.NUM3, String.valueOf(s.amount3)));
            case STOP_AT -> List.of(new Seg(SegType.LABEL, "stop at x"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)), new Seg(SegType.LABEL, "z"),
                    new Seg(SegType.NUM2, String.valueOf(s.amount2)));
            case SET_ANGLE -> List.of(new Seg(SegType.LABEL, "set angle x"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)), new Seg(SegType.LABEL, "y"),
                    new Seg(SegType.NUM2, String.valueOf(s.amount2)));
            case HOLD_KEY -> List.of(new Seg(SegType.LABEL, "hold"),
                    new Seg(SegType.HOLDKEY, capturingStep == s ? "press a key / click"
                            : Program.inputName(s.amount, s.amount3 == 1)),
                    new Seg(SegType.LABEL, "for"), new Seg(SegType.NUM2, Program.tenths(s.amount2)),
                    new Seg(SegType.LABEL, "s"));
            case WAIT_RANDOM -> List.of(new Seg(SegType.LABEL, "random wait"),
                    new Seg(SegType.NUM, Program.tenths(s.amount)), new Seg(SegType.LABEL, "to"),
                    new Seg(SegType.NUM2, Program.tenths(s.amount2)), new Seg(SegType.LABEL, "s"));
        };
    }

    private String textShown(Program.Step s) {
        if (editingText == s) return s.text + "_";
        return s.text.isEmpty() ? "click to type" : s.text;
    }

    private int segWidth(Seg seg) {
        return switch (seg.type) {
            case LABEL -> textRenderer.getWidth(seg.text);
            case DIR, MODE, RMODE, ATMODE, YMODE, SOUNDSEL -> textRenderer.getWidth(seg.text + " \u25BE") + 10;
            case NUM, NUM2, NUM3, REPEAT, KEY, HOLDKEY, PICK, TEXT, SCRIPTKEY ->
                    Math.max(20, textRenderer.getWidth(seg.text) + 12);
            case LINK -> 14;
            case RUN -> 16;
        };
    }

    private int segmentsWidth(List<Seg> segs) {
        int w = 0;
        for (Seg s : segs) w += segWidth(s) + 5;
        return w - 5;
    }

    /** Draws a block's words and fields; when editable, records their click areas. */
    private void drawSegments(DrawContext ctx, int x, int y, List<Seg> segs, int si, Program.Step step,
                              boolean editable, int dark) {
        int tx = x, ty = y + (BH - 8) / 2;
        Program p = script(si);
        for (Seg seg : segs) {
            int w = segWidth(seg);
            int fy1 = y + 4, fy2 = y + BH - 4;
            switch (seg.type) {
                case LABEL -> ctx.drawText(textRenderer, seg.text, tx, ty, WHITE, false);
                case DIR, MODE, RMODE, ATMODE, YMODE, SOUNDSEL -> {
                    pill(ctx, tx, fy1, w, BH - 8, dark);
                    ctx.drawText(textRenderer, seg.text + " \u25BE", tx + 5, ty, WHITE, false);
                    HitType ht = switch (seg.type) {
                        case DIR -> HitType.DIR;
                        case MODE -> HitType.MODE;
                        case ATMODE -> HitType.ATMODE;
                        case YMODE -> HitType.YMODE;
                        case SOUNDSEL -> HitType.SOUNDSEL;
                        default -> HitType.RMODE;
                    };
                    if (editable && step != null) nextHits.add(new Hit(tx, fy1, tx + w, fy2, ht, si, step, null, -1));
                }
                case LINK -> {
                    int cx = tx + 7, cyy = y + BH / 2;
                    Program.Step linked = editable && p != null && step != null && step.link >= 0
                            ? p.byId(step.link) : null;
                    int fillColor = linked != null ? colorOf(linked) : WHITE;
                    pill(ctx, cx - 6, cyy - 6, 12, 12, FIELD_TEXT);   // socket ring
                    pill(ctx, cx - 4, cyy - 4, 8, 8, fillColor);
                    if (editable && step != null) {
                        sockets.put(step.id, new int[]{cx + 6, cyy});
                        nextHits.add(new Hit(cx - 7, cyy - 7, cx + 7, cyy + 7, HitType.LINK, si, step, null, -1));
                    }
                }
                case RUN -> {
                    pill(ctx, tx, fy1, w, BH - 8, GREEN);
                    ctx.drawText(textRenderer, seg.text, tx + (w - textRenderer.getWidth(seg.text)) / 2, ty,
                            WHITE, false);
                    if (editable && p != null) nextHits.add(new Hit(tx, fy1, tx + w, fy2, HitType.RUN, si, null, null, -1));
                }
                case NUM, NUM2, NUM3, REPEAT, KEY, HOLDKEY, PICK, TEXT, SCRIPTKEY -> {
                    boolean picking = (seg.type == SegType.KEY && capturing == si && si >= 0)
                            || ((seg.type == SegType.HOLDKEY || seg.type == SegType.SCRIPTKEY)
                                && step != null && capturingStep == step)
                            || (seg.type == SegType.TEXT && step != null && editingText == step)
                            || (step != null && step == editNumStep && seg.type == editNumSeg);
                    pill(ctx, tx, fy1, w, BH - 8, picking ? CAPTURE_BG : WHITE);
                    ctx.drawText(textRenderer, seg.text, tx + (w - textRenderer.getWidth(seg.text)) / 2, ty,
                            FIELD_TEXT, false);
                    if (!editable) break;
                    HitType ht = switch (seg.type) {
                        case NUM -> HitType.NUM;
                        case NUM2 -> HitType.NUM2;
                        case NUM3 -> HitType.NUM3;
                        case REPEAT -> HitType.REPEAT;
                        case HOLDKEY -> HitType.HOLDKEY;
                        case PICK -> HitType.PICK;
                        case TEXT -> HitType.TEXT;
                        case SCRIPTKEY -> HitType.SCRIPTKEY;
                        default -> HitType.KEY;
                    };
                    if (ht == HitType.KEY ? p != null : step != null)
                        nextHits.add(new Hit(tx, fy1, tx + w, fy2, ht, si, step, null, -1));
                }
            }
            tx += w + 5;
        }
    }

    /** A rounded-looking field (rectangle with clipped corners). */
    private static void pill(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x + 2, y, x + w - 2, y + h, color);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, color);
        ctx.fill(x, y + 2, x + w, y + h - 2, color);
    }

    private static int darken(int c) {
        int r = ((c >> 16) & 0xFF) * 4 / 5, g = ((c >> 8) & 0xFF) * 4 / 5, b = (c & 0xFF) * 4 / 5;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
