package com.clawai.gatedemo.game.pekko.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单城地图缓存占位；仅允许在 {@link WorldMapSandboxBehavior} 邮箱线程内修改。
 */
public final class CityMapCacheState {

    private final ConcurrentHashMap<String, Integer> tiles = new ConcurrentHashMap<>();

    public void putTile(String key, int value) {
        tiles.put(key, value);
    }

    public Map<String, Integer> snapshot() {
        return Map.copyOf(tiles);
    }
}
