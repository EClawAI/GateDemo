package com.clawai.gatedemo.gate.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聚合所有 {@link ServiceRouter} 实现，按 serviceType 索引，供 Handler 快速查找目标路由器。
 */
@Component
public class ServiceRouterManager {

    private static final Logger logger = LoggerFactory.getLogger(ServiceRouterManager.class);

    private final Map<String, ServiceRouter> routers = new HashMap<>();

    public ServiceRouterManager(List<ServiceRouter> routerList) {
        for (ServiceRouter router : routerList) {
            routers.put(router.serviceType(), router);
            logger.info("注册 ServiceRouter: {} -> {}", router.serviceType(), router.getClass().getSimpleName());
        }
    }

    /** 按服务类型查找路由器，未注册返回 null。 */
    public ServiceRouter getRouter(String serviceType) {
        return routers.get(serviceType);
    }
}
