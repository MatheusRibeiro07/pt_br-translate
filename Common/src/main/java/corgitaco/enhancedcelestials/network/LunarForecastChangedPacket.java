package corgitaco.enhancedcelestials.network;

import corgitaco.enhancedcelestials.EnhancedCelestialsWorldData;
import corgitaco.enhancedcelestials.api.lunarevent.LunarDimensionSettings;
import corgitaco.enhancedcelestials.core.EnhancedCelestialsContext;
import corgitaco.enhancedcelestials.lunarevent.LunarEventInstance;
import corgitaco.enhancedcelestials.lunarevent.LunarForecast;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;

public class LunarForecastChangedPacket implements S2CPacket {

    private final LunarForecast.Data lunarForecast;
    private final boolean isNight; // TODO: When is mojang actually going to sync the client level's `isNight` method?!?!?!!?!?!?

    public LunarForecastChangedPacket(LunarForecast forecast, boolean isNight) {
        this(forecast.data(), isNight);
    }

    public LunarForecastChangedPacket(LunarForecast.Data lunarForecast, boolean isNight) {
        this.lunarForecast = lunarForecast;
        this.isNight = isNight;
    }


    public static LunarForecastChangedPacket readFromPacket(FriendlyByteBuf buf) {
        try {
            return new LunarForecastChangedPacket(buf.readWithCodec(LunarForecast.Data.CODEC), buf.readBoolean());
        } catch (Exception e) {
            throw new IllegalStateException("Lunar Forecast packet could not be read. This is really really bad...\n\n" + e.getMessage());
        }
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        try {
            buf.writeWithCodec(LunarForecast.Data.CODEC, this.lunarForecast);
            buf.writeBoolean(this.isNight);
        } catch (Exception e) {
            throw new IllegalStateException("Lunar Forecast packet could not be written to. This is really really bad...\n\n" + e.getMessage());
        }
    }

    @Override
    public void handle(Level level) {
        if (level != null) {
            EnhancedCelestialsContext enhancedCelestialsContext = ((EnhancedCelestialsWorldData) level).getLunarContext();
            if (enhancedCelestialsContext != null) {
                LunarForecast lunarForecast = enhancedCelestialsContext.getLunarForecast();
                lunarForecast.getForecast().clear();
                lunarForecast.getForecast().addAll(this.lunarForecast.forecast());

                lunarForecast.getPastEvents().clear();
                lunarForecast.getPastEvents().addAll(this.lunarForecast.pastEvents());

                lunarForecast.setLastCheckedGameTime(this.lunarForecast.lastCheckedGameTime());

                if (!lunarForecast.getForecast().isEmpty()) {
                    LunarEventInstance lunarEventInstance = lunarForecast.getForecast().get(0);
                    LunarDimensionSettings lunarDimensionSettings = lunarForecast.getDimensionSettingsHolder().value();
                    long currentDay = level.getDayTime() / lunarDimensionSettings.dayLength();
                    if (lunarEventInstance.active(currentDay) && this.isNight) {
                        lunarForecast.setCurrentEvent(lunarEventInstance.getLunarEventKey());
                    } else {
                        lunarForecast.setCurrentEvent(lunarDimensionSettings.defaultEvent());
                    }
                }
            }
        }
    }
}