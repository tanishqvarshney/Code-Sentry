package org.codesentry.core.rule;

import org.codesentry.core.analysis.AnalysisContext;
import org.codesentry.core.model.Finding;

import java.util.List;

public interface Rule {
    String getId();
    String getName();
    String getDescription();
    List<Finding> analyze(AnalysisContext context);
}
