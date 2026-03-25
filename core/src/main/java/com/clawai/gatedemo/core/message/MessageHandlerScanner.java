package com.clawai.gatedemo.core.message;

import com.google.protobuf.MessageLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.Map;

/**
 * Spring 启动时自动扫描所有标注 {@link MessageMapping} 的 Bean，
 * 提取 proto class 并注册到 {@link MessageHandlerRegistry}。
 */
public class MessageHandlerScanner implements ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(MessageHandlerScanner.class);

    private final MessageHandlerRegistry registry;

    public MessageHandlerScanner(MessageHandlerRegistry registry) {
        this.registry = registry;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        Map<String, Object> beans = ctx.getBeansWithAnnotation(MessageMapping.class);
        int count = 0;
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            MessageMapping ann = bean.getClass().getAnnotation(MessageMapping.class);
            if (ann == null) {
                continue;
            }

            Class<? extends MessageLite> msgClass = ann.value();
            if (bean instanceof IMessageHandler handler) {
                registry.register(msgClass, handler);
                count++;
            } else {
                logger.warn("Bean {} 标注了 @MessageMapping 但未实现 IMessageHandler，跳过",
                        entry.getKey());
            }
        }
        logger.info("MessageHandlerScanner 完成：扫描到 {} 个 handler，注册表共 {} 条",
                count, registry.size());
    }
}
