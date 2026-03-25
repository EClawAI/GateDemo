package com.clawai.gatedemo.core.message;

import com.google.protobuf.MessageLite;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 Handler 处理哪个 proto 消息类型。
 * 框架在 Spring 启动时自动扫描此注解，从 value 推导消息名和 parser，
 * 再从 {@link com.clawai.gatedemo.common.route.MessageRouteRegistry} 查到 messageId 完成注册。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MessageMapping {

    /** 处理的 proto 消息类（如 {@code CgBattleMove.class}）。 */
    Class<? extends MessageLite> value();
}
