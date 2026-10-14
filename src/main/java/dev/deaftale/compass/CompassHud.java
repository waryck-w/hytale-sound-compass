package dev.deaftale.compass;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Arrays;

final class CompassHud extends CustomUIHud {
    static final String KEY = "Deaftale.SoundCompass";
    private final boolean large;
    private final CueEngine.Icon[] shown = new CueEngine.Icon[9];

    CompassHud(PlayerRef player, boolean large) {
        super(player, KEY, 10);
        this.large = large;
    }

    @Override protected void build(UICommandBuilder builder) {
        builder.append(large ? "SoundCompass/Large.ui" : "SoundCompass/Standard.ui");
        Arrays.fill(shown, null);
    }

    void render(CueEngine.View[] views) {
        UICommandBuilder builder = new UICommandBuilder();
        boolean changed = false;
        for (int i = 0; i < views.length; i++) {
            CueEngine.View view = views[i];
            CueEngine.Icon key = view == null ? null : view.icon();
            if (java.util.Objects.equals(shown[i], key)) continue;
            shown[i] = key;
            changed = true;
            builder.set("#Cue" + i + ".Visible", view != null);
            if (view != null) {
                for (CueEngine.Icon icon : CueEngine.Icon.values()) {
                    builder.set("#Icon" + i + icon.name() + ".Visible", icon == view.icon());
                }
            }
        }
        if (changed) update(false, builder);
    }
}
