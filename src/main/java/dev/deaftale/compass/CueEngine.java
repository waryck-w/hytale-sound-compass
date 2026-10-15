package dev.deaftale.compass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

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
    record Cue(String id, Kind kind, String label, Point source, long time, long duration, boolean demo, boolean sprinting, int networkId) {
        Cue(String id, Kind kind, String label, Point source, long time, long duration, boolean demo) {
            this(id, kind, label, source, time, duration, demo, false, -1);
        }
        Cue(String id, Kind kind, String label, Point source, long time, long duration, boolean demo, boolean sprinting) {
            this(id, kind, label, source, time, duration, demo, sprinting, -1);
        }
    }
    enum Icon { GOBLIN, FOOTSTEP, SPRINT, COMBAT, AXE, PICKAXE, CREATURE, INTERACTION, ENVIRONMENT, OTHER }
    record View(long id, Icon icon, double angle, boolean directional, int priority, long time) {}
    static final int MAX_ACTIVE = 64;
    static final int DIRECTIONAL_SLOTS = 8;
    static final int LOCAL_SLOT = DIRECTIONAL_SLOTS;
    static final int VIEW_SLOTS = DIRECTIONAL_SLOTS + 1;
    private static final double MIN_SEPARATION = Math.toRadians(15);
    private static final Comparator<Tracked> IMPORTANCE = Comparator
            .comparingInt((Tracked sound) -> sound.cue.kind.priority).reversed()
            .thenComparing(Comparator.comparingLong((Tracked sound) -> sound.cue.time).reversed());
    private final List<Tracked> active = new ArrayList<>();
    private final long[] slotIds = new long[DIRECTIONAL_SLOTS];
    private long nextId = 1;

    private static final class Tracked {
        final long id;
        Cue cue;
        Tracked(long id, Cue cue) { this.id = id; this.cue = cue; }
    }

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

    /** Continuous clockwise bearing: forward=0, right=pi/2. Hytale yaw=0 faces -Z. */
    static double bearing(Point listener, Point source, double yaw) {
        return wrap(Math.atan2(source.x - listener.x, -(source.z - listener.z)) + yaw);
    }

    static double wrap(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    void add(Cue cue, Point listener, double maximumRange, boolean quiet, long now) {
        if (cue.source != null && (!cue.source.finite() || !listener.finite()
                || cue.source.distance(listener) > maximumRange)) return;
        if (now - cue.time >= cue.duration || now < cue.time) return;
        if (quiet && cue.kind.priority < Kind.FOOTSTEP.priority) return;
        // Suppress feet at the listener's own position, without hiding nearby players.
        if (!cue.demo && cue.kind == Kind.FOOTSTEP && cue.source != null
                && cue.source.distance(listener) < .8) return;
        active.removeIf(old -> now - old.cue.time >= old.cue.duration);
        for (Tracked old : active) {
            if (sameSource(old.cue, cue)) {
                old.cue = cue;
                return; // Keep identity so repeated footsteps don't restart motion smoothing.
            }
        }
        if (active.size() >= MAX_ACTIVE) {
            Tracked victim = active.stream().max(IMPORTANCE).orElseThrow();
            if (victim.cue.kind.priority > cue.kind.priority) return;
            active.remove(victim);
        }
        active.add(new Tracked(nextId++, cue));
    }

    private boolean sameSource(Cue a, Cue b) {
        if (a.networkId >= 0 && b.networkId >= 0) {
            return a.networkId == b.networkId && (a.id.equals(b.id)
                    || (a.kind == Kind.FOOTSTEP && b.kind == Kind.FOOTSTEP));
        }
        if (a.networkId != b.networkId) return false;
        return a.id.equals(b.id) && ((a.source == null && b.source == null)
                || (a.source != null && b.source != null && a.source.distance(b.source) < 2));
    }

    /** Only tracks entities already identified by received sound packets; never discovers new sources. */
    void updateSources(IntFunction<Point> positions, long now) {
        active.removeIf(sound -> now - sound.cue.time >= sound.cue.duration);
        for (Tracked sound : active) {
            Cue c = sound.cue;
            if (c.networkId < 0) continue;
            Point point = positions.apply(c.networkId);
            if (point != null && point.finite()) {
                sound.cue = new Cue(c.id, c.kind, c.label, point, c.time, c.duration, c.demo, c.sprinting, c.networkId);
            }
        }
    }

    View[] view(Point listener, double yaw, long now) {
        active.removeIf(sound -> now - sound.cue.time >= sound.cue.duration);
        View[] views = new View[VIEW_SLOTS];
        List<View> selected = new ArrayList<>(DIRECTIONAL_SLOTS);
        for (Tracked sound : active.stream().sorted(IMPORTANCE).toList()) {
            Cue c = sound.cue;
            if (c.source == null) {
                if (views[LOCAL_SLOT] == null) views[LOCAL_SLOT] = makeView(sound, 0, false);
                continue;
            }
            if (selected.size() == DIRECTIONAL_SLOTS) continue;
            double angle = bearing(listener, c.source, yaw);
            if (selected.stream().noneMatch(other -> Math.abs(wrap(other.angle - angle)) < MIN_SEPARATION)) {
                selected.add(makeView(sound, angle, true));
            }
        }
        // A slot belongs to a sound identity, not an angle. Crossing any compass boundary
        // must not swap icons or make them jump to another slot.
        for (int slot = 0; slot < DIRECTIONAL_SLOTS; slot++) {
            long id = slotIds[slot];
            View retained = selected.stream().filter(view -> view.id == id).findFirst().orElse(null);
            if (retained == null) slotIds[slot] = 0;
            else { views[slot] = retained; selected.remove(retained); }
        }
        for (View view : selected) {
            for (int slot = 0; slot < DIRECTIONAL_SLOTS; slot++) {
                if (slotIds[slot] == 0) { slotIds[slot] = view.id; views[slot] = view; break; }
            }
        }
        return views;
    }

    private View makeView(Tracked sound, double angle, boolean directional) {
        Cue c = sound.cue;
        return new View(sound.id, iconFor(c.id, c.kind, c.sprinting), angle, directional, c.kind.priority, c.time);
    }

    void clear() { active.clear(); java.util.Arrays.fill(slotIds, 0); }
    int activeCount() { return active.size(); }
}
