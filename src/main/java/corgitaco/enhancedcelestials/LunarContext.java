package corgitaco.enhancedcelestials;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import corgitaco.enhancedcelestials.api.EnhancedCelestialsRegistry;
import corgitaco.enhancedcelestials.api.lunarevent.LunarEvent;
import corgitaco.enhancedcelestials.lunarevent.Moon;
import corgitaco.enhancedcelestials.save.LunarEventSavedData;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class LunarContext {

    public static final String CONFIG_NAME = "lunar-settings.toml";
    private static final LunarEvent DEFAULT = Moon.MOON;

    public static final Codec<LunarContext> PACKET_CODEC = RecordCodecBuilder.create((builder) -> {
        return builder.group(LunarForecast.CODEC.fieldOf("lunarForecast").forGetter((lunarContext -> {
            return lunarContext.forecast;
        })), ResourceLocation.CODEC.fieldOf("worldID").forGetter((weatherEventContext) -> {
            return weatherEventContext.worldID;
        }), Codec.unboundedMap(Codec.STRING, LunarEvent.CODEC).fieldOf("weatherEvents").forGetter((weatherEventContext) -> {
            return weatherEventContext.lunarEvents;
        })).apply(builder, LunarContext::new);
    });

    private final Map<String, LunarEvent> lunarEvents = new HashMap<>();
    private final LunarForecast lunarForecast;
    private final ResourceLocation worldID;
    private final Path lunarConfigPath;
    private final Path lunarEventsConfigPath;
    private final File lunarConfigFile;
    private LunarEvent currentEvent;
    private LunarForecast forecast;

    public LunarContext(ServerWorld world) {
        this.worldID = world.getDimensionKey().getLocation();
        this.lunarConfigPath = Main.CONFIG_PATH.resolve(worldID.getNamespace()).resolve(worldID.getPath()).resolve("lunar");
        this.lunarEventsConfigPath = this.lunarConfigPath.resolve("events");
        this.lunarConfigFile = this.lunarConfigPath.resolve(CONFIG_NAME).toFile();
        handleEventConfigs(false);
        this.lunarForecast = getAndComputeLunarForecast(world).getForecast();
        assert lunarForecast != null;
        LunarEventInstance nextLunarEvent = lunarForecast.getForecast().get(0);
        this.currentEvent = nextLunarEvent.getDaysUntil() == 0 ? nextLunarEvent.getEvent(this.lunarEvents) : DEFAULT;
    }

    public LunarContext(LunarForecast lunarForecast, ResourceLocation worldID, @Nullable Map<String, LunarEvent> lunarEvents) {
        this.worldID = worldID;
        this.lunarConfigPath = Main.CONFIG_PATH.resolve(worldID.getNamespace()).resolve(worldID.getPath()).resolve("lunar");
        this.lunarEventsConfigPath = this.lunarConfigPath.resolve("events");
        this.lunarConfigFile = this.lunarConfigPath.resolve(CONFIG_NAME).toFile();
        boolean isClient = lunarEvents != null;
        LunarEventInstance nextLunarEvent = lunarForecast.getForecast().get(0);
        this.currentEvent = nextLunarEvent.getDaysUntil() == 0 ? nextLunarEvent.getEvent(this.lunarEvents) : DEFAULT;
        this.lunarForecast = lunarForecast;
    }

    public LunarEventSavedData getAndComputeLunarForecast(ServerWorld world) {
        LunarEventSavedData lunarEventSavedData = LunarEventSavedData.get(world);
        if (lunarEventSavedData.getForecast() == null) {
            lunarEventSavedData.setForecast(computeInitialLunarForecast(world));
        }
        return lunarEventSavedData;
    }

    public LunarForecast computeInitialLunarForecast(ServerWorld world) {
        Random random = new Random(world.getSeed() + world.getDimensionKey().getLocation().hashCode());
        long gameTime = world.getGameTime();
        List<LunarEventInstance> lunarEventInstances = new ArrayList<>();

        Object2IntArrayMap<LunarEvent> eventByLastTime = new Object2IntArrayMap<>();
        int lastDay = 0;

        ArrayList<String> eventKeys = new ArrayList<>(this.lunarEvents.keySet());
        Collections.shuffle(eventKeys, random);
        for (int day = 0; day < 100 /*TODO: Add custom year length in days*/; day++) {
            gameTime += 24000; // TODO: Allow custom day lengths

            for (String key : eventKeys) {
                LunarEvent value = this.lunarEvents.get(key);
                if ((day - eventByLastTime.getOrDefault(value, 25)) > value.getMinNumberOfNightsBetween() && (day - lastDay) > 5/*TODO: Add min day count between events*/ && value.getChance() > random.nextDouble()) {
                    lastDay = day;
                    lunarEventInstances.add(new LunarEventInstance(key, (int) (gameTime / 24000)));
                    eventByLastTime.put(value, day);
                }
            }
        }
        return new LunarForecast(lunarEventInstances, gameTime);
    }

    public LunarEvent getCurrentEvent() {
        return currentEvent;
    }

    public void handleEventConfigs(boolean isClient) {
        File eventsDirectory = this.lunarEventsConfigPath.toFile();
        if (!eventsDirectory.exists()) {
            createDefaultEventConfigs();
        }

        File[] files = eventsDirectory.listFiles();

        if (files.length == 0) {
            createDefaultEventConfigs();
        }

        iterateAndReadConfiguredEvents(files, isClient);
    }

    private void iterateAndReadConfiguredEvents(File[] files, boolean isClient) {
        for (File configFile : files) {
            String absolutePath = configFile.getAbsolutePath();
//            if (absolutePath.endsWith(".toml")) {
//                readToml(isClient, configFile);

//            } else if (absolutePath.endsWith(".json")) {
            readJson(isClient, configFile);
//            }
        }
    }


    public void createDefaultEventConfigs() {
        for (Map.Entry<ResourceLocation, LunarEvent> entry : EnhancedCelestialsRegistry.DEFAULT_EVENTS.entrySet()) {
            ResourceLocation location = entry.getKey();
            LunarEvent event = entry.getValue();
            Optional<RegistryKey<Codec<? extends LunarEvent>>> optionalKey = EnhancedCelestialsRegistry.LUNAR_EVENT.getOptionalKey(event.codec());

            if (optionalKey.isPresent()) {
//                if (BetterWeatherConfig.SERIALIZE_AS_JSON) {
                createJsonEventConfig(event, location.toString());
//                } else {
//                    createTomlEventConfig(event, location.toString());
//                }
            } else {
                throw new IllegalStateException("Weather Event Key for codec not there when requested: " + event.getClass().getSimpleName());
            }
        }
    }


    private void createJsonEventConfig(LunarEvent weatherEvent, String weatherEventID) {
        Path configFile = this.lunarEventsConfigPath.resolve(weatherEventID.replace(":", "-") + ".json");
        JsonElement jsonElement = LunarEvent.CODEC.encodeStart(JsonOps.INSTANCE, weatherEvent).result().get();

        try {
            Files.createDirectories(configFile.getParent());
            Files.write(configFile, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(jsonElement).getBytes());
        } catch (IOException e) {
            Main.LOGGER.error(e.toString());
        }
    }


    private void readJson(boolean isClient, File configFile) {
        try {
            String name = configFile.getName().replace(".json", "").toLowerCase();
            LunarEvent decodedValue = LunarEvent.CODEC.decode(JsonOps.INSTANCE, new JsonParser().parse(new FileReader(configFile))).resultOrPartial(Main.LOGGER::error).get().getFirst().setName(name);
            if (isClient /*&& !BetterWeather.CLIENT_CONFIG.useServerClientSettings*/) {
                if (this.lunarEvents.containsKey(name)) {
                    LunarEvent lunarEvent = this.lunarEvents.get(name);
                    lunarEvent.setClientSettings(decodedValue.getClientSettings());
                    lunarEvent.setLunarEventClient(lunarEvent.getClientSettings().createClient());
                }
            } else {
                this.lunarEvents.put(name, decodedValue);
            }
        } catch (FileNotFoundException e) {
            Main.LOGGER.error(e.toString());
        }
    }
}