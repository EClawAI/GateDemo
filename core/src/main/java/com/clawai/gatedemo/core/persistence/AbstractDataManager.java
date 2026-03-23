package com.clawai.gatedemo.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 通用持久化管理基类，提供内存缓存 + dirty tracking + 定时刷盘 + 立即写入能力。
 * <p>
 * 子类需实现：
 * <ul>
 *   <li>{@link #createDefault(Object)} - 生成新实体的默认数据</li>
 *   <li>{@link #getCollectionName()} - MongoDB 集合名称</li>
 *   <li>{@link #getEntityClass()} - 实体 Class 对象</li>
 * </ul>
 *
 * @param <ID> 主键类型
 * @param <T>  实体类型
 */
public abstract class AbstractDataManager<ID, T extends BaseEntity<ID>> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ConcurrentHashMap<ID, T> cache = new ConcurrentHashMap<>();
    private final Set<ID> dirtySet = ConcurrentHashMap.newKeySet();
    private final MongoTemplate mongoTemplate;

    protected AbstractDataManager(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    protected abstract T createDefault(ID id);

    protected abstract String getCollectionName();

    protected abstract Class<T> getEntityClass();

    /**
     * 加载实体：缓存优先 → MongoDB 查询 → 不存在则 createDefault 并立即写入
     */
    public T load(ID id) {
        T cached = cache.get(id);
        if (cached != null) {
            return cached;
        }

        try {
            Query query = new Query(Criteria.where("_id").is(id));
            T entity = mongoTemplate.findOne(query, getEntityClass(), getCollectionName());

            if (entity != null) {
                cache.put(id, entity);
                logger.debug("Loaded from MongoDB: id={}, collection={}", id, getCollectionName());
                return entity;
            }
        } catch (Exception e) {
            logger.error("Failed to load from MongoDB: id={}, error={}", id, e.getMessage());
            throw e;
        }

        T defaultEntity = createDefault(id);
        try {
            mongoTemplate.save(defaultEntity, getCollectionName());
            logger.info("Created default entity and saved to MongoDB: id={}, collection={}", id, getCollectionName());
        } catch (Exception e) {
            logger.error("Failed to save default entity to MongoDB: id={}, error={}", id, e.getMessage());
            throw e;
        }

        cache.put(id, defaultEntity);
        return defaultEntity;
    }

    /**
     * 仅从内存缓存获取，不触发 MongoDB 查询
     */
    public T get(ID id) {
        return cache.get(id);
    }

    /**
     * 修改内存中的实体并标记 dirty。对同一实体的并发修改通过 synchronized 保证原子性。
     */
    public void update(ID id, Consumer<T> mutator) {
        T entity = cache.get(id);
        if (entity == null) {
            logger.warn("Cannot update: entity not in cache, id={}", id);
            return;
        }

        synchronized (entity) {
            mutator.accept(entity);
        }
        dirtySet.add(id);
    }

    /**
     * 立即将缓存中的实体写入 MongoDB，并清除 dirty 标记
     */
    public void saveNow(ID id) {
        T entity = cache.get(id);
        if (entity == null) {
            logger.debug("saveNow skipped: entity not in cache, id={}", id);
            return;
        }

        try {
            mongoTemplate.save(entity, getCollectionName());
            dirtySet.remove(id);
            logger.debug("Saved to MongoDB immediately: id={}, collection={}", id, getCollectionName());
        } catch (Exception e) {
            logger.error("Failed to saveNow: id={}, error={}", id, e.getMessage());
        }
    }

    /**
     * 遍历 dirty set 批量写入 MongoDB。写入失败的 id 保留在 dirty set 中等待下次重试。
     */
    public void flushAll() {
        if (dirtySet.isEmpty()) {
            return;
        }

        Set<ID> snapshot = Set.copyOf(dirtySet);
        int success = 0;
        int failed = 0;

        for (ID id : snapshot) {
            T entity = cache.get(id);
            if (entity == null) {
                dirtySet.remove(id);
                continue;
            }

            try {
                mongoTemplate.save(entity, getCollectionName());
                dirtySet.remove(id);
                success++;
            } catch (Exception e) {
                failed++;
                logger.error("flushAll failed for id={}: {}", id, e.getMessage());
            }
        }

        if (success > 0 || failed > 0) {
            logger.info("flushAll complete: collection={}, success={}, failed={}", getCollectionName(), success, failed);
        }
    }

    /**
     * 驱逐缓存。若 dirty 则先写入 MongoDB 再移除。
     */
    public void evict(ID id) {
        if (dirtySet.contains(id)) {
            saveNow(id);
        }
        cache.remove(id);
        dirtySet.remove(id);
        logger.debug("Evicted from cache: id={}", id);
    }
}
