package com.clawai.gatedemo.gate.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 网关集群管理器 - 管理多Gateway实例的集群
 *
 * 设计原理：
 * 游戏网关需要支持高可用和负载均衡，通常部署多个实例。
 * 本类负责集群中各实例的健康检测和状态管理。
 *
 * 为什么需要集群管理？
 * 1. 高可用：单点故障不影响服务
 * 2. 负载均衡：将玩家分散到不同实例
 * 3. 扩缩容：可以根据负载动态调整实例数
 *
 * 本实现特点：
 * - 内存存储：不依赖外部存储，简单高效
 * - 心跳检测：定期检测实例健康状态
 * - 自动剔除：移除不健康的实例
 *
 * 扩展方向：
 * - 当前：内存存储，单机部署
 * - 未来：可以改用Redis/ZooKeeper实现多实例共享
 *
 * 工作流程：
 * 1. 应用启动 → 注册本实例信息
 * 2. 定时发送心跳 → 更新最后活跃时间
 * 3. 定时检查 → 移除超时实例
 * 4. 应用关闭 → 注销本实例
 *
 * 与负载均衡的关系：
 * - 负载均衡器（如Nginx）获取实例列表
 * - 根据在线人数选择最优实例
 * - 将玩家请求路由到对应实例
 *
 * 超时设计：
 * - 心跳间隔：30秒
 * - 超时阈值：120秒（4次心跳间隔）
 * - 为什么120秒？
 *   - 网络抖动可能导致单次心跳失败
 *   - 4次失败才判定为不健康，减少误判
 */
public class GateClusterManager {

    private static final Logger logger = LoggerFactory.getLogger(GateClusterManager.class);

    /** 本实例ID：IP:时间戳 */
    private final String instanceId;

    /** 实例存储：instanceId -> GateInstance */
    private final Map<String, GateInstance> instances = new ConcurrentHashMap<>();

    /** 定时任务调度器 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cluster-manager");
        t.setDaemon(true);
        return t;
    });

    /** 是否已注册 */
    private volatile boolean registered = false;

    /**
     * 构造函数
     *
     * 初始化流程：
     * 1. 生成唯一实例ID
     * 2. 启动心跳任务
     */
    public GateClusterManager() {
        // 生成唯一实例ID：IP+时间戳
        String id;
        try {
            id = InetAddress.getLocalHost().getHostAddress() + ":" + System.currentTimeMillis();
        } catch (Exception e) {
            id = "unknown:" + System.currentTimeMillis();
        }
        this.instanceId = id;
        
        // 启动心跳检测
        startHeartbeat();
    }

    /**
     * 注册本实例
     *
     * 调用时机：应用启动时
     *
     * @param port 监听端口
     * @param onlinePlayers 当前在线人数
     */
    public void registerInstance(int port, int onlinePlayers) {
        // 创建实例信息
        GateInstance instance = new GateInstance(instanceId, getLocalIp(), port, onlinePlayers, System.currentTimeMillis());
        
        // 存入集群
        instances.put(instanceId, instance);
        registered = true;
        
        logger.info("Registered gate instance: {}", instanceId);
    }

    /**
     * 更新在线人数
     *
     * 调用时机：玩家登录/登出时
     *
     * @param count 在线人数
     */
    public void updateOnlineCount(int count) {
        GateInstance instance = instances.get(instanceId);
        if (instance != null) {
            instance.setOnlinePlayers(count);
            instance.setLastHeartbeat(System.currentTimeMillis());
        }
    }

    /**
     * 注销本实例
     *
     * 调用时机：应用关闭时
     */
    public void unregisterInstance() {
        instances.remove(instanceId);
        registered = false;
        logger.info("Unregistered gate instance: {}", instanceId);
    }

    /**
     * 获取所有实例
     *
     * 返回副本，防止外部修改
     *
     * @return 实例Map
     */
    public Map<String, GateInstance> getAllInstances() {
        return new ConcurrentHashMap<>(instances);
    }

    /**
     * 获取指定实例
     *
     * @param instanceId 实例ID
     * @return 实例信息，不存在返回null
     */
    public GateInstance getInstance(String instanceId) {
        return instances.get(instanceId);
    }

    /**
     * 获取本实例ID
     *
     * @return 实例ID
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * 检查是否已注册
     *
     * @return true表示已注册
     */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * 启动心跳任务
     *
     * 任务内容：
     * 1. 更新本实例心跳时间
     * 2. 检查并移除不健康实例
     *
     * 执行间隔：30秒
     */
    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            // 1. 更新本实例心跳
            GateInstance instance = instances.get(instanceId);
            if (instance != null) {
                instance.setLastHeartbeat(System.currentTimeMillis());
            }

            // 2. 检查并移除不健康实例
            instances.entrySet().removeIf(entry -> {
                long inactive = System.currentTimeMillis() - entry.getValue().getLastHeartbeat();
                if (inactive > 120000) {  // 超过2分钟视为不健康
                    logger.info("Removed inactive gate instance: {}", entry.getKey());
                    return true;  // 移除
                }
                return false;  // 保留
            });
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 关闭集群管理器
     */
    public void shutdown() {
        unregisterInstance();
        scheduler.shutdown();
    }

    /**
     * 获取本机IP地址
     *
     * @return IP地址
     */
    private String getLocalIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * 网关实例信息
     *
     * 存储实例的基本信息和状态
     */
    public static class GateInstance {
        /** 实例ID */
        private final String instanceId;
        
        /** 主机地址 */
        private final String host;
        
        /** 端口 */
        private final int port;
        
        /** 当前在线人数 */
        private volatile int onlinePlayers;
        
        /** 最后心跳时间 */
        private volatile long lastHeartbeat;

        /**
         * 构造函数
         */
        public GateInstance(String instanceId, String host, int port, int onlinePlayers, long lastHeartbeat) {
            this.instanceId = instanceId;
            this.host = host;
            this.port = port;
            this.onlinePlayers = onlinePlayers;
            this.lastHeartbeat = lastHeartbeat;
        }

        public String getInstanceId() { return instanceId; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public int getOnlinePlayers() { return onlinePlayers; }
        public void setOnlinePlayers(int onlinePlayers) { this.onlinePlayers = onlinePlayers; }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public void setLastHeartbeat(long lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    }
}
