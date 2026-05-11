/*
 * Vendored from Joget Community Edition (GPLv3).
 * Origin: jw-community/wflow-wfengine/src/main/java/org/joget/workflow/model/WorkflowProcess.java
 * Copyright (C) Joget Inc. Licensed under GNU General Public License v3.
 *
 * Repackaged for pods-ov-agent. Stripped: WorkflowUtil-based label translation
 * (getName/getIdWithoutVersion) and ApplicationContext-based lazy loading of
 * the variable list. The agent populates these eagerly.
 */
package com.pods.agent.workflow.joget.model;

import java.io.Serializable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;

public class WorkflowProcess implements Serializable {

    private String recordId;
    private String id;
    private String instanceId;
    private String packageId;
    private String packageName;
    private String name;
    private String version;
    private String description;
    private String category;
    private Date createdTime;
    private Date startedTime;
    private String limit;
    private Date due;
    private String delay;
    private long delayInSeconds;
    private Date finishTime;
    private String timeConsumingFromDateCreated;
    private long timeConsumingFromDateCreatedInSeconds;
    private String timeConsumingFromDateStarted;
    private long timeConsumingFromDateStartedInSeconds;
    private String state;
    private String requesterId;
    private boolean latest;
    private Collection<WorkflowVariable> variableList;

    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }

    public String getEncodedId() {
        if (id == null) {
            return null;
        }
        try {
            return URLEncoder.encode(id, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return id;
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getPackageId() { return packageId; }
    public void setPackageId(String packageId) { this.packageId = packageId; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public boolean isLatest() { return latest; }
    public void setLatest(boolean latest) { this.latest = latest; }

    public Date getStartedTime() { return startedTime; }
    public void setStartedTime(Date startedTime) { this.startedTime = startedTime; }

    public Date getFinishTime() { return finishTime; }
    public void setFinishTime(Date finishTime) { this.finishTime = finishTime; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getLimit() { return limit; }
    public void setLimit(String limit) { this.limit = limit; }

    public Date getDue() { return due; }
    public void setDue(Date due) { this.due = due; }

    public String getDelay() { return delay; }
    public void setDelay(String delay) { this.delay = delay; }

    public Date getCreatedTime() { return createdTime; }
    public void setCreatedTime(Date createdTime) { this.createdTime = createdTime; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getTimeConsumingFromDateCreated() { return timeConsumingFromDateCreated; }
    public void setTimeConsumingFromDateCreated(String s) { this.timeConsumingFromDateCreated = s; }

    public String getTimeConsumingFromDateStarted() { return timeConsumingFromDateStarted; }
    public void setTimeConsumingFromDateStarted(String s) { this.timeConsumingFromDateStarted = s; }

    public String getRequesterId() { return requesterId; }
    public void setRequesterId(String requesterId) { this.requesterId = requesterId; }

    public long getDelayInSeconds() { return delayInSeconds; }
    public void setDelayInSeconds(long v) { this.delayInSeconds = v; }

    public long getTimeConsumingFromDateCreatedInSeconds() { return timeConsumingFromDateCreatedInSeconds; }
    public void setTimeConsumingFromDateCreatedInSeconds(long v) { this.timeConsumingFromDateCreatedInSeconds = v; }

    public long getTimeConsumingFromDateStartedInSeconds() { return timeConsumingFromDateStartedInSeconds; }
    public void setTimeConsumingFromDateStartedInSeconds(long v) { this.timeConsumingFromDateStartedInSeconds = v; }

    public Collection<WorkflowVariable> getVariableList() { return variableList; }
    public void setVariableList(Collection<WorkflowVariable> variableList) { this.variableList = variableList; }
}
