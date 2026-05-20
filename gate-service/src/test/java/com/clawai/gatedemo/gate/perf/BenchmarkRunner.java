package com.clawai.gatedemo.gate.perf;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * JMH 入口 wrapper：以 JUnit5 @Test 的方式触发所有 *Benchmark 类，结果统一写到
 * {@code target/jmh-result.json}（路径可通过 -Djmh.result.dir 覆盖）。
 *
 * <p>之所以不用 jmh-maven-plugin：
 * <ul>
 *   <li>避免新增 plugin 配置 + 单独 mvn 阶段（与现有 surefire 冲突 includes 难管）；</li>
 *   <li>统一用 mvn test -Pperf 触发，输出 surefire XML（便于聚合 pass/fail）+ JMH JSON；</li>
 *   <li>本地与 CI 行为一致，脚本可以只 cp target/jmh-result.json → reports/{ts}/perf/。</li>
 * </ul>
 *
 * <p>该类不是真正的"测试"，是 JMH 的 driver；命名 *BenchmarkRunner 而非 *Benchmark 是为了
 * 避免被 -Dtest 的 *Benchmark 模式重复匹配（surefire 在 perf profile 下用 includes
 * **&#47;*Benchmark.java，本类不在内）。
 */
public class BenchmarkRunner {

    /**
     * 触发 JMH。仅在显式 -Drun.jmh=true 时执行，避免普通 mvn test 时被误跑。
     * scripts/run-tests.sh perf 段会传该参数。
     */
    @Test
    public void runJmh() throws Exception {
        if (!Boolean.parseBoolean(System.getProperty("run.jmh", "false"))) {
            System.out.println("[JMH] skipped (run.jmh != true)");
            return;
        }

        String resultDir = System.getProperty("jmh.result.dir", "target");
        Path resultPath = Paths.get(resultDir, "jmh-result.json");
        Files.createDirectories(resultPath.getParent());

        // forks/iterations 可通过 system property 调小以加快本地反馈
        int warmup = Integer.parseInt(System.getProperty("jmh.warmup", "2"));
        int measure = Integer.parseInt(System.getProperty("jmh.measure", "3"));
        int forks = Integer.parseInt(System.getProperty("jmh.forks", "1"));

        // 注意：JMH include 正则匹配的是 "package.ClassName.methodName" 完整串，
        // 用 `.*Benchmark$` 会因 $ 锚结尾而匹配失败；这里用 `.*Benchmark\..*` 匹配
        // 所有以 *Benchmark 结尾的类下的所有方法。
        Options opt = new OptionsBuilder()
                .include(".*Benchmark\\..*")
                .warmupIterations(warmup)
                .measurementIterations(measure)
                .forks(forks)
                .resultFormat(ResultFormatType.JSON)
                .result(resultPath.toString())
                .shouldFailOnError(true)
                .build();

        new Runner(opt).run();

        File f = resultPath.toFile();
        if (!f.exists() || f.length() == 0) {
            throw new IllegalStateException("JMH result file missing or empty: " + f);
        }
        System.out.println("[JMH] result written to " + f.getAbsolutePath()
                + " (" + f.length() + " bytes)");
    }
}
