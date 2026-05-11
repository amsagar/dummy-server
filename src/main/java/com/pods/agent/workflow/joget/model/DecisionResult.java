/*
 * Vendored from Joget Community Edition (GPLv3).
 * Origin: jw-community/wflow-wfengine/src/main/java/org/joget/workflow/model/DecisionResult.java
 * Copyright (C) Joget Inc. Licensed under GNU General Public License v3.
 *
 * Repackaged for pods-ov-agent. No semantic changes.
 */
package com.pods.agent.workflow.joget.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class DecisionResult {

    protected Map<String, String> variables = new HashMap<>();
    protected Collection<String> transitions = null;
    protected Boolean isAndSplit = null;
    protected String auditData;

    public Boolean getIsAndSplit() {
        return isAndSplit;
    }

    public void setIsAndSplit(Boolean isAndSplit) {
        this.isAndSplit = isAndSplit;
    }

    public String getAuditData() {
        return auditData;
    }

    public void setAuditData(String auditData) {
        this.auditData = auditData;
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public void setVariable(String name, String value) {
        this.variables.put(name, value);
    }

    public Collection<String> getTransitions() {
        return transitions;
    }

    public void addTransition(String idOrName) {
        if (this.transitions == null) {
            this.transitions = new ArrayList<>();
        }
        this.transitions.add(idOrName);
    }
}
