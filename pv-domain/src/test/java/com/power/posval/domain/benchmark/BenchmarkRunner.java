package com.power.posval.domain.benchmark;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;

/**
 * JMH benchmark runner — executes all or filtered benchmarks from the command line.
 *
 * <h3>Usage</h3>
 * <pre>
 * # Run ALL benchmarks:
 * mvn -pl pv-domain test-compile exec:exec@benchmarks
 *
 * # Run only FiveYearSettlementBenchmark:
 * mvn -pl pv-domain test-compile exec:exec@benchmarks -Dbenchmark=FiveYear
 *
 * # Run only PriceExpressionBenchmark:
 * mvn -pl pv-domain test-compile exec:exec@benchmarks -Dbenchmark=PriceExpression
 *
 * # Run only DomainBenchmarkSuite:
 * mvn -pl pv-domain test-compile exec:exec@benchmarks -Dbenchmark=DomainBenchmark
 * </pre>
 *
 * Results are printed to stdout and saved to {@code pv-domain/target/jmh-results.json}.
 *
 * <h3>Available benchmarks</h3>
 * <ul>
 *   <li>{@link FiveYearSettlementBenchmark} — 5-year wind PPA settlement (60 months, ~175k intervals)</li>
 *   <li>{@link PriceExpressionBenchmark} — collar PPA price expression tree walk</li>
 *   <li>{@link DomainBenchmarkSuite} — domain model micro-benchmarks (QualityState, DeliveryPeriod, etc.)</li>
 * </ul>
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        String filter = (args.length > 0 && args[0] != null && !args[0].isBlank()
                && !args[0].equals("${benchmark}"))
                ? ".*" + args[0] + ".*"
                : ".*";

        System.out.println("=== JMH Benchmark Runner ===");
        System.out.println("Filter: " + filter);
        System.out.println();

        // Ensure target directory exists for result output
        new File("target").mkdirs();

        String resultFile = "target/jmh-results.json";

        Options opts = new OptionsBuilder()
                .include(filter)
                .resultFormat(ResultFormatType.JSON)
                .result(resultFile)
                .shouldDoGC(true)
                .build();

        var results = new Runner(opts).run();

        System.out.println();
        System.out.println("=== Done: " + results.size() + " benchmark(s) completed ===");
        if (!results.isEmpty()) {
            System.out.println("JSON results saved to: " + new File(resultFile).getAbsolutePath());
        }
    }
}
