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

@Component
public class PlayerDataManager extends AbstractDataManager<Long, PlayerData> {

    private static final Logger logger = LoggerFactory.getLogger(PlayerDataManager.class);

    public PlayerDataManager(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

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

    @Override
    protected String getCollectionName() {
        return "player_data";
    }

    @Override
    protected Class<PlayerData> getEntityClass() {
        return PlayerData.class;
    }

    @Scheduled(fixedRateString = "${game.persistence.flush-interval:30000}")
    public void scheduledFlush() {
        flushAll();
    }
}
