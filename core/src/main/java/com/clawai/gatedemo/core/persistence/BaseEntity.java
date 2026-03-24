package com.clawai.gatedemo.core.persistence;

/**
 * 所有持久化实体的基础契约
 *
 * @param <ID> 实体主键类型
 */
public interface BaseEntity<ID> {

    /**
     * 返回实体的业务主键（MongoDB 中通常映射为 {@code _id}）。
     *
     * @return 主键值，新建未持久化前可能为 null（视具体实体而定）
     */
    ID getId();
}
