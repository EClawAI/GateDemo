package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.model.PlayerMessage;
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
public class StreamConsumerService {

    private static final Logger logger = LoggerFactory.getLogger(StreamConsumerService.class);

    private final GateConfig gateConfig;
    private final RedisReactiveCommands<String, String> redisCommands;
    private final PlayerService playerService;
    private final ObjectMapper objectMapper;

    private Disposable consumerDisposable;
    private volatile boolean running = true;

    public StreamConsumerService(GateConfig gateConfig, RedisReactiveCommands<String, String> redisCommands,
                                  PlayerService playerService, ObjectMapper objectMapper) {
        this.gateConfig = gateConfig;
        this.redisCommands = redisCommands;
        this.playerService = playerService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void startConsumer() {
        String streamKey = "stream:down:gate:" + gateConfig.getId();
        String consumerGroup = gateConfig.getId() + "-cluster";
        String consumerName = gateConfig.getId() + "-instance-1";

        // 创建 Consumer Group
        redisCommands.xgroupCreate(streamKey, consumerGroup, "0", true)
            .doOnError(e -> {
                if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                    logger.info("Consumer group already exists: {}", consumerGroup);
                } else {
                    logger.error("Failed to create consumer group: {}", e.getMessage());
                }
            })
            .subscribe();

        // 启动消费者
        logger.info("Starting to consume downstream: {}", streamKey);

        consumerDisposable = Flux.interval(Duration.ofMillis(100))
            .publishOn(Schedulers.boundedElastic())
            .flatMap(tick -> redisCommands.xreadgroup(
                consumerGroup,
                consumerName,
                Map.of(streamKey, ">"),
                gateConfig.getRedis().getStream().getCount(),
                gateConfig.getRedis().getStream().getBlockMs(),
                false
            ))
            .flatMap(messages -> Flux.fromIterable(messages))
            .flatMap(this::processMessages)
            .doOnError(e -> logger.error("Error consuming stream: {}", e.getMessage()))
            .subscribe();
    }

    private reactor.core.publisher.Mono<Long> processMessages(Map.Entry<String, Map<String, String>> entry) {
        String streamKey = entry.getKey();
        Map<String, String> fields = entry.getValue();
        
        return processDownstreamMessage(streamKey, fields)
            .onErrorResume(e -> {
                logger.error("Error processing message: {}", e.getMessage());
                return reactor.core.publisher.Mono.just(0L);
            });
    }

    private reactor.core.publisher.Mono<Long> processDownstreamMessage(String streamKey, Map<String, String> fields) {
        try {
            Long playerId = Long.parseLong(fields.get("player_id"));
            String msgType = fields.get("msg_type");
            String bodyStr = fields.get("body");
            Long seq = Long.parseLong(fields.get("seq"));
            Long timestamp = Long.parseLong(fields.get("timestamp"));

            Map<String, Object> body = objectMapper.readValue(bodyStr, Map.class);

            PlayerMessage message = new PlayerMessage();
            message.setMsgType(msgType);
            message.setSeq(seq);
            message.setBody(body);
            message.setTimestamp(timestamp);

            boolean success = playerService.sendToPlayer(playerId, message);

            if (success) {
                String msgId = fields.get("id");
                return redisCommands.xack(streamKey, gateConfig.getId() + "-cluster", msgId);
            } else {
                logger.warn("Message not acked for player {}", playerId);
                return reactor.core.publisher.Mono.just(0L);
            }
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
        logger.info("Stream consumer stopped");
    }
}