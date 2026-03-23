package com.clawai.gatedemo.core.persistence;

/**
 * 所有持久化实体的基础契约
 *
 * @param <ID> 实体主键类型
 */
public interface BaseEntity<ID> {

    ID getId();
}
