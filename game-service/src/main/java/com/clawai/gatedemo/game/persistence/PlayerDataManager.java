package com.clawai.gatedemo.game.persistence;

import com.clawai.gatedemo.core.persistence.AbstractDataManager;
import com.clawai.gatedemo.game.model.ItemData;
import com.clawai.gatedemo.game.model.PlayerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于核心模块 {@link AbstractDataManager} 的玩家数据管理：负责默认建号模板与定时刷盘，隔离 Mongo 访问与业务逻辑。
 */
@Component
public class PlayerDataManager extends AbstractDataManager<Long, PlayerData> {

    private static final Logger logger = LoggerFactory.getLogger(PlayerDataManager.class);

    public PlayerDataManager(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    /**
     * 为新玩家生成默认档案（昵称、初始资源、示例背包），仅内存形态；由基类 {@code load} 负责首次落库。
     *
     * @param playerId 玩家主键
     * @return 带齐默认字段的 {@link PlayerData}
     */
    @Override
    protected PlayerData createDefault(Long playerId) {
        long now = System.currentTimeMillis();

        PlayerData player = new PlayerData();
        player.setId(playerId);
        player.setNickname("Player_" + playerId);
        player.setLevel(1);
        player.setExp(0);
        player.setGold(1000);
        player.setDiamond(100);
        player.setVipLevel(0);
        player.setCreateTime(now);
        player.setLastLoginTime(now);
        player.setLoginCount(1);
        player.setItems(List.of(
                new ItemData(1001, 1),
                new ItemData(2001, 5),
                new ItemData(3001, 1)
        ));

        logger.info("Generated default player data: playerId={}, nickname={}", playerId, player.getNickname());
        return player;
    }

    /**
     * @return MongoDB 集合名 {@code player_data}
     */
    @Override
    protected String getCollectionName() {
        return "player_data";
    }

    /**
     * @return 实体类型 {@link PlayerData}
     */
    @Override
    protected Class<PlayerData> getEntityClass() {
        return PlayerData.class;
    }

    /**
     * 定时将 dirty 玩家数据批量刷入 Mongo；间隔由 {@code game.persistence.flush-interval} 配置（默认 30s）。
     *
     * @apiNote 与业务触发的 {@code saveNow} 并存，降低丢数据窗口
     */
    @Scheduled(fixedRateString = "${game.persistence.flush-interval:30000}")
    public void scheduledFlush() {
        flushAll();
    }
}
