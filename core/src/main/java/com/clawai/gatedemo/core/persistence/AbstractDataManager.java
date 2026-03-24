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

    /** 已加载实体的进程内缓存；与 Mongo 可能短暂不一致，以 dirty 标记与 flush 为准。 */
    private final ConcurrentHashMap<ID, T> cache = new ConcurrentHashMap<>();
    /** 自上次成功落库以来被修改过主键集合；flush/saveNow 成功后从中移除。 */
    private final Set<ID> dirtySet = ConcurrentHashMap.newKeySet();
    private final MongoTemplate mongoTemplate;

    /**
     * @param mongoTemplate 用于读写 MongoDB 的模板（集合名由子类 {@link #getCollectionName()} 决定）
     */
    protected AbstractDataManager(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 当库中不存在该主键时，构造新实体的默认内存形态；{@link #load(Object)} 会随后立即持久化。
     *
     * @param id 主键
     * @return 未写入缓存前的新实体实例（由 load 负责放入缓存）
     */
    protected abstract T createDefault(ID id);

    /** @return 当前管理器对应的 MongoDB 集合名 */
    protected abstract String getCollectionName();

    /** @return 实体类型，用于 {@code MongoTemplate} 反序列化 */
    protected abstract Class<T> getEntityClass();

    /**
     * 加载实体：先读缓存；未命中则查 Mongo；仍不存在则 {@link #createDefault(Object)} 并立即 save，再放入缓存。
     *
     * @param id 主键
     * @return 缓存或库中或新建的实体，永不为 null（除非 createDefault 返回 null，子类不应如此）
     * @throws RuntimeException 底层 Mongo 异常会向上抛出（部分路径会记录日志后抛出）
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
     * 仅从进程内缓存读取，不访问 Mongo；未 load 过则返回 null。
     *
     * @param id 主键
     * @return 缓存中的实体，无则 null
     */
    public T get(ID id) {
        return cache.get(id);
    }

    /**
     * 在缓存命中的前提下修改内存实体并标记为 dirty，供后续 flush；同一实体上使用实例锁串行化 mutator。
     *
     * @param id      主键
     * @param mutator 对实体原地修改，勿替换引用
     * @apiNote 实体不在缓存时直接忽略并打 warn，不抛异常
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
     * 将指定 id 的缓存实体立即写入 Mongo；成功后从 dirty 集合移除。失败仅打日志，不抛出。
     *
     * @param id 主键；不在缓存则 noop
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
     * 对当前 dirty 快照逐条 save；成功则清除对应 dirty，失败则保留以便下次重试（可能并发产生新 dirty）。
     *
     * @apiNote 若缓存中已无实体，会从 dirty 中移除该 id，避免泄漏
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
     * 若该 id 为 dirty 则先 {@link #saveNow(Object)}，再从缓存与 dirty 集合移除。
     *
     * @param id 主键
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
