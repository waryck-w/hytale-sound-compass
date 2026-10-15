package dev.deaftale.compass;

/** Pure motion/geometry model, independent of the game UI. */
final class CompassMotion {
    private static final double RESPONSE_SECONDS = .045;
    record Position(int iconLeft, int iconTop, int arcLeft, int arcTop, int frame) {}
    private final long[] ids = new long[CueEngine.VIEW_SLOTS];
    private final double[] angles = new double[CueEngine.VIEW_SLOTS];
    private long previousTime = -1;

    static double approach(double current, double target, double seconds) {
        if (seconds <= 0) return current;
        double fraction = -Math.expm1(-Math.max(0, seconds) / RESPONSE_SECONDS);
        return CueEngine.wrap(current + CueEngine.wrap(target - current) * fraction);
    }

    Position[] positions(CueEngine.View[] views, boolean large, long now) {
        double dt = previousTime < 0 ? 0 : Math.min(.1, Math.max(0, (now - previousTime) / 1000.0));
        previousTime = now;
        Position[] positions = new Position[views.length];
        for (int slot = 0; slot < views.length; slot++) {
            CueEngine.View view = views[slot];
            if (view == null) { ids[slot] = 0; continue; }
            if (ids[slot] != view.id()) angles[slot] = view.angle();
            else angles[slot] = approach(angles[slot], view.angle(), dt);
            ids[slot] = view.id();
            positions[slot] = position(angles[slot], large, view.directional());
        }
        return positions;
    }

    static Position position(double angle, boolean large, boolean directional) {
        int canvas = large ? 640 : 500;
        int icon = large ? 64 : 48;
        int radius = large ? 278 : 212;
        int patch = large ? 160 : 122;
        double arcRadius = large ? 237.5 : 180.5;
        double centre = canvas / 2.0;
        if (!directional) return new Position((canvas - icon) / 2, canvas / 2 + 70, 0, 0, -1);
        double sin = Math.sin(angle), cos = Math.cos(angle);
        int frame = Math.floorMod((int) Math.round(Math.toDegrees(angle)), 360);
        return new Position((int) Math.round(centre + sin * radius - icon / 2.0),
                (int) Math.round(centre - cos * radius - icon / 2.0),
                (int) Math.round(centre + sin * arcRadius - patch / 2.0),
                (int) Math.round(centre - cos * arcRadius - patch / 2.0), frame);
    }
}
