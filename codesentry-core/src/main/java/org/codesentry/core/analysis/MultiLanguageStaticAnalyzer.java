package org.codesentry.core.analysis;

import org.codesentry.core.model.Finding;
import org.codesentry.core.model.Finding.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MultiLanguageStaticAnalyzer {

    public static List<Finding> analyze(String filePath, String content) {
        List<Finding> findings = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return findings;
        }

        String ext = getFileExtension(filePath).toLowerCase();
        String[] lines = content.split("\r?\n");

        switch (ext) {
            case "cpp":
            case "h":
            case "hpp":
            case "cc":
            case "c":
                analyzeCpp(filePath, lines, findings);
                break;
            case "py":
                analyzePython(filePath, lines, findings);
                break;
            case "js":
            case "ts":
            case "jsx":
            case "tsx":
                analyzeJsTs(filePath, lines, findings);
                break;
            default:
                analyzeGeneric(filePath, lines, findings);
                break;
        }

        return findings;
    }

    private static String getFileExtension(String filePath) {
        int dot = filePath.lastIndexOf('.');
        return dot > 0 ? filePath.substring(dot + 1) : "";
    }

    private static String stripComments(String line, String ext) {
        if (ext.equals("py")) {
            int hashIdx = line.indexOf('#');
            if (hashIdx >= 0) {
                return line.substring(0, hashIdx);
            }
        } else {
            int dslashIdx = line.indexOf("//");
            if (dslashIdx >= 0) {
                return line.substring(0, dslashIdx);
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                return "";
            }
        }
        return line;
    }

    private static void analyzeCpp(String file, String[] lines, List<Finding> findings) {
        // C/C++ specific rules
        Pattern openPattern = Pattern.compile("\\b(fopen|open|malloc|new)\\b");
        Pattern closePattern = Pattern.compile("\\b(fclose|close|free|delete)\\b");
        boolean hasAlloc = false;
        int allocLine = 0;
        String allocSnippet = "";

        Pattern nullAssign = Pattern.compile("(\\w+)\\s*=\\s*(nullptr|NULL|0)\\s*;");
        List<String> nullVars = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String lineContent = stripComments(rawLine, "cpp");
            int lineNum = i + 1;

            if (lineContent.trim().isEmpty()) {
                continue;
            }

            // Check allocation
            if (openPattern.matcher(lineContent).find() && !lineContent.contains("close") && !lineContent.contains("free")) {
                hasAlloc = true;
                allocLine = lineNum;
                allocSnippet = rawLine.trim();
            }
            if (closePattern.matcher(lineContent).find()) {
                hasAlloc = false;
            }

            // Check null assign
            Matcher mNull = nullAssign.matcher(lineContent);
            if (mNull.find()) {
                nullVars.add(mNull.group(1));
            }

            // Check null dereference on assigned null variables
            for (String var : nullVars) {
                if (lineContent.contains(var + "->") || lineContent.contains("*" + var)) {
                    if (!lineContent.contains("if") && !lineContent.contains("!") && !lineContent.contains("==")) {
                        findings.add(Finding.builder()
                                .ruleId("RULE-002-NULL-SAFETY")
                                .severity(Severity.ERROR)
                                .file(file)
                                .line(lineNum)
                                .codeSnippet(rawLine.trim())
                                .message("Potential null pointer dereference of '" + var + "' without a guard check.")
                                .build());
                    }
                }
            }

            // Database/HTTP Query in loops
            if (isInsideLoop(lines, i)) {
                if (lineContent.contains("query(") || lineContent.contains("execute(") || lineContent.contains("select(")) {
                    findings.add(Finding.builder()
                            .ruleId("RULE-004-NPLUSONE-QUERY")
                            .severity(Severity.WARNING)
                            .file(file)
                            .line(lineNum)
                            .codeSnippet(rawLine.trim())
                            .message("Database query executed inside a loop. Potential N+1 performance bottleneck.")
                            .build());
                }
            }
        }

        if (hasAlloc) {
            findings.add(Finding.builder()
                    .ruleId("RULE-001-RESOURCE-LEAK")
                    .severity(Severity.ERROR)
                    .file(file)
                    .line(allocLine)
                    .codeSnippet(allocSnippet)
                    .message("Resource allocated but not closed/released on all execution paths.")
                    .build());
        }
    }

    private static void analyzePython(String file, String[] lines, List<Finding> findings) {
        Pattern openPattern = Pattern.compile("open\\(");
        Pattern withPattern = Pattern.compile("with\\s+open\\(");
        boolean inWith = false;
        boolean hasOpen = false;
        int openLine = 0;
        String openSnippet = "";

        Pattern NoneAssign = Pattern.compile("(\\w+)\\s*=\\s*None");
        List<String> noneVars = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String lineContent = stripComments(rawLine, "py");
            int lineNum = i + 1;

            if (lineContent.trim().isEmpty()) {
                continue;
            }

            if (withPattern.matcher(lineContent).find()) {
                inWith = true;
            }
            if (openPattern.matcher(lineContent).find() && !inWith && !lineContent.contains(".close()")) {
                hasOpen = true;
                openLine = lineNum;
                openSnippet = rawLine.trim();
            }
            if (lineContent.contains(".close()")) {
                hasOpen = false;
            }

            // Null safety
            Matcher mNone = NoneAssign.matcher(lineContent);
            if (mNone.find()) {
                noneVars.add(mNone.group(1));
            }

            for (String var : noneVars) {
                if (lineContent.contains(var + ".") || lineContent.contains(var + "[")) {
                    if (!lineContent.contains("if") && !lineContent.contains("is not None") && !lineContent.contains("not " + var)) {
                        findings.add(Finding.builder()
                                .ruleId("RULE-002-NULL-SAFETY")
                                .severity(Severity.ERROR)
                                .file(file)
                                .line(lineNum)
                                .codeSnippet(rawLine.trim())
                                .message("Accessing member of object '" + var + "' that may be None.")
                                .build());
                    }
                }
            }

            // Loop queries
            if (isInsideLoop(lines, i)) {
                if (lineContent.contains("execute(") || lineContent.contains("query(") || lineContent.contains("db.")) {
                    findings.add(Finding.builder()
                            .ruleId("RULE-004-NPLUSONE-QUERY")
                            .severity(Severity.WARNING)
                            .file(file)
                            .line(lineNum)
                            .codeSnippet(rawLine.trim())
                            .message("Database query/operation executed inside a loop. Consider batching.")
                            .build());
                }
            }
        }

        if (hasOpen && !inWith) {
            findings.add(Finding.builder()
                    .ruleId("RULE-001-RESOURCE-LEAK")
                    .severity(Severity.ERROR)
                    .file(file)
                    .line(openLine)
                    .codeSnippet(openSnippet)
                    .message("File resource opened but not closed. Use 'with' statement for safe cleanup.")
                    .build());
        }
    }

    private static void analyzeJsTs(String file, String[] lines, List<Finding> findings) {
        Pattern nullAssign = Pattern.compile("(const|let|var)?\\s*(\\w+)\\s*=\\s*(null|undefined)\\s*;?");
        List<String> nullVars = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String lineContent = stripComments(rawLine, "js");
            int lineNum = i + 1;

            if (lineContent.trim().isEmpty()) {
                continue;
            }

            Matcher mNull = nullAssign.matcher(lineContent);
            if (mNull.find()) {
                nullVars.add(mNull.group(2));
            }

            for (String var : nullVars) {
                if (lineContent.contains(var + ".") && !lineContent.contains(var + "?.") && !lineContent.contains("typeof")) {
                    if (!lineContent.contains("if") && !lineContent.contains("&&") && !lineContent.contains("?")) {
                        findings.add(Finding.builder()
                                .ruleId("RULE-002-NULL-SAFETY")
                                .severity(Severity.ERROR)
                                .file(file)
                                .line(lineNum)
                                .codeSnippet(rawLine.trim())
                                .message("Dereferencing '" + var + "' which is explicitly set to null/undefined without optional chaining.")
                                .build());
                    }
                }
            }

            // DB/HTTP calls inside loops
            if (isInsideLoop(lines, i)) {
                if (lineContent.contains("await ") || lineContent.contains("fetch(") || lineContent.contains("db.") || lineContent.contains("query(")) {
                    findings.add(Finding.builder()
                            .ruleId("RULE-004-NPLUSONE-QUERY")
                            .severity(Severity.WARNING)
                            .file(file)
                            .line(lineNum)
                            .codeSnippet(rawLine.trim())
                            .message("Asynchronous network/database operation executed inside a loop. Potential N+1 query issue.")
                            .build());
                }
            }
        }
    }

    private static void analyzeGeneric(String file, String[] lines, List<Finding> findings) {
        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String lineContent = stripComments(rawLine, "generic");
            int lineNum = i + 1;

            if (lineContent.trim().isEmpty()) {
                continue;
            }

            // Simple loop checks for general script/source files
            if (isInsideLoop(lines, i)) {
                if (lineContent.contains("query") || lineContent.contains("sql") || lineContent.contains("fetch(")) {
                    findings.add(Finding.builder()
                            .ruleId("RULE-004-NPLUSONE-QUERY")
                            .severity(Severity.WARNING)
                            .file(file)
                            .line(lineNum)
                            .codeSnippet(rawLine.trim())
                            .message("Potential database call or HTTP request in loop.")
                            .build());
                }
            }
        }
    }

    private static boolean isInsideLoop(String[] lines, int currentIndex) {
        for (int j = Math.max(0, currentIndex - 10); j < currentIndex; j++) {
            String line = lines[j].trim();
            if (line.startsWith("for ") || line.startsWith("for(") || line.startsWith("while ") || line.startsWith("while(") || line.contains(".forEach") || line.contains(".map(")) {
                return true;
            }
        }
        return false;
    }
}
