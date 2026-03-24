package com.clawai.gatedemo.gate.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis Streams 消费者组从 gate→game 消息流拉取条目并确认消费，将异步下行与游戏服处理解耦。
 */
@Component
public class MessageQueueConsumer {

    private static final Logger logger = LoggerFactory.getLogger(MessageQueueConsumer.class);

    private static final String STREAM_KEY = "game:message:queue";
    private static final String GROUP_NAME = "gate-consumers";
    private static final String CONSUMER_NAME = "gate-";

    private final RedisTemplate<String, Object> redisTemplate;
    private final MessageQueueProducer producer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = false;

    public MessageQueueConsumer(RedisTemplate<String, Object> redisTemplate, MessageQueueProducer producer) {
        this.redisTemplate = redisTemplate;
        this.producer = producer;
    }

    @PostConstruct
    public void init() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP_NAME);
            logger.info("Created consumer group: {}", GROUP_NAME);
        } catch (Exception e) {
            logger.warn("Redis stream not available, offline message queue disabled: {}", e.getMessage());
            return;
        }

        String consumerName = CONSUMER_NAME + UUID.randomUUID().toString().substring(0, 8);
        startConsuming(consumerName);
    }

    public void startConsuming(String consumerName) {
        if (running) {
            return;
        }
        running = true;

        scheduler.scheduleAtFixedRate(() -> {
            if (!running) {
                return;
            }
            consumeMessages(consumerName);
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    private void consumeMessages(String consumerName) {
        try {
            List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream().read(
                    Consumer.from(GROUP_NAME, consumerName),
                    StreamReadOptions.empty().count(10).block(Duration.ofMillis(500)),
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
            );

            if (messages != null && !messages.isEmpty()) {
                for (MapRecord<String, Object, Object> record : messages) {
                    processMessage(record);
                    redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
                }
            }
        } catch (Exception e) {
            logger.debug("Error consuming messages: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void processMessage(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> rawMessage = record.getValue();
            Map<String, Object> message = new HashMap<>();
            for (Map.Entry<Object, Object> entry : rawMessage.entrySet()) {
                message.put(String.valueOf(entry.getKey()), entry.getValue());
            }

            Long playerId = toLong(message.get("player_id"));
            Short messageId = toShort(message.get("message_id"));

            logger.debug("Processing message: playerId={}, msgId={}", playerId, messageId);
        } catch (Exception e) {
            logger.error("Error processing message: {}", e.getMessage());
        }
    }

    public void stopConsuming() {
        running = false;
    }

    public void shutdown() {
        stopConsuming();
        scheduler.shutdown();
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Integer) return ((Integer) value).longValue();
        return Long.parseLong(value.toString());
    }

    private Short toShort(Object value) {
        if (value == null) return null;
        if (value instanceof Short) return (Short) value;
        if (value instanceof Integer) return ((Integer) value).shortValue();
        return Short.parseShort(value.toString());
    }
}
