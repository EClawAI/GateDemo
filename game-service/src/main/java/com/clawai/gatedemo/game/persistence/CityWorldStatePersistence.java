package com.clawai.gatedemo.game.persistence;

import java.util.Map;

/**
 * 城地图缓存快照持久化：与 {@link com.clawai.gatedemo.game.pekko.world.CityMapCacheState} 对齐，供进程重启后回填。
 */
public interface CityWorldStatePersistence {

    void saveSnapshot(long regionId, long cityId, Map<String, Integer> tiles);

    Map<String, Integer> loadSnapshot(long regionId, long cityId);
}
