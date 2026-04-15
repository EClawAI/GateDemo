package com.clawai.gatedemo.game.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.HashMap;
import java.util.Map;

/**
 * Mongo {@code city_world_state}：单城地图缓存快照（键值占位，由 {@link com.clawai.gatedemo.game.pekko.world.WorldMapSandboxBehavior} 单写者更新）。
 */
@Document("city_world_state")
public class CityWorldStateDocument {

    @Id
    private String id;

    private long regionId;
    private long cityId;
    private Map<String, Integer> tiles = new HashMap<>();

    public CityWorldStateDocument() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getRegionId() {
        return regionId;
    }

    public void setRegionId(long regionId) {
        this.regionId = regionId;
    }

    public long getCityId() {
        return cityId;
    }

    public void setCityId(long cityId) {
        this.cityId = cityId;
    }

    public Map<String, Integer> getTiles() {
        return tiles;
    }

    public void setTiles(Map<String, Integer> tiles) {
        this.tiles = tiles != null ? tiles : new HashMap<>();
    }
}
