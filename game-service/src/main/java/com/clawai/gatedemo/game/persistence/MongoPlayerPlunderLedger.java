package com.clawai.gatedemo.game.persistence;

import com.clawai.gatedemo.game.model.PlayerData;
import com.clawai.gatedemo.game.model.PlunderSettlementRecord;
import com.clawai.gatedemo.game.pekko.session.PlunderDuplicate;
import com.clawai.gatedemo.game.pekko.session.PlunderOk;
import com.clawai.gatedemo.game.pekko.session.PlunderRejected;
import com.clawai.gatedemo.game.pekko.session.PlunderSettleResponse;
import com.clawai.gatedemo.game.pekko.session.PlayerPlunderLedger;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 将掠夺结算写入 {@code player_data}：金币扣减与 {@code battleId} 幂等记录同事务落库（{@link PlayerDataManager#saveNow}）。
 */
@Component
public class MongoPlayerPlunderLedger implements PlayerPlunderLedger {

    private final PlayerDataManager playerDataManager;

    public MongoPlayerPlunderLedger(PlayerDataManager playerDataManager) {
        this.playerDataManager = playerDataManager;
    }

    @Override
    public PlunderSettleResponse trySettle(long playerId, long battleId, long requestedPlunder) {
        if (requestedPlunder <= 0) {
            return new PlunderRejected(battleId, "requestedPlunder must be positive");
        }
        PlayerData p = playerDataManager.load(playerId);
        synchronized (p) {
            List<PlunderSettlementRecord> list = p.getPlunderSettlements();
            if (list == null) {
                list = new ArrayList<>();
                p.setPlunderSettlements(list);
            }
            for (PlunderSettlementRecord r : list) {
                if (r.getBattleId() == battleId) {
                    return new PlunderDuplicate(battleId, r.getActualAmount());
                }
            }
            long actual = Math.min(requestedPlunder, p.getGold());
            p.setGold(p.getGold() - actual);
            list.add(new PlunderSettlementRecord(battleId, actual));
            playerDataManager.saveNow(playerId);
            return new PlunderOk(battleId, actual);
        }
    }
}
