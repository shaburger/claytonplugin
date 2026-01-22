package clayton.plugin;

import au.ellie.hyui.builders.HudBuilder;
import au.ellie.hyui.builders.HyUIHud;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class App extends JavaPlugin {
    // Base calendar used to convert world time into a day count.
    private static final LocalDate START_DATE = LocalDate.of(1, 1, 1);
    // Keep HUD refresh cheap but responsive.
    private static final long REFRESH_RATE_MS = 1000L;
    private final Map<UUID, HyUIHud> huds = new ConcurrentHashMap<>();
    private ResourceType<EntityStore, DayCounterResource> dayCounterResourceType;

    public App(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        // Resource persists with the world save (no manual file I/O needed).
        dayCounterResourceType = getEntityStoreRegistry().registerResource(
            DayCounterResource.class,
            "DayCounterResource",
            DayCounterResource.CODEC
        );
        getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        getEventRegistry().register(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
        // Keep a single, canonical command name to avoid clutter.
        ResetDayCounterCommand resetCommand = new ResetDayCounterCommand(this);
        getCommandRegistry().registerCommand(resetCommand);
        getLogger().atInfo().log("Registered command /%s", resetCommand.getName());
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        world.execute(() -> {
            // UI calls must run on the world thread.
            HyUIHud existing = huds.remove(playerRef.getUuid());
            if (existing != null) {
                existing.remove();
            }
            HyUIHud hud = HudBuilder.detachedHud()
                .fromHtml(buildHudHtml(getHudText(store)))
                .withRefreshRate(REFRESH_RATE_MS)
                .onRefresh(activeHud -> refreshHud(activeHud, store))
                .show(playerRef, store);
            huds.put(playerRef.getUuid(), hud);
        });
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        HyUIHud hud = huds.remove(playerRef.getUuid());
        if (hud != null) {
            hud.remove();
        }
    }

    private void refreshHud(HyUIHud hud, Store<EntityStore> store) {
        HudBuilder.detachedHud()
            .fromHtml(buildHudHtml(getHudText(store)))
            .updateExisting(hud);
    }

    private String getHudText(Store<EntityStore> store) {
        WorldTimeResource timeResource = store.getResource(WorldTimeResource.getResourceType());
        LocalDateTime gameDateTime = timeResource.getGameDateTime();
        long dayCount = ChronoUnit.DAYS.between(START_DATE, gameDateTime.toLocalDate()) + 1;
        // Use a world resource so the day counter persists with the save.
        DayCounterResource resource = store.getResource(dayCounterResourceType);
        if (resource == null) {
            resource = new DayCounterResource();
            store.replaceResource(dayCounterResourceType, resource);
        }

        if (!resource.isInitialized()) {
            resource.initialize(dayCount);
        } else if (dayCount < resource.getLastRawDay()) {
            // If the raw world time went backwards, keep the saved day steady.
            resource.setLastRawDay(dayCount);
        } else if (dayCount > resource.getLastRawDay()) {
            // Only advance when the raw day advances.
            resource.advanceBy(dayCount - resource.getLastRawDay());
        }

        return "Day: " + resource.getSavedDay();
    }

    private void resetDayCounter(Store<EntityStore> store, long rawDay, long newDay) {
        DayCounterResource resource = store.getResource(dayCounterResourceType);
        if (resource == null) {
            resource = new DayCounterResource();
            store.replaceResource(dayCounterResourceType, resource);
        }
        resource.reset(rawDay, newDay);
    }

    private static String buildHudHtml(String text) {
        return "<div style='anchor-top: 25; anchor-left: 0; anchor-right: 0; "
            + "text-align: center; font-size: 36; font-weight: bold;'>"
            + "<p style='font-size: 36; font-weight: bold;'>" + text + "</p>"
            + "</div>";
    }

    private static final class DayCounterResource implements Resource<EntityStore> {
        // Codec controls how the resource is serialized into the world save.
        private static final BuilderCodec<DayCounterResource> CODEC =
            BuilderCodec.builder(DayCounterResource.class, DayCounterResource::new)
                .addField(new KeyedCodec<>("SavedDay", Codec.LONG),
                    (data, value) -> data.savedDay = value,
                    data -> data.savedDay)
                .addField(new KeyedCodec<>("LastRawDay", Codec.LONG),
                    (data, value) -> data.lastRawDay = value,
                    data -> data.lastRawDay)
                .addField(new KeyedCodec<>("Initialized", Codec.BOOLEAN),
                    (data, value) -> data.initialized = value,
                    data -> data.initialized)
                .build();

        private long savedDay;
        private long lastRawDay;
        private boolean initialized;

        private DayCounterResource() {
            this.savedDay = 0L;
            this.lastRawDay = 0L;
            this.initialized = false;
        }

        private DayCounterResource(DayCounterResource clone) {
            this.savedDay = clone.savedDay;
            this.lastRawDay = clone.lastRawDay;
            this.initialized = clone.initialized;
        }

        @Override
        public Resource<EntityStore> clone() {
            return new DayCounterResource(this);
        }

        private boolean isInitialized() {
            return initialized;
        }

        private long getSavedDay() {
            return savedDay;
        }

        private long getLastRawDay() {
            return lastRawDay;
        }

        private void initialize(long dayCount) {
            this.savedDay = dayCount;
            this.lastRawDay = dayCount;
            this.initialized = true;
        }

        private void advanceBy(long delta) {
            this.savedDay += delta;
            this.lastRawDay += delta;
        }

        private void setLastRawDay(long dayCount) {
            this.lastRawDay = dayCount;
        }

        private void reset(long rawDay, long newDay) {
            this.savedDay = newDay;
            this.lastRawDay = rawDay;
            this.initialized = true;
        }
    }

    private static final class ResetDayCounterCommand extends AbstractPlayerCommand {
        private final App plugin;

        private ResetDayCounterCommand(App plugin) {
            super("resetdaycounter", "Reset the day counter to Day 0.");
            this.plugin = plugin;
        }

        @Override
        protected void execute(CommandContext commandContext, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            // Commands run async; use world thread before touching world data.
            world.execute(() -> {
                WorldTimeResource timeResource = store.getResource(WorldTimeResource.getResourceType());
                LocalDateTime gameDateTime = timeResource.getGameDateTime();
                long dayCount = ChronoUnit.DAYS.between(START_DATE, gameDateTime.toLocalDate()) + 1;
                plugin.resetDayCounter(store, dayCount, 0L);
                playerRef.sendMessage(Message.raw("Day counter reset to Day 0."));
            });
        }
    }
}
