package dev.deaftale.compass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Sound-only model. Never queries entities or discovers sound sources. World-thread confined. */
final class CueEngine {
    enum Kind {
        COMBAT("! Combat", 5), CREATURE("A Creature", 4), FOOTSTEP(".. Footsteps", 3),
        INTERACTION("+ Interaction", 2), ENVIRONMENT("~ Environment", 1), OTHER("* Sound", 0);
        final String title;
        final int priority;
        Kind(String title, int priority) { this.title = title; this.priority = priority; }
    }

    record Point(double x, double y, double z) {
        boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
        double distance(Point other) {
            return Math.sqrt(Math.pow(x - other.x, 2) + Math.pow(y - other.y, 2) + Math.pow(z - other.z, 2));
        }
    }
    record Cue(String id, Kind kind, String label, Point source, long time, long duration, boolean demo, boolean sprinting) {
        Cue(String id, Kind kind, String label, Point source, long time, long duration, boolean demo) {
            this(id, kind, label, source, time, duration, demo, false);
        }
    }
    enum Icon { GOBLIN, FOOTSTEP, SPRINT, COMBAT, AXE, PICKAXE, CREATURE, INTERACTION, ENVIRONMENT, OTHER }
    record View(Icon icon, int priority, long time) {}
    static final int MAX_ACTIVE = 64;
    static final String[] DIRECTIONS = {"FRONT", "FRONT RIGHT", "RIGHT", "BACK RIGHT", "BACK", "BACK LEFT", "LEFT", "FRONT LEFT"};
    private final List<Cue> active = new ArrayList<>();

    static Kind classify(String id, String category) {
        String s = id.toLowerCase(Locale.ROOT);
        String c = category.toLowerCase(Locale.ROOT);
        if (isTool(s) || (!isWeapon(s) && isMaterialWork(s))) return Kind.INTERACTION;
        if (isWeapon(s) || s.matches(".*(swing|melee|attack|impact|explos|damage|weapon|arrow|parry|shield|gunshot).*")) return Kind.COMBAT;
        if (c.contains("footstep") || s.matches(".*(_walk|_run|_running|_sprint|_sprinting|_step|footstep|_land)(_|$).*")) return Kind.FOOTSTEP;
        if (c.contains("npc") || s.matches(".*(wolf|wulf|zombie|skeleton|trork|goblin|bear|spider|growl|howl|roar|grunt|hiss|bison|deer|fox|pig|sheep|cow|chicken).*")) return Kind.CREATURE;
        if (s.matches(".*(chest|door|break|build|harvest|pickup|craft|lever|switch|dig|mine_).*")) return Kind.INTERACTION;
        if (s.matches(".*(water|lava|fire|wind|rain|thunder|ambient|splash|swim).*")) return Kind.ENVIRONMENT;
        return Kind.OTHER;
    }

    private static boolean hasToken(String sound, String tokens) {
        return sound.matches(".*(?:^|_)(?:" + tokens + ")(?:_|$).*");
    }

    private static boolean isTool(String sound) {
        return hasToken(sound, "hatchet|pickaxe");
    }

    private static boolean isWeapon(String sound) {
        return hasToken(sound, "sword|longsword|dagger|daggers|axe|battleaxe|mace|club|hammer|spear|bow|shortbow|crossbow|staff|wand|weapon|weapons|unarmed");
    }

    private static boolean isMaterialWork(String sound) {
        return hasToken(sound, "break|hit|harvest|chop|mine|dig")
                && (isWood(sound) || isRock(sound));
    }

    private static boolean isWood(String sound) {
        return hasToken(sound, "wood|wooden|sticks|stick|branch|branches|log|logs|trunk|tree|bamboo");
    }

    private static boolean isRock(String sound) {
        return hasToken(sound, "rock|stone|ore|ores|granite|basalt|marble|slate|sandstone|quartzite|calcite|limestone|shale|crystal|metal");
    }

    static Icon iconFor(String id, Kind kind) {
        return iconFor(id, kind, false);
    }

    static Icon iconFor(String id, Kind kind, boolean sprinting) {
        String sound = id.toLowerCase(Locale.ROOT);
        if (kind == Kind.FOOTSTEP && (sprinting || hasToken(sound, "run|running|sprint|sprinting"))) return Icon.SPRINT;
        if (hasToken(sound, "pickaxe")) return Icon.PICKAXE;
        if (hasToken(sound, "hatchet")) return Icon.AXE;
        // Weapon axes and stone/wooden weapons remain combat cues, not harvesting cues.
        if (isWeapon(sound)) return Icon.COMBAT;
        if (isMaterialWork(sound)) {
            if (isWood(sound)) return Icon.AXE;
            if (isRock(sound)) return Icon.PICKAXE;
        }
        if (sound.contains("goblin")) return Icon.GOBLIN;
        return Icon.valueOf(kind.name());
    }

    static boolean ignored(String id, String category) {
        String s = (id + " " + category).toLowerCase(Locale.ROOT);
        return s.matches(".*(^|[_ /])(music|mus|ui|menu)([_ /]|$).*");
    }

    static String label(String id) {
        String s = id.replaceFirst("(?i)^(sfx|amb|sound)_", "")
                .replaceAll("(?i)_(world|local|player)(?=_|$)", "")
                .replaceAll("_+", " ").trim();
        if (s.isEmpty()) return "Sound";
        return s.length() > 24 ? s.substring(0, 23) + "…" : s;
    }

    /** Sector 0 is forward. Hytale yaw=0 faces -Z; positive yaw turns toward -X. */
    static int sector(Point listener, Point source, double yaw) {
        double angle = Math.atan2(source.x - listener.x, -(source.z - listener.z)) + yaw;
        return Math.floorMod((int) Math.floor(angle / (Math.PI / 4) + .5), 8);
    }

    void add(Cue cue, Point listener, double maximumRange, boolean quiet, long now) {
        if (cue.source != null && (!cue.source.finite() || !listener.finite()
                || cue.source.distance(listener) > maximumRange)) return;
        if (now - cue.time >= cue.duration || now < cue.time) return;
        if (quiet && cue.kind.priority < Kind.FOOTSTEP.priority) return;
        // Suppress feet at the listener's own position, without hiding nearby players.
        if (!cue.demo && cue.kind == Kind.FOOTSTEP && cue.source != null
                && cue.source.distance(listener) < .8) return;
        active.removeIf(old -> now - old.time >= old.duration || sameSource(old, cue));
        if (active.size() >= MAX_ACTIVE) {
            Cue victim = active.stream().min(Comparator.comparingInt((Cue c) -> c.kind.priority)
                    .thenComparingLong(Cue::time)).orElseThrow();
            if (victim.kind.priority > cue.kind.priority) return;
            active.remove(victim);
        }
        active.add(cue);
    }

    private boolean sameSource(Cue a, Cue b) {
        return a.id.equals(b.id) && ((a.source == null && b.source == null)
                || (a.source != null && b.source != null && a.source.distance(b.source) < 2));
    }

    View[] view(Point listener, double yaw, long now) {
        active.removeIf(c -> now - c.time >= c.duration);
        View[] views = new View[9]; // 8 bearings plus a non-directional icon.
        for (Cue c : active) {
            int slot = c.source == null ? 8 : sector(listener, c.source, yaw);
            View previous = views[slot];
            if (previous == null || c.kind.priority > previous.priority
                    || (c.kind.priority == previous.priority && c.time > previous.time)) {
                views[slot] = new View(iconFor(c.id, c.kind, c.sprinting), c.kind.priority, c.time);
            }
        }
        return views;
    }

    void clear() { active.clear(); }
    int activeCount() { return active.size(); }
}
