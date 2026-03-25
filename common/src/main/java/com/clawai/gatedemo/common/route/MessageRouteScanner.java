package com.clawai.gatedemo.common.route;

import com.clawai.gatedemo.proto.GateOptionsProto;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 扫描 proto {@link Descriptors.FileDescriptor}，读取 {@code route_to} 和 {@code msg_id}
 * 自定义 option，自动填充 {@link MessageRouteRegistry}。
 */
public final class MessageRouteScanner {

    private static final Logger logger = LoggerFactory.getLogger(MessageRouteScanner.class);

    private MessageRouteScanner() {}

    /** 扫描给定的 proto 文件描述符，将含有 route_to + msg_id 的消息自动注册到路由表。 */
    public static void scan(Descriptors.FileDescriptor... fileDescriptors) {
        int count = 0;
        for (Descriptors.FileDescriptor fd : fileDescriptors) {
            for (Descriptors.Descriptor msgDesc : fd.getMessageTypes()) {
                count += scanMessage(msgDesc);
            }
        }
        logger.info("MessageRouteScanner 完成：共注册 {} 条路由，注册表总计 {} 条",
                count, MessageRouteRegistry.size());
    }

    private static int scanMessage(Descriptors.Descriptor msgDesc) {
        DescriptorProtos.MessageOptions options = msgDesc.getOptions();

        boolean hasRouteTo = options.hasField(GateOptionsProto.routeTo.getDescriptor());
        boolean hasMsgId = options.hasField(GateOptionsProto.msgId.getDescriptor());

        if (!hasRouteTo || !hasMsgId) {
            return 0;
        }

        String routeTo = (String) options.getField(GateOptionsProto.routeTo.getDescriptor());
        int msgId = (int) options.getField(GateOptionsProto.msgId.getDescriptor());
        String name = msgDesc.getName();

        MessageRouteRegistry.register(name, msgId, routeTo);
        logger.debug("注册路由: {} -> msgId={}, service={}", name, msgId, routeTo);
        return 1;
    }
}
