package com.clawai.gatedemo.game.service;

import com.clawai.gatedemo.game.config.GameConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

@Service
public class UpstreamConsumerService {

    private static final Logger logger = LoggerFactory.getLogger(UpstreamConsumerService.class);

    private final GameConfig gameConfig;
    private final RedisReactiveCommands<String, String> redisCommands;
    private final ObjectMapper objectMapper;
    private final GameMessageHandler messageHandler;

    private Disposable consumerDisposable;
    private volatile boolean running = true;

    public UpstreamConsumerService(GameConfig gameConfig, RedisReactiveCommands<String, String> redisCommands,
                                    ObjectMapper objectMapper, GameMessageHandler messageHandler) {
        this.gameConfig = gameConfig;
        this.redisCommands = redisCommands;
        this.objectMapper = objectMapper;
        this.messageHandler = messageHandler;
    }

    @PostConstruct
    public void startConsumer() {
        String streamKey = "stream:up:game:" + gameConfig.getId();
        String consumerGroup = gameConfig.getId() + "-cluster";
        String consumerName = gameConfig.getId() + "-instance-1";

        // 创建 Consumer Group (使用 xgroupCreateMkstream 方法)
        redisCommands.xgroupCreateMkstream(streamKey, consumerGroup, "0")
            .doOnError(e -> {
                if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                    logger.info("Consumer group already exists: {}", consumerGroup);
                } else {
                    logger.error("Failed to create consumer group: {}", e.getMessage());
                }
            })
            .subscribe();

        logger.info("Starting to consume upstream: {}", streamKey);

        consumerDisposable = Flux.interval(Duration.ofMillis(100))
            .publishOn(Schedulers.boundedElastic())
            .flatMap(tick -> redisCommands.xreadgroup(
                consumerGroup,
                consumerName,
                Map.of(streamKey, ">"),
                100,
                5000,
                false
            ))
            .flatMap(messages -> Flux.fromIterable(messages))
            .flatMap(this::processMessage)
            .doOnError(e -> logger.error("Error consuming upstream: {}", e.getMessage()))
            .subscribe();
    }

    private reactor.core.publisher.Mono<Long> processMessage(Map.Entry<String, Map<String, String>> entry) {
        String streamKey = entry.getKey();
        Map<String, String> fields = entry.getValue();

        try {
            String gateId = fields.get("gate_id");
            Long playerId = Long.parseLong(fields.get("player_id"));
            String msgType = fields.get("msg_type");
            String bodyStr = fields.get("body");

            logger.info("Received message from player {} via gate {}: {}", playerId, gateId, msgType);

            // 处理消息
            messageHandler.handleMessage(playerId, msgType, bodyStr);

            // ACK 消息
            String msgId = fields.get("id");
            return redisCommands.xack(streamKey, gameConfig.getId() + "-cluster", msgId);
        } catch (Exception e) {
            logger.error("Error processing message: {}", e.getMessage());
            return reactor.core.publisher.Mono.just(0L);
        }
    }

    @PreDestroy
    public void stopConsumer() {
        running = false;
        if (consumerDisposable != null) {
            consumerDisposable.dispose();
        }
        logger.info("Upstream consumer stopped");
    }
}