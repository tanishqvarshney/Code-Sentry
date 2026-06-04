package org.codesentry.benchmark;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.codesentry.core.analysis.AnalysisContext;
import org.codesentry.core.model.Finding;
import org.codesentry.core.rule.*;

import java.util.*;

public class BenchmarkHarness {

    private static class BenchmarkCase {
        final String name;
        final String code;
        final String ruleId;
        final int expectedBugs;

        BenchmarkCase(String name, String code, String ruleId, int expectedBugs) {
            this.name = name;
            this.code = code;
            this.ruleId = ruleId;
            this.expectedBugs = expectedBugs;
        }
    }

    private final List<BenchmarkCase> testSuite = new ArrayList<>();
    private final List<Rule> rules = List.of(
            new ResourceLeakRule(),
            new NullSafetyRule(),
            new ConcurrencyRule(),
            new NPlusOneQueryRule()
    );

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("       CodeSentry Static Analyzer Benchmark        ");
        System.out.println("==================================================");

        BenchmarkHarness harness = new BenchmarkHarness();
        harness.setupSuite();
        harness.runBenchmark();
    }

    private void setupSuite() {
        // 1. Resource Leak cases
        testSuite.add(new BenchmarkCase("ResourceLeak_Buggy",
                "public class A { public void run() throws Exception { java.io.FileInputStream f = new java.io.FileInputStream(\"a\"); f.read(); } }",
                "RULE-001-RESOURCE-LEAK", 1));
        testSuite.add(new BenchmarkCase("ResourceLeak_Safe",
                "public class B { public void run() throws Exception { try(java.io.FileInputStream f = new java.io.FileInputStream(\"a\")) { f.read(); } } }",
                "RULE-001-RESOURCE-LEAK", 0));

        // 2. Null Safety cases
        testSuite.add(new BenchmarkCase("NullSafety_Buggy",
                "public class C { public void run() { String s = null; s.trim(); } }",
                "RULE-002-NULL-SAFETY", 1));
        testSuite.add(new BenchmarkCase("NullSafety_Safe",
                "public class D { public void run() { String s = null; if (s != null) { s.trim(); } } }",
                "RULE-002-NULL-SAFETY", 0));

        // 3. Concurrency cases
        testSuite.add(new BenchmarkCase("Concurrency_Buggy",
                "@org.springframework.web.bind.annotation.RestController\n" +
                        "public class E { private int val; public void inc() { val++; } }",
                "RULE-003-CONCURRENCY", 1));
        testSuite.add(new BenchmarkCase("Concurrency_Safe",
                "@org.springframework.web.bind.annotation.RestController\n" +
                        "public class F { private volatile int val; public synchronized void inc() { val++; } }",
                "RULE-003-CONCURRENCY", 0));

        // 4. N+1 Queries cases
        testSuite.add(new BenchmarkCase("NPlusOne_Buggy",
                "public class G { private Repo repository; public void run(java.util.List<String> ids) { for(String id : ids) { repository.findById(id); } } }",
                "RULE-004-NPLUSONE-QUERY", 1));
        testSuite.add(new BenchmarkCase("NPlusOne_Safe",
                "public class H { private Repo repository; public void run(java.util.List<String> ids) { repository.findAllById(ids); } }",
                "RULE-004-NPLUSONE-QUERY", 0));
    }

    private void runBenchmark() {
        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        int trueNegatives = 0;

        long totalParseTime = 0;
        long totalAnalysisTime = 0;

        int totalFilesAnalyzed = testSuite.size();

        System.out.println("Running analysis on " + totalFilesAnalyzed + " benchmark files...");

        for (BenchmarkCase tc : testSuite) {
            long pStart = System.nanoTime();
            CompilationUnit cu = StaticJavaParser.parse(tc.code);
            totalParseTime += (System.nanoTime() - pStart);

            AnalysisContext context = AnalysisContext.builder()
                    .filePaths(List.of(tc.name + ".java"))
                    .parsedAsts(Map.of(tc.name + ".java", cu))
                    .changedLines(null)
                    .build();

            long aStart = System.nanoTime();
            List<Finding> findings = new ArrayList<>();
            for (Rule rule : rules) {
                if (rule.getId().equals(tc.ruleId)) {
                    findings.addAll(rule.analyze(context));
                }
            }
            totalAnalysisTime += (System.nanoTime() - aStart);

            int actualBugsFound = findings.size();
            int expectedBugs = tc.expectedBugs;

            if (expectedBugs > 0) {
                if (actualBugsFound > 0) {
                    truePositives += Math.min(expectedBugs, actualBugsFound);
                    if (actualBugsFound > expectedBugs) {
                        falsePositives += (actualBugsFound - expectedBugs);
                    }
                } else {
                    falseNegatives += expectedBugs;
                }
            } else {
                if (actualBugsFound > 0) {
                    falsePositives += actualBugsFound;
                } else {
                    trueNegatives++;
                }
            }
        }

        double parseTimeMs = totalParseTime / 1_000_000.0;
        double analysisTimeMs = totalAnalysisTime / 1_000_000.0;
        double totalTimeMs = parseTimeMs + analysisTimeMs;

        double throughput = totalFilesAnalyzed / (totalTimeMs / 1000.0);

        double precision = (truePositives + falsePositives) == 0 ? 100.0 : (truePositives * 100.0 / (truePositives + falsePositives));
        double recall = (truePositives + falseNegatives) == 0 ? 100.0 : (truePositives * 100.0 / (truePositives + falseNegatives));
        double f1Score = (precision + recall) == 0 ? 0.0 : (2 * precision * recall / (precision + recall));

        System.out.println("\n---------------- BENCHMARK METRICS ----------------");
        System.out.printf("Total Files Analyzed   : %d\n", totalFilesAnalyzed);
        System.out.printf("Warmup & Run Latency   : %.2f ms (Parse: %.2f ms, Analyze: %.2f ms)\n", totalTimeMs, parseTimeMs, analysisTimeMs);
        System.out.printf("Average Latency/File   : %.2f ms\n", (totalTimeMs / totalFilesAnalyzed));
        System.out.printf("Analysis Throughput    : %.2f files/sec\n", throughput);
        System.out.println("----------------- ACCURACY METRICS -----------------");
        System.out.printf("True Positives (TP)    : %d\n", truePositives);
        System.out.printf("True Negatives (TN)    : %d\n", trueNegatives);
        System.out.printf("False Positives (FP)   : %d\n", falsePositives);
        System.out.printf("False Negatives (FN)   : %d\n", falseNegatives);
        System.out.printf("Analyzer Precision     : %.2f%%\n", precision);
        System.out.printf("Analyzer Recall        : %.2f%%\n", recall);
        System.out.printf("Analyzer F1 Score      : %.2f%%\n", f1Score);
        System.out.println("==================================================\n");
    }
}
