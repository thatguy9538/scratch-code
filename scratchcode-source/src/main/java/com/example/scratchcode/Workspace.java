package com.example.scratchcode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** All of the player's scripts, saved together in .minecraft/config/scratchcode.txt. */
public class Workspace {

    public static final int MAX_SCRIPTS = 8;

    public final List<Program> scripts = new ArrayList<>();

    public static Workspace defaults() {
        Workspace w = new Workspace();
        w.scripts.add(Program.defaults());
        return w;
    }

    /** The script a block belongs to, or null. */
    public Program scriptOf(Program.Step step) {
        for (Program p : scripts) if (p.parentListOf(step) != null) return p;
        return null;
    }

    public void save(Path file) {
        StringBuilder sb = new StringBuilder();
        for (Program p : scripts) p.writeTo(sb);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static Workspace load(Path file) {
        if (!Files.exists(file)) return defaults();
        try {
            Workspace w = new Workspace();
            Program current = null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 0 || parts[0].isEmpty()) continue;
                if (parts[0].equals("script") && parts.length >= 2 && parts.length <= 4) {
                    if (w.scripts.size() >= MAX_SCRIPTS) break;
                    if (current != null) current.finishLoading();
                    current = new Program();
                    current.key = Integer.parseInt(parts[1]);
                    if (parts.length >= 3) current.key2 = Integer.parseInt(parts[2]);
                    if (parts.length >= 4) current.key3 = Integer.parseInt(parts[3]);
                    w.scripts.add(current);
                    continue;
                }
                if (current == null) {            // older save with one script and no "script" line
                    current = new Program();
                    w.scripts.add(current);
                }
                current.readLine(parts);
            }
            if (current != null) current.finishLoading();
            if (w.scripts.isEmpty()) return defaults();
            Program.makeIdsUnique(w.scripts);
            return w;
        } catch (Exception e) {
            return defaults();
        }
    }
}
