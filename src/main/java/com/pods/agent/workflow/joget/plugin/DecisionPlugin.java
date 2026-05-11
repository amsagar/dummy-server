/*
 * Vendored from Joget Community Edition (GPLv3).
 * Origin: jw-community/wflow-wfengine/src/main/java/org/joget/workflow/model/DecisionPlugin.java
 * Copyright (C) Joget Inc. Licensed under GNU General Public License v3.
 *
 * Repackaged for pods-ov-agent. Joget extends PropertyEditable on this
 * interface; we drop that inheritance to avoid pulling in the Joget plugin
 * lifecycle. Implementations may still expose configuration via plain methods
 * if needed.
 */
package com.pods.agent.workflow.joget.plugin;

import com.pods.agent.workflow.joget.model.DecisionResult;
import java.util.Map;

/**
 * Routing/decision plugin invoked at activities of TYPE_ROUTE. Returns a
 * {@link DecisionResult} describing which transitions to follow, optional
 * variable updates, and whether this is an AND-split.
 */
public interface DecisionPlugin {

    DecisionResult getDecision(String processDefId,
                               String processId,
                               String routeId,
                               Map<String, String> variables);
}
