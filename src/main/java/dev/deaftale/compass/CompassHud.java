package dev.deaftale.compass;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Arrays;

final class CompassHud extends CustomUIHud {
    static final String KEY = "Deaftale.SoundCompass";
    private final boolean large;
    private final CueEngine.Icon[] shown = new CueEngine.Icon[CueEngine.VIEW_SLOTS];
    private final CompassMotion.Position[] positions = new CompassMotion.Position[CueEngine.VIEW_SLOTS];
    private CompassMotion motion = new CompassMotion();

    CompassHud(PlayerRef player, boolean large) {
        super(player, KEY, 10);
        this.large = large;
    }

    @Override protected void build(UICommandBuilder builder) {
        builder.append(large ? "SoundCompass/Large.ui" : "SoundCompass/Standard.ui");
        Arrays.fill(shown, null);
        Arrays.fill(positions, null);
        motion = new CompassMotion();
    }

    void render(CueEngine.View[] views, long now) {
        UICommandBuilder builder = new UICommandBuilder();
        CompassMotion.Position[] next = motion.positions(views, large, now);
        boolean changed = false;
        for (int i = 0; i < views.length; i++) {
            CueEngine.View view = views[i];
            CueEngine.Icon key = view == null ? null : view.icon();
            if (shown[i] != key) {
                changed = true;
                builder.set("#Cue" + i + ".Visible", view != null);
            }
            if (view != null && shown[i] != key) {
                for (CueEngine.Icon icon : CueEngine.Icon.values()) {
                    builder.set("#Icon" + i + icon.name() + ".Visible", icon == view.icon());
                }
            }
            shown[i] = key;
            CompassMotion.Position target = next[i], old = positions[i];
            if (target != null && !target.equals(old)) {
                changed = true;
                if (old == null || old.iconLeft() != target.iconLeft()) builder.set("#Marker" + i + ".Anchor.Left", target.iconLeft());
                if (old == null || old.iconTop() != target.iconTop()) builder.set("#Marker" + i + ".Anchor.Top", target.iconTop());
                if (i < CueEngine.DIRECTIONAL_SLOTS) {
                    if (old == null || old.arcLeft() != target.arcLeft()) builder.set("#Arc" + i + ".Anchor.Left", target.arcLeft());
                    if (old == null || old.arcTop() != target.arcTop()) builder.set("#Arc" + i + ".Anchor.Top", target.arcTop());
                    if (old == null || old.frame() != target.frame()) {
                        builder.set("#Arc" + i + ".Background", Value.ref(large ? "SoundCompass/Large.ui" : "SoundCompass/Standard.ui",
                                "Arc" + String.format(java.util.Locale.ROOT, "%03d", target.frame())));
                    }
                }
            }
            positions[i] = target;
        }
        if (changed) update(false, builder);
    }
}
