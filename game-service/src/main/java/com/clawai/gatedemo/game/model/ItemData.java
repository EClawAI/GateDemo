package com.clawai.gatedemo.game.model;

/**
 * 玩家背包中单种道具的数量快照，与 {@link PlayerData} 组合表达持久化库存结构。
 */
public class ItemData {

    /** 道具配置 ID。 */
    private int itemId;
    /** 持有数量。 */
    private int count;

    public ItemData() {
    }

    public ItemData(int itemId, int count) {
        this.itemId = itemId;
        this.count = count;
    }

    public int getItemId() {
        return itemId;
    }

    public void setItemId(int itemId) {
        this.itemId = itemId;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }
}
