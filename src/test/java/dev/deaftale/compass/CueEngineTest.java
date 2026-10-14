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
        audibilityAndLifetime();
        prioritizationAndBounds();
        classificationAndIcons();
        preferences();
        System.out.println("Passed " + checks + " sound compass checks.");
    }

    private static void bearings() {
        for (int turn = 0; turn < 8; turn++) {
            double yaw = turn * Math.PI / 4;
            for (int bearing = 0; bearing < 8; bearing++) {
                double worldAngle = bearing * Math.PI / 4 - yaw;
                var source = new CueEngine.Point(Math.sin(worldAngle) * 12, 0, -Math.cos(worldAngle) * 12);
                check(CueEngine.sector(ORIGIN, source, yaw) == bearing, "Incorrect bearing while turning");
            }
        }
        check(CueEngine.sector(ORIGIN, new CueEngine.Point(12, 0, 0), 0) == 2, "+X is right at yaw zero");
        check(CueEngine.sector(ORIGIN, new CueEngine.Point(-12, 0, 0), Math.PI / 2) == 0, "+yaw faces -X");
        var engine = new CueEngine();
        engine.add(cue("SFX_Sword_Swing", CueEngine.Kind.COMBAT, new CueEngine.Point(0, 0, -6), 1000), ORIGIN, 48, false, 1000);
        check(engine.view(ORIGIN, 0, 1000)[0] != null, "Cue starts forward");
        check(engine.view(ORIGIN, Math.PI / 2, 1100)[2] != null, "Stationary cue rotates as player turns");
        check(engine.view(ORIGIN, Math.PI * 2, 1200)[0] != null, "Yaw wrap doesn't flip cue");
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
