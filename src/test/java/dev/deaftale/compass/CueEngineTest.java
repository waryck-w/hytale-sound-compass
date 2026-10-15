package dev.deaftale.compass;

import java.nio.file.Files;
import java.util.UUID;

public final class CueEngineTest {
    private static final CueEngine.Point ORIGIN = new CueEngine.Point(0, 0, 0);
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static CueEngine.Cue cue(String id, CueEngine.Kind kind, CueEngine.Point point, long time) {
        return new CueEngine.Cue(id, kind, CueEngine.label(id), point, time, 1800, false);
    }

    public static void main(String[] args) throws Exception {
        bearings();
        smoothMotion();
        sourceTracking();
        audibilityAndLifetime();
        prioritizationAndBounds();
        classificationAndIcons();
        preferences();
        System.out.println("Passed " + checks + " sound compass checks.");
    }

    private static void bearings() {
        for (int turn = 0; turn < 8; turn++) {
            double yaw = turn * Math.PI / 4;
            double largestError = 0;
            for (int degrees = 0; degrees < 360; degrees += 7) {
                double bearing = Math.toRadians(degrees);
                double worldAngle = bearing - yaw;
                var source = new CueEngine.Point(Math.sin(worldAngle) * 12, 0, -Math.cos(worldAngle) * 12);
                largestError = Math.max(largestError, Math.abs(CueEngine.wrap(CueEngine.bearing(ORIGIN, source, yaw) - bearing)));
            }
            check(largestError < 1e-9, "Exact continuous bearings at arbitrary angles while turning");
        }
        check(Math.abs(CueEngine.bearing(ORIGIN, new CueEngine.Point(12, 0, 0), 0) - Math.PI / 2) < 1e-9, "+X is right at yaw zero");
        check(Math.abs(CueEngine.bearing(ORIGIN, new CueEngine.Point(-12, 0, 0), Math.PI / 2)) < 1e-9, "+yaw faces -X");
        var engine = new CueEngine();
        engine.add(cue("SFX_Sword_Swing", CueEngine.Kind.COMBAT, new CueEngine.Point(0, 0, -6), 1000), ORIGIN, 48, false, 1000);
        check(engine.view(ORIGIN, 0, 1000)[0] != null, "Cue starts forward");
        long id = engine.view(ORIGIN, 0, 1000)[0].id();
        check(Math.abs(engine.view(ORIGIN, Math.PI / 2, 1100)[0].angle() - Math.PI / 2) < 1e-9, "Stationary cue rotates as player turns");
        check(engine.view(ORIGIN, Math.PI * 2, 1200)[0] != null, "Yaw wrap doesn't flip cue");
        check(engine.view(ORIGIN, Math.PI * 2, 1200)[0].id() == id, "Full revolution keeps the same marker identity");
        check(Math.abs(engine.view(ORIGIN, Math.toRadians(13), 1200)[0].angle() - Math.toRadians(13)) < 1e-9,
                "13-degree bearing is not snapped to a compass sector");
    }

    private static void smoothMotion() {
        double current = Math.toRadians(179), target = Math.toRadians(-179);
        double next = CompassMotion.approach(current, target, 1.0 / 30);
        double travelled = CueEngine.wrap(next - current);
        check(travelled > 0 && travelled < Math.toRadians(2), "Rear-boundary smoothing takes the short path");
        check(CompassMotion.approach(.3, 1.1, 0) == .3, "Zero elapsed time doesn't move a marker");
        double thirtyHz = 0, sixtyHz = 0;
        for (int i = 0; i < 6; i++) thirtyHz = CompassMotion.approach(thirtyHz, 1, 1.0 / 30);
        for (int i = 0; i < 12; i++) sixtyHz = CompassMotion.approach(sixtyHz, 1, 1.0 / 60);
        check(Math.abs(thirtyHz - sixtyHz) < 1e-9, "Smoothing depends on elapsed time rather than frame count");
        check(thirtyHz > .9 && thirtyHz < 1, "Motion converges without overshooting");
        var front = CompassMotion.position(0, false, true);
        var right = CompassMotion.position(Math.PI / 2, false, true);
        check(front.iconLeft() == 226 && front.iconTop() == 14, "Front marker geometry");
        check(right.iconLeft() == 438 && right.iconTop() == 226, "Right marker geometry");
        check(CompassMotion.position(Math.toRadians(359.8), false, true).frame() == 0, "Arc frame wraps from 359 to zero");
        var intermediate = CompassMotion.position(Math.toRadians(13), false, true);
        check(intermediate.iconLeft() != front.iconLeft() && intermediate.iconTop() != front.iconTop(), "Subsector motion changes icon position");
        check(CompassMotion.position(Math.PI, false, false).frame() == -1, "Unlocated sound has no directional arc");
        var views = new CueEngine.View[CueEngine.VIEW_SLOTS];
        views[0] = new CueEngine.View(1, CueEngine.Icon.COMBAT, 0, true, 5, 1000);
        var motion = new CompassMotion();
        check(motion.positions(views, false, 1000)[0].equals(front), "New sound appears directly at its bearing");
        views[0] = new CueEngine.View(1, CueEngine.Icon.COMBAT, Math.PI / 2, true, 5, 1000);
        var moving = motion.positions(views, false, 1033)[0];
        check(moving.iconLeft() > front.iconLeft() && moving.iconTop() < right.iconTop(), "Existing marker glides toward its new bearing");
        views[0] = new CueEngine.View(2, CueEngine.Icon.COMBAT, Math.PI / 2, true, 5, 1033);
        check(motion.positions(views, false, 1066)[0].equals(right), "Reused slot doesn't animate from a different sound's old location");
    }

    private static void sourceTracking() {
        var engine = new CueEngine();
        engine.add(new CueEngine.Cue("SFX_Stone_Walk", CueEngine.Kind.FOOTSTEP, "", new CueEngine.Point(-5, 0, -5),
                1000, 1800, false, false, 42), ORIGIN, 48, false, 1000);
        long id = engine.view(ORIGIN, 0, 1000)[0].id();
        engine.updateSources(networkId -> networkId == 42 ? new CueEngine.Point(5, 0, -5) : null, 1100);
        var moved = engine.view(ORIGIN, 0, 1100)[0];
        check(moved.id() == id && Math.abs(moved.angle() - Math.PI / 4) < 1e-9, "Entity sound follows its source without changing identity");
        engine.add(new CueEngine.Cue("SFX_Wood_Walk", CueEngine.Kind.FOOTSTEP, "", new CueEngine.Point(7, 0, -5),
                1200, 1800, false, true, 42), ORIGIN, 48, false, 1200);
        check(engine.activeCount() == 1 && engine.view(ORIGIN, 0, 1200)[0].id() == id, "Repeated entity footsteps retain identity across surfaces");
        check(engine.view(ORIGIN, 0, 1200)[0].icon() == CueEngine.Icon.SPRINT, "Source renewal still updates sprint icon");
        engine.updateSources(networkId -> null, 1300);
        check(engine.view(ORIGIN, 0, 1300)[0] != null, "Missing source retains its last known position briefly");
        engine.updateSources(networkId -> new CueEngine.Point(7, 0, -5), 3000);
        check(engine.activeCount() == 0, "Tracking an entity never extends a sound's lifetime");
        int[] queries = {0};
        engine.updateSources(networkId -> { queries[0]++; return ORIGIN; }, 3100);
        check(queries[0] == 0, "No silent entities are queried or discovered");
        engine.add(cue("first", CueEngine.Kind.FOOTSTEP, new CueEngine.Point(-4, 0, -8), 4000), ORIGIN, 48, false, 4000);
        engine.add(cue("second", CueEngine.Kind.FOOTSTEP, new CueEngine.Point(4, 0, -8), 4001), ORIGIN, 48, false, 4001);
        var initial = engine.view(ORIGIN, 0, 4001);
        engine.add(cue("first", CueEngine.Kind.FOOTSTEP, new CueEngine.Point(-4, 0, -8), 4002), ORIGIN, 48, false, 4002);
        var renewed = engine.view(ORIGIN, Math.toRadians(50), 4002);
        check(initial[0].id() == renewed[0].id() && initial[1].id() == renewed[1].id(), "Recency changes and camera rotation don't swap marker slots");
        engine.clear();
        engine.add(cue("rear-combat", CueEngine.Kind.COMBAT, new CueEngine.Point(.1, 0, 7), 5000), ORIGIN, 48, false, 5000);
        engine.add(cue("rear-footstep", CueEngine.Kind.FOOTSTEP, new CueEngine.Point(-.1, 0, 7), 5001), ORIGIN, 48, false, 5001);
        var rear = engine.view(ORIGIN, 0, 5001);
        check(java.util.Arrays.stream(rear).filter(java.util.Objects::nonNull).count() == 1 && rear[0].icon() == CueEngine.Icon.COMBAT,
                "Overlapping bearings across the rear seam retain the important sound");
    }

    private static void audibilityAndLifetime() {
        var engine = new CueEngine();
        engine.add(cue("far", CueEngine.Kind.COMBAT, new CueEngine.Point(0, 0, -30), 1000), ORIGIN, 20, false, 1000);
        engine.add(cue("self", CueEngine.Kind.FOOTSTEP, new CueEngine.Point(.1, 0, 0), 1000), ORIGIN, 48, false, 1000);
        engine.add(cue("nan", CueEngine.Kind.COMBAT, new CueEngine.Point(Double.NaN, 0, 0), 1000), ORIGIN, 48, false, 1000);
        engine.add(cue("old", CueEngine.Kind.COMBAT, new CueEngine.Point(0, 0, -5), 1000), ORIGIN, 48, false, 2900);
        check(engine.activeCount() == 0, "Out-of-range, own feet, invalid and stale cues must be dropped");
        engine.add(cue("SFX_Stone_Walk", CueEngine.Kind.FOOTSTEP, new CueEngine.Point(0, 0, -3), 1000), ORIGIN, 48, false, 1000);
        check(engine.view(ORIGIN, 0, 2799)[0] != null, "Cue remains for configured lifetime");
        check(engine.view(ORIGIN, 0, 2800)[0] == null, "Cue expires exactly at lifetime");
        check(engine.activeCount() == 0, "Expired sounds are removed from memory");
        engine.add(cue("quiet-water", CueEngine.Kind.ENVIRONMENT, new CueEngine.Point(0, 0, -3), 3000), ORIGIN, 48, true, 3000);
        check(engine.activeCount() == 0, "Quiet mode excludes environment");
        engine.add(cue("quiet-feet", CueEngine.Kind.FOOTSTEP, new CueEngine.Point(0, 0, -3), 3000), ORIGIN, 48, true, 3000);
        check(engine.activeCount() == 1, "Quiet mode retains footsteps");
        engine.clear();
        check(engine.activeCount() == 0, "Turning off clears previous sounds");
    }

    private static void prioritizationAndBounds() {
        var engine = new CueEngine();
        engine.add(cue("combat", CueEngine.Kind.COMBAT, new CueEngine.Point(0, 0, -5), 1000), ORIGIN, 48, false, 1000);
        engine.add(cue("feet", CueEngine.Kind.FOOTSTEP, new CueEngine.Point(0, 0, -6), 1100), ORIGIN, 48, false, 1100);
        check(engine.view(ORIGIN, 0, 1100)[0].icon() == CueEngine.Icon.COMBAT, "Combat wins same-bearing clutter");
        engine.add(cue("combat", CueEngine.Kind.COMBAT, new CueEngine.Point(0, 0, -5), 1200), ORIGIN, 48, false, 1200);
        check(engine.activeCount() == 2, "Repeated sound renews rather than accumulating");
        for (int i = 0; i < 300; i++) {
            engine.add(cue("many-" + i, CueEngine.Kind.ENVIRONMENT, new CueEngine.Point(3, 0, -6), 1200), ORIGIN, 48, false, 1200);
        }
        check(engine.activeCount() <= CueEngine.MAX_ACTIVE, "Sound floods have bounded memory");
        check(engine.view(ORIGIN, 0, 1200)[0].icon() == CueEngine.Icon.COMBAT, "Low-priority floods retain important cues");
    }

    private static void classificationAndIcons() {
        check(CueEngine.classify("SFX_Stone_Walk", "AudioCat_Footsteps") == CueEngine.Kind.FOOTSTEP, "Native footsteps");
        check(CueEngine.classify("SFX_Goblin_Grunt", "") == CueEngine.Kind.CREATURE, "Native goblin cue");
        check(CueEngine.classify("SFX_Light_Melee_T2_Swing", "") == CueEngine.Kind.COMBAT, "Native sword cue");
        check(CueEngine.classify("SFX_Chest_Wooden_Open_Player", "") == CueEngine.Kind.INTERACTION, "Native chest cue");
        check(CueEngine.ignored("SFX_UI_Click", ""), "Menu click noise excluded");
        check(CueEngine.ignored("Track_A", "AudioCat_Music"), "Music category excluded");
        check(!CueEngine.ignored("SFX_Goblin_Grunt", "AudioCat_NPC"), "Creature audio retained");
        var engine = new CueEngine();
        engine.add(cue("SFX_Goblin_Grunt", CueEngine.Kind.CREATURE, new CueEngine.Point(0, 6, -7), 1000), ORIGIN, 48, false, 1000);
        var view = engine.view(ORIGIN, 0, 1000)[0];
        check(view.icon() == CueEngine.Icon.GOBLIN, "Goblin audio uses GoblinBreachCompass icon");
        engine.add(cue("SFX_Sword_Swing", CueEngine.Kind.COMBAT, null, 1000), ORIGIN, 48, false, 1000);
        check(engine.view(ORIGIN, 0, 1000)[8].icon() == CueEngine.Icon.COMBAT, "Unlocated audio uses the non-directional icon slot");
        engine.clear();
        engine.add(cue("SFX_Unknown_ModSound", CueEngine.Kind.OTHER, new CueEngine.Point(0, -5, -4), 1000), ORIGIN, 48, false, 1000);
        check(engine.view(ORIGIN, 0, 1000)[0].icon() == CueEngine.Icon.OTHER, "Unknown sound uses fallback icon");
        for (String id : new String[]{"SFX_Light_Melee_T2_Swing", "SFX_Daggers_T1_Guard_Local", "SFX_Daggers_T2_Stab_Right_Local",
                "SFX_Mace_T1_Swing", "SFX_Battleaxe_T2_Signature_End_Local", "SFX_Club_Wood_Impact", "SFX_Axe_Stone_Trork_Hit"}) {
            var kind = CueEngine.classify(id, "");
            check(kind == CueEngine.Kind.COMBAT && CueEngine.iconFor(id, kind) == CueEngine.Icon.COMBAT,
                    "Weapons share sword icon: " + id);
        }
        for (String id : new String[]{"SFX_Wood_Break", "SFX_Wood_Hit", "SFX_Sticks_Break", "SFX_Branch_Break", "SFX_Hatchet_T2_Impact_Nice"}) {
            var kind = CueEngine.classify(id, "");
            check(kind == CueEngine.Kind.INTERACTION && CueEngine.iconFor(id, kind) == CueEngine.Icon.AXE,
                    "Woodwork uses axe icon: " + id);
        }
        for (String id : new String[]{"SFX_Stone_Break", "SFX_Stone_Hit", "SFX_Stone_Harvest", "SFX_Iron_Ore_Hit", "SFX_Pickaxe_T1_Swing_Down_Local"}) {
            var kind = CueEngine.classify(id, "");
            check(kind == CueEngine.Kind.INTERACTION && CueEngine.iconFor(id, kind) == CueEngine.Icon.PICKAXE,
                    "Mining uses pickaxe icon: " + id);
        }
        for (String id : new String[]{"SFX_Wood_Walk", "SFX_Stone_Walk"}) {
            var kind = CueEngine.classify(id, "AudioCat_Footsteps");
            check(CueEngine.iconFor(id, kind) == CueEngine.Icon.FOOTSTEP, "Walking is not harvesting: " + id);
        }
        check(CueEngine.iconFor("SFX_Chest_Wooden_Open_Player", CueEngine.Kind.INTERACTION) == CueEngine.Icon.INTERACTION,
                "Opening a wooden chest is not chopping wood");
        check(CueEngine.iconFor("SFX_Stone_Walk", CueEngine.Kind.FOOTSTEP, true) == CueEngine.Icon.SPRINT,
                "Sprinting source uses boots even when the surface sound is shared with walking");
        check(CueEngine.iconFor("SFX_Player_Sprint", CueEngine.Kind.FOOTSTEP) == CueEngine.Icon.SPRINT, "Named sprint sound uses boots");
        check(CueEngine.iconFor("SFX_Wolf_Run", CueEngine.Kind.FOOTSTEP) == CueEngine.Icon.SPRINT, "Running sound uses boots");
        check(CueEngine.iconFor("SFX_Sword_Swing", CueEngine.Kind.COMBAT, true) == CueEngine.Icon.COMBAT,
                "Attacking while sprinting still uses the sword");
        check(CueEngine.iconFor("SFX_Stone_Walk", CueEngine.Kind.FOOTSTEP, false) == CueEngine.Icon.FOOTSTEP,
                "Walking source retains footprint");
        engine.clear();
        engine.add(new CueEngine.Cue("SFX_Stone_Walk", CueEngine.Kind.FOOTSTEP, "", new CueEngine.Point(0, 0, -5),
                1000, 1800, false, true), ORIGIN, 48, true, 1000);
        check(engine.view(ORIGIN, 0, 1000)[0].icon() == CueEngine.Icon.SPRINT, "Source movement metadata reaches the rendered icon");
    }

    private static void preferences() throws Exception {
        var directory = Files.createTempDirectory("sound-compass-test-");
        try {
            UUID first = UUID.randomUUID(), second = UUID.randomUUID();
            var prefs = new Preferences(directory);
            check(prefs.get(first).enabled(), "Accessibility starts enabled");
            var options = new Preferences.Options(false, true, true);
            prefs.save(first, options);
            check(new Preferences(directory).get(first).equals(options), "Personal settings survive restart");
            check(prefs.get(second).enabled(), "One player's setting doesn't disable another player");
        } finally {
            try (var files = Files.list(directory)) { for (var file : files.toList()) Files.delete(file); }
            Files.delete(directory);
        }
    }
}
