package dev.deaftale.compass;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.world.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Accessibility overlay for Hytale 0.7.2, including the singleplayer world's local server. */
public final class SoundCompassPlugin extends JavaPlugin {
    private static final double MAX_RANGE = 48;
    private static final long LIFETIME_MS = 1800;
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private ScheduledExecutorService timer;
    private PacketFilter outbound;
    private Preferences preferences;
    private volatile boolean running;

    public SoundCompassPlugin(JavaPluginInit init) { super(init); }
    private static long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }

    @Override protected void setup() {
        try { preferences = new Preferences(getDataDirectory()); }
        catch (IOException e) { throw new IllegalStateException("Cannot load Sound Compass preferences", e); }
        getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> {
            var ref = event.getPlayerRef();
            PlayerRef player = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
            if (player != null) attach(player, event.getPlayer(), ref.getStore().getExternalData().getWorld());
        });
        getEventRegistry().register(PlayerDisconnectEvent.class, event -> {
            Session session = sessions.remove(event.getPlayerRef().getUuid());
            if (session != null) session.closed = true;
        });
        getCommandRegistry().registerCommand(new CompassCommand(null));
    }

    @Override protected void start() {
        running = true;
        outbound = PacketAdapters.registerOutbound((PlayerPacketWatcher) this::capture);
        timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "SoundCompass-dispatch");
            thread.setDaemon(true);
            return thread;
        });
        // Only dispatch here. ECS reads and HUD updates happen on the owning world thread.
        timer.scheduleAtFixedRate(this::dispatch, 33, 33, TimeUnit.MILLISECONDS);
        getLogger().at(Level.INFO).log("Sound Compass 0.3.1 ready. Use /soundcompass demo to preview.");
    }

    private void attach(PlayerRef player, Player entity, World world) {
        if (!running) return;
        Session previous = sessions.remove(player.getUuid());
        if (previous != null) previous.closed = true;
        entity.getHudManager().removeCustomHud(player, CompassHud.KEY);
        Session session = new Session(player, world, preferences.get(player.getUuid()));
        sessions.put(player.getUuid(), session);
        if (session.options.enabled()) show(session, entity);
    }

    private void show(Session session, Player entity) {
        entity.getHudManager().removeCustomHud(session.player, CompassHud.KEY);
        session.hud = new CompassHud(session.player, session.options.large());
        entity.getHudManager().addCustomHud(session.player, session.hud);
    }

    private void capture(PlayerRef player, Packet packet) {
        if (!running) return;
        Session session = sessions.get(player.getUuid());
        if (session == null || session.closed || !session.options.enabled()) return;
        // Copy the few fields we need; never retain or mutate the original packet.
        Incoming sound;
        long time = now();
        if (packet instanceof PlaySoundEvent3D p && p.position != null) {
            sound = new Incoming(p.soundEventIndex, -1, new CueEngine.Point(p.position.x, p.position.y, p.position.z), p.volumeModifier, time);
        } else if (packet instanceof PlaySoundEventEntity p) {
            sound = new Incoming(p.soundEventIndex, p.networkId, null, p.volumeModifier, time);
        } else if (packet instanceof PlaySoundEvent2D p) {
            sound = new Incoming(p.soundEventIndex, -1, null, p.volumeModifier, time);
        } else if (packet instanceof PlaySoundEventLocalPlayer p) {
            sound = new Incoming(p.localSoundEventIndex, -1, null, p.volumeModifier, time);
        } else return;
        if (sound.index < 0 || !Float.isFinite(sound.volume) || sound.volume <= 0) return;
        // Bound network-thread work and memory even in sound-heavy scenes.
        session.incoming.offer(sound);
    }

    private void dispatch() {
        if (!running) return;
        for (Session session : sessions.values()) {
            if (session.closed || !session.queued.compareAndSet(false, true)) continue;
            try {
                session.world.execute(() -> {
                    try {
                        if (running && !session.closed && sessions.get(session.player.getUuid()) == session) tick(session);
                    } catch (RuntimeException e) {
                        // Quarantine this overlay, leaving normal sound and the world tick intact.
                        session.closed = true;
                        if (session.hud != null) {
                            try { session.hud.update(true, new com.hypixel.hytale.server.core.ui.builder.UICommandBuilder()); }
                            catch (RuntimeException ignored) { /* Connection may already be closing. */ }
                        }
                        getLogger().at(Level.WARNING).withCause(e).log("Sound Compass paused for one player after an overlay error.");
                    } finally { session.queued.set(false); }
                });
            } catch (RuntimeException e) {
                session.queued.set(false);
                session.closed = true;
                getLogger().at(Level.WARNING).withCause(e).log("Sound Compass could not schedule an overlay update.");
            }
        }
    }

    private void tick(Session session) {
        PlayerRef player = session.player;
        // Don't leak stale cues across worlds, respawns, or disconnected references.
        if (!java.util.Objects.equals(player.getWorldUuid(), session.world.getWorldConfig().getUuid())) {
            session.incoming.clear();
            session.engine.clear();
            if (session.hud != null) session.hud.render(new CueEngine.View[CueEngine.VIEW_SLOTS], now());
            return;
        }
        var ref = player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != session.world.getEntityStore().getStore()) return;
        var store = ref.getStore();
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
        if (transform == null || head == null) return;
        var p = transform.getPosition();
        var listener = new CueEngine.Point(p.x, p.y, p.z);
        long time = now();
        Incoming sound;
        while ((sound = session.incoming.poll()) != null) {
            if (!session.options.enabled() || time - sound.time >= LIFETIME_MS) continue;
            SoundEvent asset = SoundEvent.getAssetMap().getAsset(sound.index);
            if (asset == null || asset.getId() == null) continue;
            String category = asset.getAudioCategoryId() == null ? "" : asset.getAudioCategoryId();
            if (CueEngine.ignored(asset.getId(), category)) continue;
            CueEngine.Kind kind = CueEngine.classify(asset.getId(), category);
            CueEngine.Point source = sound.position;
            boolean sprinting = false;
            if (sound.networkId >= 0) {
                var entityRef = session.world.getEntityStore().getRefFromNetworkId(sound.networkId);
                if (entityRef != null && entityRef.isValid()) {
                    if (entityRef.equals(ref) && kind == CueEngine.Kind.FOOTSTEP) continue;
                    if (kind == CueEngine.Kind.FOOTSTEP) {
                        var movement = store.getComponent(entityRef, MovementStatesComponent.getComponentType());
                        if (movement != null && movement.getMovementStates() != null) {
                            var states = movement.getMovementStates();
                            sprinting = states.sprinting || states.running;
                        }
                    }
                    var entityTransform = store.getComponent(entityRef, TransformComponent.getComponentType());
                    if (entityTransform != null) {
                        var position = entityTransform.getPosition();
                        source = new CueEngine.Point(position.x, position.y, position.z);
                    }
                }
            }
            if (source == null && kind == CueEngine.Kind.FOOTSTEP) continue;
            // Sound range is only an upper bound; final client mixing/occlusion isn't exposed here.
            double range = asset.getMaxDistance() > 0 ? Math.min(MAX_RANGE, asset.getMaxDistance()) : MAX_RANGE;
            session.engine.add(new CueEngine.Cue(asset.getId(), kind, CueEngine.label(asset.getId()),
                    source, sound.time, LIFETIME_MS, false, sprinting, sound.networkId), listener, range, session.options.quiet(), time);
        }
        if (session.options.enabled() && session.hud != null) {
            session.engine.updateSources(networkId -> {
                var sourceRef = session.world.getEntityStore().getRefFromNetworkId(networkId);
                if (sourceRef == null || !sourceRef.isValid()) return null;
                var sourceTransform = store.getComponent(sourceRef, TransformComponent.getComponentType());
                if (sourceTransform == null) return null;
                var sourcePosition = sourceTransform.getPosition();
                return new CueEngine.Point(sourcePosition.x, sourcePosition.y, sourcePosition.z);
            }, time);
            session.hud.render(session.engine.view(listener, head.getRotation().yaw(), time), time);
        }
    }

    @Override protected void shutdown() {
        running = false;
        if (outbound != null) PacketAdapters.deregisterOutbound(outbound);
        if (timer != null) timer.shutdownNow();
        for (Session session : sessions.values()) {
            session.closed = true;
            session.world.execute(() -> {
                var ref = session.player.getReference();
                if (ref != null && ref.isValid() && ref.getStore() == session.world.getEntityStore().getStore()) {
                    Player entity = ref.getStore().getComponent(ref, Player.getComponentType());
                    if (entity != null) entity.getHudManager().removeCustomHud(session.player, CompassHud.KEY);
                }
            });
        }
        sessions.clear();
    }

    private record Incoming(int index, int networkId, CueEngine.Point position, float volume, long time) {}
    private static final class Session {
        final PlayerRef player;
        final World world;
        final ArrayBlockingQueue<Incoming> incoming = new ArrayBlockingQueue<>(128);
        final CueEngine engine = new CueEngine();
        final AtomicBoolean queued = new AtomicBoolean();
        volatile Preferences.Options options;
        volatile boolean closed;
        CompassHud hud;
        Session(PlayerRef player, World world, Preferences.Options options) {
            this.player = player; this.world = world; this.options = options;
        }
    }

    private final class CompassCommand extends AbstractPlayerCommand {
        private final String action;
        CompassCommand(String action) {
            super(action == null ? "soundcompass" : action, "Personal directional sound accessibility controls");
            this.action = action;
            requireNoPermission();
            if (action == null) {
                addAliases("sc");
                for (String option : new String[]{"on", "off", "demo", "small", "large", "quiet", "all"}) {
                    addSubCommand(new CompassCommand(option));
                }
            }
        }
        @Override protected void execute(CommandContext context, Store<EntityStore> store,
                Ref<EntityStore> ref, PlayerRef player, World world) {
            Player entity = store.getComponent(ref, Player.getComponentType());
            if (entity == null) return;
            Session session = sessions.get(player.getUuid());
            if (session == null || session.closed) {
                attach(player, entity, world);
                session = sessions.get(player.getUuid());
            }
            if (session == null) return;
            if (action == null) {
                context.sendMessage(Message.raw("Sound Compass: " + (session.options.enabled() ? "ON" : "OFF")
                        + ". /sc on | off | demo | small | large | quiet | all. Sword = weapon attack, axe = woodwork, pickaxe = mining, boots = running/sprinting. Quiet shows combat, creatures and footsteps."));
                return;
            }
            var previous = session.options;
            boolean enabled = switch (action) { case "on", "demo" -> true; case "off" -> false; default -> previous.enabled(); };
            boolean large = switch (action) { case "large" -> true; case "small" -> false; default -> previous.large(); };
            boolean quiet = switch (action) { case "quiet" -> true; case "all" -> false; default -> previous.quiet(); };
            session.options = new Preferences.Options(enabled, large, quiet);
            session.engine.clear();
            session.incoming.clear();
            entity.getHudManager().removeCustomHud(player, CompassHud.KEY);
            session.hud = null;
            if (enabled) show(session, entity);
            if (action.equals("demo")) demo(session, store, ref);
            try { preferences.save(player.getUuid(), session.options); }
            catch (IOException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Could not save Sound Compass preferences.");
                context.sendMessage(Message.raw("Changed for this session; saving settings failed."));
                return;
            }
            context.sendMessage(Message.raw(action.equals("demo")
                    ? "Sound Compass demo: synthetic footstep, boots, sword, axe, pickaxe and goblin icons for 6 seconds. No sound is played."
                    : "Sound Compass: " + action + ". Saved for your player."));
        }
    }

    private void demo(Session session, Store<EntityStore> store, Ref<EntityStore> ref) {
        var transform = store.getComponent(ref, TransformComponent.getComponentType());
        var head = store.getComponent(ref, HeadRotation.getComponentType());
        if (transform == null || head == null) return;
        var p = transform.getPosition();
        var listener = new CueEngine.Point(p.x, p.y, p.z);
        double yaw = head.getRotation().yaw();
        long time = now();
        CueEngine.Kind[] kinds = {CueEngine.Kind.FOOTSTEP, CueEngine.Kind.INTERACTION, CueEngine.Kind.COMBAT,
                CueEngine.Kind.INTERACTION, CueEngine.Kind.CREATURE, CueEngine.Kind.FOOTSTEP};
        String[] ids = {"Demo_Footsteps", "SFX_Wood_Break", "Demo_Combat", "SFX_Stone_Break", "Demo_Goblin", "Demo_Sprint"};
        String[] labels = {"Footsteps", "Wood break", "Weapon attack", "Stone break", "Goblin call", "Sprinting"};
        double[] angles = {0, Math.PI / 4, Math.PI / 2, Math.PI * 3 / 4, Math.PI, Math.PI * 5 / 4};
        for (int i = 0; i < kinds.length; i++) {
            double angle = angles[i] - yaw;
            var source = new CueEngine.Point(p.x + Math.sin(angle) * 7, p.y, p.z - Math.cos(angle) * 7);
            session.engine.add(new CueEngine.Cue(ids[i], kinds[i], labels[i], source, time, 6000, true), listener, MAX_RANGE, false, time);
        }
    }
}
