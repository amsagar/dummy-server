/*
 * Vendored from Joget Community Edition (GPLv3).
 * Origin: jw-community/wflow-wfengine/src/main/java/org/joget/workflow/model/WorkflowProcessLink.java
 * Copyright (C) Joget Inc. Licensed under GNU General Public License v3.
 *
 * Repackaged for pods-ov-agent. No semantic changes.
 */
package com.pods.agent.workflow.joget.model;

import java.io.Serializable;

public class WorkflowProcessLink implements Serializable {

    private String processId;
    private String parentProcessId;
    private String originProcessId;

    public String getOriginProcessId() {
        return originProcessId;
    }

    public void setOriginProcessId(String originProcessId) {
        this.originProcessId = originProcessId;
    }

    public String getParentProcessId() {
        return parentProcessId;
    }

    public void setParentProcessId(String parentProcessId) {
        this.parentProcessId = parentProcessId;
    }

    public String getProcessId() {
        return processId;
    }

    public void setProcessId(String processId) {
        this.processId = processId;
    }
}
