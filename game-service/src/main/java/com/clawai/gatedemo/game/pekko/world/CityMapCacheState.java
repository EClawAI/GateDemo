package com.clawai.gatedemo.game.pekko.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-city map cache placeholder; mutated only from {@link CityBehavior} mailbox.
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
