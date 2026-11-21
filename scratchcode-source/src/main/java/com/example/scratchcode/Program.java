package com.example.scratchcode;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One script: "when [key] pressed" followed by a list of blocks. Repeat and if
 * blocks are "C" blocks that hold their own list of blocks inside them; if
 * blocks can also have an "else" list.
 */
public class Program {

    public enum Dir {
        FORWARD, BACK, LEFT, RIGHT;

        public Dir next() { return values()[(ordinal() + 1) % values().length]; }
        public Dir prev() { return values()[(ordinal() + values().length - 1) % values().length]; }
        public String label() { return name().toLowerCase(); }
    }

    public enum Kind {
        // motion
        WALK_WALL, WALK_BLOCKS, SET_ANGLE, GO_TO,
        // control
        REPEAT, REPEAT_UNTIL, IF_AT, IF_WALL, IF, WAIT, WAIT_RANDOM, WAIT_UNTIL, STOP_AT, STOP_SCRIPT, RUN_SCRIPT,
        // inventory
        SELECT_SLOT, IF_HOLDING, IF_INV_FULL, IF_CONTAINER_FULL, DEPOSIT, CLOSE_CONTAINER, WAIT_OPEN,
        // sensing
        IF_LOOKING, IF_HEALTH, IF_Y,
        // keys
        HOLD_KEY,
        // other
        SAY, SOUND, COMMENT;

        public boolean isWalk() { return this == WALK_WALL || this == WALK_BLOCKS; }

        public boolean isWait() { return this == WAIT || this == WAIT_RANDOM || this == WAIT_UNTIL; }

        /** If blocks: they check something and can have an "else" section. */
        public boolean isConditional() {
            return this == IF || this == IF_AT || this == IF_WALL || this == IF_HOLDING || this == IF_INV_FULL
                    || this == IF_CONTAINER_FULL || this == IF_LOOKING || this == IF_HEALTH || this == IF_Y;
        }

        /** "C" blocks that hold other blocks inside them. */
        public boolean isContainer() { return isConditional() || this == REPEAT || this == REPEAT_UNTIL; }

        /** Blocks whose two numbers are an x, z block position. */
        public boolean usesXZ() {
            return this == STOP_AT || this == IF_AT || this == REPEAT_UNTIL || this == WAIT_UNTIL;
        }

        public Category category() {
            return switch (this) {
                case WALK_WALL, WALK_BLOCKS, SET_ANGLE, GO_TO -> Category.MOTION;
                case SELECT_SLOT, IF_HOLDING, IF_INV_FULL, IF_CONTAINER_FULL, DEPOSIT, CLOSE_CONTAINER, WAIT_OPEN ->
                        Category.INVENTORY;
                case IF_LOOKING, IF_HEALTH, IF_Y, HOLD_KEY -> Category.SENSING;
                case SAY, SOUND -> Category.SOUND;
                case COMMENT -> Category.NOTE;
                default -> Category.CONTROL;
            };
        }
    }

    public enum Category { MOTION, CONTROL, INVENTORY, SENSING, SOUND, NOTE }

    public static final List<String> SOUND_NAMES = List.of("ding", "chime", "level up");

    public static class Step {
        public Kind kind;
        public Dir dir;
        /** WAIT / WALK_BLOCKS: the number.
         *  WAIT_RANDOM: amount = min, amount2 = max, in tenths of a second.
         *  SET_ANGLE:   amount = yaw (x), amount2 = pitch (y), in degrees.
         *  STOP_AT, IF_AT, REPEAT_UNTIL, WAIT_UNTIL: amount = block X, amount2 = block Z.
         *  GO_TO:       amount = X, amount2 = Y, amount3 = Z.
         *  REPEAT:      amount = times, 0 = forever.
         *  HOLD_KEY:    amount = key code, amount2 = tenths of a second, amount3 = 1 for a mouse button.
         *  RUN_SCRIPT:  amount = the other script's key.
         *  SELECT_SLOT: amount = 1-9.   IF_HEALTH: amount = hearts.   IF_Y: amount = y level.
         *  SOUND:       amount = which sound.
         *  WAIT_OPEN:   amount = most seconds to wait. */
        public int amount;
        public int amount2;
        public int amount3;
        /** IF_AT: 0 = at, 1 = not at.  IF_Y: 0 = below, 1 = above. */
        public int mode;
        /** DEPOSIT: the item id / custom id / name to put away.  SAY / COMMENT: the words.  IF_HOLDING: item id.  IF_LOOKING: block id. */
        public String text = "";
        /** Unique id, so "if" strings survive reordering. */
        public int id;
        /** IF only: id of the linked block, or -1. */
        public int link = -1;
        /** Blocks inside a C block. */
        public final List<Step> children = new ArrayList<>();
        /** If blocks: whether they have an "else" section, and the blocks in it. */
        public boolean hasElse = false;
        public final List<Step> elseChildren = new ArrayList<>();

        public Step(Kind kind, Dir dir, int amount) {
            this(kind, dir, amount, amount);
        }

        public Step(Kind kind, Dir dir, int amount, int amount2) {
            this(kind, dir, amount, amount2, 0);
        }

        public Step(Kind kind, Dir dir, int amount, int amount2, int amount3) {
            this.kind = kind;
            this.dir = dir;
            this.amount = amount;
            this.amount2 = amount2;
            this.amount3 = amount3;
            this.id = nextId++;
        }

        public Step withText(String t) {
            this.text = t;
            return this;
        }

        /** A fresh copy with its own id (used when dragging out of the palette). */
        public Step copy() {
            Step c = new Step(kind, dir, amount, amount2, amount3);
            c.mode = mode;
            c.text = text;
            return c;
        }

        public String describe() {
            return switch (kind) {
                case WALK_WALL -> "walk " + dir.label() + " until wall";
                case WALK_BLOCKS -> "walk " + dir.label() + " " + amount + (amount == 1 ? " block" : " blocks");
                case WAIT -> "wait " + amount + (amount == 1 ? " second" : " seconds");
                case WAIT_RANDOM -> "random wait " + tenths(amount) + " to " + tenths(amount2) + " s";
                case WAIT_UNTIL -> "wait until at x " + amount + " z " + amount2;
                case SET_ANGLE -> "set angle x " + amount + " y " + amount2;
                case STOP_AT -> "stop at x " + amount + " z " + amount2;
                case GO_TO -> "go to " + amount + " " + amount2 + " " + amount3;
                case IF -> "if linked block is done";
                case IF_AT -> "if " + (mode == 1 ? "not at" : "at") + " x " + amount + " z " + amount2;
                case IF_WALL -> "if touching wall";
                case REPEAT -> amount == 0 ? "repeat forever" : "repeat " + amount + " times";
                case REPEAT_UNTIL -> "repeat until at x " + amount + " z " + amount2;
                case STOP_SCRIPT -> "stop script";
                case RUN_SCRIPT -> "run [" + ScratchCodeClient.keyName(amount) + "] script";
                case HOLD_KEY -> "hold " + inputName(amount, amount3 == 1) + " for " + tenths(amount2) + " s";
                case SELECT_SLOT -> "select slot " + amount;
                case IF_HOLDING -> "if holding " + prettyId(text);
                case IF_INV_FULL -> "if inventory full";
                case IF_CONTAINER_FULL -> "if container full";
                case DEPOSIT -> "put all " + text + " in container";
                case CLOSE_CONTAINER -> "close container";
                case WAIT_OPEN -> "wait until container open (up to " + amount + " s)";
                case IF_LOOKING -> "if looking at " + prettyId(text);
                case IF_HEALTH -> "if health below " + amount;
                case IF_Y -> "if y " + (mode == 1 ? "above " : "below ") + amount;
                case SAY -> "say " + text;
                case SOUND -> "play sound " + SOUND_NAMES.get(clamp(amount, 0, SOUND_NAMES.size() - 1));
                case COMMENT -> "note: " + text;
            };
        }
    }

    public static final int MAX_STEPS = 30;
    public static final int MAX_AMOUNT = 999;
    public static final int MAX_TEXT = 40;
    public static final int COORD_LIMIT = 30_000_000;
    public static final int MIN_Y = -2048, MAX_Y = 4096;
    /** Longest random wait, in tenths of a second (60 s). */
    public static final int MAX_TENTHS = 600;
    private static int nextId = 1;

    /** Display name for a key or mouse button. */
    public static String inputName(int code, boolean mouse) {
        if (mouse) {
            return switch (code) {
                case 0 -> "left click";
                case 1 -> "right click";
                case 2 -> "middle click";
                default -> "mouse " + (code + 1);
            };
        }
        return ScratchCodeClient.keyName(code);
    }

    /** "minecraft:diamond_pickaxe" -> "diamond pickaxe". */
    public static String prettyId(String id) {
        if (id == null || id.isEmpty()) return "?";
        String s = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        if (s.equals("air")) return "nothing";
        return s.replace('_', ' ');
    }

    public static String tenths(int t) {
        return (t / 10) + "." + (t % 10);
    }

    /** The keys that run this script, pressed together (up to 3, e.g. F3 + P). -1 = unused. */
    public int key = org.lwjgl.glfw.GLFW.GLFW_KEY_K;
    public int key2 = -1;
    public int key3 = -1;

    /** The keys in this script's combination (empty if it has none). */
    public int[] combo() {
        return java.util.Arrays.stream(new int[]{key, key2, key3}).filter(k -> k >= 0).toArray();
    }

    public void setCombo(List<Integer> keys) {
        key = keys.size() > 0 ? keys.get(0) : -1;
        key2 = keys.size() > 1 ? keys.get(1) : -1;
        key3 = keys.size() > 2 ? keys.get(2) : -1;
    }

    public void clearCombo() {
        key = key2 = key3 = -1;
    }

    public boolean sameCombo(Program other) {
        int[] a = combo(), b = other.combo();
        java.util.Arrays.sort(a);
        java.util.Arrays.sort(b);
        return a.length > 0 && java.util.Arrays.equals(a, b);
    }

    /** e.g. "F3 + P", or "?" if none. */
    public String comboName() {
        int[] c = combo();
        if (c.length == 0) return "?";
        StringBuilder sb = new StringBuilder();
        for (int k : c) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(ScratchCodeClient.keyName(k));
        }
        return sb.toString();
    }
    public final List<Step> steps = new ArrayList<>();

    /** Every block in the script, including ones inside repeat / if blocks. */
    public List<Step> allSteps() {
        List<Step> out = new ArrayList<>();
        collect(steps, out);
        return out;
    }

    private static void collect(List<Step> list, List<Step> out) {
        for (Step s : list) {
            out.add(s);
            collect(s.children, out);
            collect(s.elseChildren, out);
        }
    }

    public Step byId(int id) {
        for (Step s : allSteps()) if (s.id == id) return s;
        return null;
    }

    /** The list a block sits in (the script itself or a C block's inside), or null. */
    public List<Step> parentListOf(Step step) {
        return findParent(steps, step);
    }

    private static List<Step> findParent(List<Step> list, Step step) {
        for (Step s : list) {
            if (s == step) return list;
            List<Step> inside = findParent(s.children, step);
            if (inside != null) return inside;
            inside = findParent(s.elseChildren, step);
            if (inside != null) return inside;
        }
        return null;
    }

    public static Program defaults() {
        Program p = new Program();
        Step loop = new Step(Kind.REPEAT, Dir.LEFT, 0);
        loop.children.add(new Step(Kind.WALK_WALL, Dir.LEFT, 5));
        loop.children.add(new Step(Kind.WALK_WALL, Dir.RIGHT, 5));
        p.steps.add(loop);
        return p;
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Give any block whose id is already taken (in any script) a new one. */
    static void makeIdsUnique(List<Program> scripts) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Program p : scripts) for (Step s : p.allSteps()) nextId = Math.max(nextId, s.id + 1);
        for (Program p : scripts) {
            for (Step s : p.allSteps()) {
                if (!seen.add(s.id)) {
                    s.id = nextId++;
                    seen.add(s.id);
                }
            }
        }
    }

    /** Keep a block's numbers in range (used when loading). */
    static void sanitize(Step s) {
        switch (s.kind) {
            case GO_TO -> {
                s.amount = clamp(s.amount, -COORD_LIMIT, COORD_LIMIT);
                s.amount2 = clamp(s.amount2, MIN_Y, MAX_Y);
                s.amount3 = clamp(s.amount3, -COORD_LIMIT, COORD_LIMIT);
            }
            case STOP_AT, IF_AT, REPEAT_UNTIL, WAIT_UNTIL -> {
                s.amount = clamp(s.amount, -COORD_LIMIT, COORD_LIMIT);
                s.amount2 = clamp(s.amount2, -COORD_LIMIT, COORD_LIMIT);
            }
            case SET_ANGLE -> {
                s.amount = clamp(s.amount, -180, 180);
                s.amount2 = clamp(s.amount2, -90, 90);
            }
            case WAIT_RANDOM -> {
                s.amount = clamp(s.amount, 0, MAX_TENTHS);
                s.amount2 = clamp(s.amount2, 0, MAX_TENTHS);
            }
            case HOLD_KEY -> {
                s.amount = clamp(s.amount, 0, 400);
                s.amount2 = clamp(s.amount2, 1, MAX_TENTHS);
                s.amount3 = clamp(s.amount3, 0, 1);
            }
            case REPEAT -> s.amount = clamp(s.amount, 0, 100000);
            case SELECT_SLOT -> s.amount = clamp(s.amount, 1, 9);
            case IF_HEALTH -> s.amount = clamp(s.amount, 1, 20);
            case IF_Y -> s.amount = clamp(s.amount, MIN_Y, MAX_Y);
            case SOUND -> s.amount = clamp(s.amount, 0, SOUND_NAMES.size() - 1);
            case RUN_SCRIPT, IF, IF_WALL, IF_HOLDING, IF_INV_FULL, IF_CONTAINER_FULL, IF_LOOKING, STOP_SCRIPT, SAY, COMMENT,
                 DEPOSIT, CLOSE_CONTAINER -> { }
            default -> s.amount = clamp(s.amount, 1, MAX_AMOUNT);
        }
        s.mode = clamp(s.mode, 0, 1);
        if (s.text == null) s.text = "";
        if (s.text.length() > MAX_TEXT) s.text = s.text.substring(0, MAX_TEXT);
        if (!s.kind.isConditional()) {
            s.hasElse = false;
            s.elseChildren.clear();
        }
    }

    // ---- saving / loading (see Workspace for the file itself) ----
    //
    //   script <key>
    //   step <KIND> <DIR> <a> <b> <c> <id> <link> <mode> ~<text>
    //   {                 <- the blocks inside the C block above
    //   step ...
    //   }
    //   else              <- optional: the C block's "else" section follows
    //   {
    //   step ...
    //   }

    void writeTo(StringBuilder sb) {
        sb.append("script ").append(key).append(' ').append(key2).append(' ').append(key3).append('\n');
        writeList(sb, steps);
    }

    private static void writeList(StringBuilder sb, List<Step> list) {
        for (Step s : list) {
            sb.append("step ").append(s.kind.name()).append(' ').append(s.dir.name()).append(' ').append(s.amount)
                    .append(' ').append(s.amount2).append(' ').append(s.amount3)
                    .append(' ').append(s.id).append(' ').append(s.link)
                    .append(' ').append(s.mode)
                    .append(" ~").append(URLEncoder.encode(s.text, StandardCharsets.UTF_8)).append('\n');
            if (s.kind.isContainer()) {
                sb.append("{\n");
                writeList(sb, s.children);
                sb.append("}\n");
                if (s.hasElse) {
                    sb.append("else\n{\n");
                    writeList(sb, s.elseChildren);
                    sb.append("}\n");
                }
            }
        }
    }

    // loading state: the lists being filled, and which block owns each one
    private final List<List<Step>> loadStack = new ArrayList<>();
    private final List<Step> ownerStack = new ArrayList<>();
    private Step lastLoaded = null;     // most recent "step" line
    private Step lastClosed = null;     // C block whose section just ended with "}"
    private boolean elseNext = false;   // an "else" line: the next "{" is lastClosed's else section
    private int legacyRepeat = -1;      // older saves: the whole script sat in one repeat
    private boolean sawBraces = false;

    /** Reads one line of a saved script (also older formats). */
    void readLine(String[] parts) {
        if (loadStack.isEmpty()) {
            loadStack.add(steps);
            ownerStack.add(null);
        }
        List<Step> target = loadStack.get(loadStack.size() - 1);

        if (parts.length == 1 && parts[0].equals("else")) {
            elseNext = true;
            return;
        }
        if (parts.length == 1 && parts[0].equals("{")) {
            sawBraces = true;
            if (elseNext && lastClosed != null && lastClosed.kind.isConditional()) {
                lastClosed.hasElse = true;
                loadStack.add(lastClosed.elseChildren);
                ownerStack.add(lastClosed);
            } else if (lastLoaded != null && lastLoaded.kind.isContainer()) {
                loadStack.add(lastLoaded.children);
                ownerStack.add(lastLoaded);
            } else {
                loadStack.add(new ArrayList<>());          // shouldn't happen; read and throw away
                ownerStack.add(null);
            }
            elseNext = false;
            lastLoaded = null;
            return;
        }
        if (parts.length == 1 && parts[0].equals("}")) {
            if (loadStack.size() > 1) {
                loadStack.remove(loadStack.size() - 1);
                lastClosed = ownerStack.remove(ownerStack.size() - 1);
            }
            return;
        }
        elseNext = false;

        int id = -1, link = -1, mode = 0;
        String text = "";
        if (parts.length >= 8 && parts[0].equals("step")) {   // newer formats
            id = Integer.parseInt(parts[6]);
            link = Integer.parseInt(parts[7]);
            if (parts.length >= 9) mode = Integer.parseInt(parts[8]);
            if (parts.length >= 10 && parts[9].startsWith("~")) {
                text = URLDecoder.decode(parts[9].substring(1), StandardCharsets.UTF_8);
            }
            parts = java.util.Arrays.copyOfRange(parts, 1, 6);
        }
        if (parts.length == 2 && parts[0].equals("repeat")) {
            legacyRepeat = clamp(Integer.parseInt(parts[1]), 0, 100000);
        } else if (parts.length >= 3 && parts.length <= 5 && target.size() < MAX_STEPS) {
            Kind kind = Kind.valueOf(parts[0]);
            int a = Integer.parseInt(parts[2]);
            int b = parts.length >= 4 ? Integer.parseInt(parts[3]) : a;
            int c = parts.length >= 5 ? Integer.parseInt(parts[4]) : 0;
            Step st = new Step(kind, Dir.valueOf(parts[1]), a, b, c);
            if (id > 0) {
                st.id = id;
                nextId = Math.max(nextId, id + 1);
            }
            st.link = link;
            st.mode = mode;
            st.text = text;
            sanitize(st);
            target.add(st);
            lastLoaded = st;
        }
    }

    /** Called after the last line: turns older flat saves into the nested layout. */
    void finishLoading() {
        if (!sawBraces) {
            // old "if" blocks controlled just the next block: put that block inside them
            for (int i = 0; i < steps.size(); i++) {
                Step s = steps.get(i);
                if ((s.kind == Kind.IF || s.kind == Kind.IF_AT) && s.children.isEmpty() && i + 1 < steps.size()) {
                    s.children.add(steps.remove(i + 1));
                }
            }
            // old scripts always looped: wrap everything in a repeat block
            if (legacyRepeat >= 0 && !steps.isEmpty()) {
                Step loop = new Step(Kind.REPEAT, Dir.LEFT, legacyRepeat);
                loop.children.addAll(steps);
                steps.clear();
                steps.add(loop);
            }
        }
        loadStack.clear();
        ownerStack.clear();
        lastLoaded = null;
        lastClosed = null;
    }
}
