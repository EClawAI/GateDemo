package com.clawai.gatedemo.gate.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * gRPC消息序列化性能测试
 * 
 * 测试JSON String vs Binary (ByteString) 的性能差异
 * 
 * 运行方式：
 * mvn jmh:jmh
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class GrpcSerializationBenchmark {

    private ObjectMapper objectMapper;
    private Map<String, Object> testData;
    private String jsonString;
    
    @Setup
    public void prepare() {
        objectMapper = new ObjectMapper();
        
        // 构建测试数据
        testData = new HashMap<>();
        testData.put("playerId", 12345L);
        testData.put("action", "move");
        testData.put("x", 100);
        testData.put("y", 200);
        testData.put("timestamp", System.currentTimeMillis());
        
        // 预先生成JSON字符串
        try {
            jsonString = objectMapper.writeValueAsString(testData);
        } catch (Exception e) {
            jsonString = "{}";
        }
    }
    
    /**
     * 测试JSON字符串序列化性能
     */
    @Benchmark
    public void jsonStringSerialization(Blackhole blackhole) throws Exception {
        // String -> byte[]
        byte[] bytes = jsonString.getBytes("UTF-8");
        blackhole.consume(bytes);
    }
    
    /**
     * 测试ByteString序列化性能
     */
    @Benchmark
    public void byteStringSerialization(Blackhole blackhole) {
        // String -> ByteString
        ByteString byteString = ByteString.copyFromUtf8(jsonString);
        blackhole.consume(byteString);
    }
    
    /**
     * 测试ByteString反序列化性能
     */
    @Benchmark
    public void byteStringDeserialization(Blackhole blackhole) {
        ByteString byteString = ByteString.copyFromUtf8(jsonString);
        // ByteString -> String
        String result = byteString.toStringUtf8();
        blackhole.consume(result);
    }
    
    /**
     * 测试JSON字符串转Map性能
     */
    @Benchmark
    public void jsonToMap(Blackhole blackhole) throws Exception {
        Map<String, Object> result = objectMapper.readValue(jsonString, Map.class);
        blackhole.consume(result);
    }
    
    /**
     * 测试完整流程：Object -> JSON -> ByteString
     */
    @Benchmark
    public void fullSerialization(Blackhole blackhole) throws Exception {
        String json = objectMapper.writeValueAsString(testData);
        ByteString byteString = ByteString.copyFromUtf8(json);
        blackhole.consume(byteString);
    }
    
    /**
     * 测试完整流程：ByteString -> String -> Map
     */
    @Benchmark
    public void fullDeserialization(Blackhole blackhole) throws Exception {
        ByteString byteString = ByteString.copyFromUtf8(jsonString);
        String json = byteString.toStringUtf8();
        Map<String, Object> result = objectMapper.readValue(json, Map.class);
        blackhole.consume(result);
    }
    
    public static void main(String[] args) throws Exception {
        GrpcSerializationBenchmark benchmark = new GrpcSerializationBenchmark();
        benchmark.prepare();
        
        System.out.println("=== gRPC消息序列化性能测试 ===");
        System.out.println("JSON String长度: " + benchmark.jsonString.length() + " bytes");
        System.out.println("ByteString长度: " + ByteString.copyFromUtf8(benchmark.jsonString).size() + " bytes");
        System.out.println();
        
        // 简单对比测试
        long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            ByteString bs = ByteString.copyFromUtf8(benchmark.jsonString);
        }
        long end = System.nanoTime();
        System.out.println("ByteString创建10000次: " + (end - start) / 1000000 + "ms");
        
        start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            benchmark.jsonString.getBytes("UTF-8");
        }
        end = System.nanoTime();
        System.out.println("String.getBytes 10000次: " + (end - start) / 1000000 + "ms");
    }
}
