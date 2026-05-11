/*
 * Vendored from Joget Community Edition (GPLv3).
 * Origin: jw-community/wflow-wfengine/src/main/java/org/joget/workflow/model/WorkflowParticipant.java
 * Copyright (C) Joget Inc. Licensed under GNU General Public License v3.
 *
 * Repackaged for pods-ov-agent. No semantic changes.
 */
package com.pods.agent.workflow.joget.model;

import java.io.Serializable;

public class WorkflowParticipant implements Serializable {

    private String id;
    private String name;
    private boolean packageLevel;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isPackageLevel() {
        return packageLevel;
    }

    public void setPackageLevel(boolean packageLevel) {
        this.packageLevel = packageLevel;
    }
}
