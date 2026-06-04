package org.codesentry.core.rule;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.codesentry.core.analysis.AnalysisContext;
import org.codesentry.core.model.Finding;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class RuleIntegrationTest {

    @Test
    public void testResourceLeakRule() {
        String code = "package test;\n" +
                "import java.io.FileInputStream;\n" +
                "import java.io.IOException;\n" +
                "public class ResourceTest {\n" +
                "    public void buggyLeak() throws IOException {\n" +
                "        FileInputStream fis = new FileInputStream(\"test.txt\");\n" +
                "        int data = fis.read();\n" +
                "        System.out.println(data);\n" +
                "    }\n" +
                "    public void safeTryWithResources() throws IOException {\n" +
                "        try (FileInputStream fis = new FileInputStream(\"test.txt\")) {\n" +
                "            int data = fis.read();\n" +
                "            System.out.println(data);\n" +
                "        }\n" +
                "    }\n" +
                "    public void safeExplicitClose() throws IOException {\n" +
                "        FileInputStream fis = null;\n" +
                "        try {\n" +
                "            fis = new FileInputStream(\"test.txt\");\n" +
                "            int data = fis.read();\n" +
                "            System.out.println(data);\n" +
                "        } finally {\n" +
                "            if (fis != null) fis.close();\n" +
                "        }\n" +
                "    }\n" +
                "}\n";

        CompilationUnit cu = StaticJavaParser.parse(code);
        AnalysisContext context = AnalysisContext.builder()
                .filePaths(List.of("ResourceTest.java"))
                .parsedAsts(Map.of("ResourceTest.java", cu))
                .changedLines(null) // all lines relevant
                .build();

        ResourceLeakRule rule = new ResourceLeakRule();
        List<Finding> findings = rule.analyze(context);

        // Should find exactly 1 resource leak in buggyLeak() at the line where fis is defined
        assertEquals(1, findings.size(), "Should detect exactly one resource leak");
        Finding leak = findings.get(0);
        assertEquals("RULE-001-RESOURCE-LEAK", leak.getRuleId());
        assertTrue(leak.getMessage().contains("fis"));
    }

    @Test
    public void testNullSafetyRule() {
        String code = "package test;\n" +
                "public class NullTest {\n" +
                "    public void buggyNullDereference() {\n" +
                "        String str = null;\n" +
                "        System.out.println(str.length());\n" +
                "    }\n" +
                "    public void safeNullCheck() {\n" +
                "        String str = null;\n" +
                "        if (str != null) {\n" +
                "            System.out.println(str.length());\n" +
                "        }\n" +
                "    }\n" +
                "}\n";

        CompilationUnit cu = StaticJavaParser.parse(code);
        AnalysisContext context = AnalysisContext.builder()
                .filePaths(List.of("NullTest.java"))
                .parsedAsts(Map.of("NullTest.java", cu))
                .changedLines(null)
                .build();

        NullSafetyRule rule = new NullSafetyRule();
        List<Finding> findings = rule.analyze(context);

        // Should find exactly 1 null dereference in buggyNullDereference()
        assertEquals(1, findings.size(), "Should detect exactly one null dereference");
        Finding finding = findings.get(0);
        assertEquals("RULE-002-NULL-SAFETY", finding.getRuleId());
        assertTrue(finding.getMessage().contains("str"));
    }

    @Test
    public void testConcurrencyRule() {
        String code = "package test;\n" +
                "import org.springframework.web.bind.annotation.RestController;\n" +
                "import java.util.HashMap;\n" +
                "import java.util.Map;\n" +
                "@RestController\n" +
                "public class ConcurrencyTest {\n" +
                "    private int sharedCounter = 0; // mutable field\n" +
                "    private Map<String, String> map = new HashMap<>();\n" +
                "    public void increment() {\n" +
                "        sharedCounter++; // written without sync\n" +
                "    }\n" +
                "    public void checkThenAct(String key, String val) {\n" +
                "        if (!map.containsKey(key)) {\n" +
                "            map.put(key, val); // non-atomic check-then-act\n" +
                "        }\n" +
                "    }\n" +
                "    public Map<String, String> getMap() {\n" +
                "        return map; // unsafe publication\n" +
                "    }\n" +
                "}\n";

        CompilationUnit cu = StaticJavaParser.parse(code);
        AnalysisContext context = AnalysisContext.builder()
                .filePaths(List.of("ConcurrencyTest.java"))
                .parsedAsts(Map.of("ConcurrencyTest.java", cu))
                .changedLines(null)
                .build();

        ConcurrencyRule rule = new ConcurrencyRule();
        List<Finding> findings = rule.analyze(context);

        // Should detect all 3 concurrent issues
        assertTrue(findings.size() >= 3, "Should detect at least 3 concurrency issues");
        boolean hasStateIssue = false;
        boolean hasCheckThenAct = false;
        boolean hasUnsafePublication = false;

        for (Finding f : findings) {
            if (f.getMessage().contains("sharedCounter")) hasStateIssue = true;
            if (f.getMessage().contains("check-then-act")) hasCheckThenAct = true;
            if (f.getMessage().contains("Unsafe publication")) hasUnsafePublication = true;
        }

        assertTrue(hasStateIssue, "Missing shared mutable field issue");
        assertTrue(hasCheckThenAct, "Missing check-then-act issue");
        assertTrue(hasUnsafePublication, "Missing unsafe publication issue");
    }

    @Test
    public void testNPlusOneQueryRule() {
        String code = "package test;\n" +
                "import java.util.List;\n" +
                "public class QueryTest {\n" +
                "    private UserRepository userRepository;\n" +
                "    public void buggyQueryInLoop(List<String> ids) {\n" +
                "        for (String id : ids) {\n" +
                "            userRepository.findById(id);\n" +
                "        }\n" +
                "    }\n" +
                "    public void buggyQueryInStream(List<String> ids) {\n" +
                "        ids.forEach(id -> userRepository.findById(id));\n" +
                "    }\n" +
                "}\n";

        CompilationUnit cu = StaticJavaParser.parse(code);
        AnalysisContext context = AnalysisContext.builder()
                .filePaths(List.of("QueryTest.java"))
                .parsedAsts(Map.of("QueryTest.java", cu))
                .changedLines(null)
                .build();

        NPlusOneQueryRule rule = new NPlusOneQueryRule();
        List<Finding> findings = rule.analyze(context);

        // Should find exactly 2 queries inside loops/streams
        assertEquals(2, findings.size(), "Should detect two N+1 queries");
        assertTrue(findings.get(0).getMessage().contains("loop"));
        assertTrue(findings.get(1).getMessage().contains("stream"));
    }

    @Test
    public void testSqlInjectionRule() {
        String code = "package test;\n" +
                "import java.sql.Statement;\n" +
                "public class SqlTest {\n" +
                "    public void buggyQuery(Statement stmt, String name) throws Exception {\n" +
                "        stmt.executeQuery(\"SELECT * FROM users WHERE name = '\" + name + \"'\");\n" +
                "    }\n" +
                "}\n";

        CompilationUnit cu = StaticJavaParser.parse(code);
        AnalysisContext context = AnalysisContext.builder()
                .filePaths(List.of("SqlTest.java"))
                .parsedAsts(Map.of("SqlTest.java", cu))
                .build();

        SqlInjectionRule rule = new SqlInjectionRule();
        List<Finding> findings = rule.analyze(context);

        assertEquals(1, findings.size());
        assertEquals("RULE-005-SQL-INJECTION", findings.get(0).getRuleId());
    }

    @Test
    public void testSecretDetectionRule() {
        String code = "package test;\n" +
                "public class SecretTest {\n" +
                "    private static final String API_KEY = \"my-super-secret-key-12345\";\n" +
                "    public void method() {\n" +
                "        String password = \"hardcoded-pass\";\n" +
                "    }\n" +
                "}\n";

        CompilationUnit cu = StaticJavaParser.parse(code);
        AnalysisContext context = AnalysisContext.builder()
                .filePaths(List.of("SecretTest.java"))
                .parsedAsts(Map.of("SecretTest.java", cu))
                .build();

        SecretDetectionRule rule = new SecretDetectionRule();
        List<Finding> findings = rule.analyze(context);

        assertEquals(2, findings.size());
        assertEquals("RULE-006-HARDCODED-SECRET", findings.get(0).getRuleId());
    }

    @Test
    public void testLoopBoundRule() {
        String code = "package test;\n" +
                "import java.util.List;\n" +
                "public class LoopTest {\n" +
                "    public void buggyLoop(List<String> list) {\n" +
                "        for (int i = 0; i <= list.size(); i++) {\n" +
                "            System.out.println(list.get(i));\n" +
                "        }\n" +
                "    }\n" +
                "}\n";

        CompilationUnit cu = StaticJavaParser.parse(code);
        AnalysisContext context = AnalysisContext.builder()
                .filePaths(List.of("LoopTest.java"))
                .parsedAsts(Map.of("LoopTest.java", cu))
                .build();

        LoopBoundRule rule = new LoopBoundRule();
        List<Finding> findings = rule.analyze(context);

        assertEquals(1, findings.size());
        assertEquals("RULE-007-LOOP-BOUND", findings.get(0).getRuleId());
    }
}
