/*
    Copyright 2018 Booz Allen Hamilton

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/
package org.boozallen.plugins.jte.job

import static org.jenkinsci.plugins.workflow.cps.persistence.PersistenceContext.JOB

import org.boozallen.plugins.jte.init.PipelineConfigurationAggregator
import org.boozallen.plugins.jte.init.PipelineTemplateResolver
import org.boozallen.plugins.jte.init.governance.config.dsl.PipelineConfigurationObject
import org.boozallen.plugins.jte.init.primitives.TemplatePrimitiveInjector
import hudson.model.Action
import hudson.model.Item
import hudson.model.Queue
import hudson.model.TaskListener
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution
import org.jenkinsci.plugins.workflow.cps.persistence.PersistIn
import org.jenkinsci.plugins.workflow.flow.FlowDefinition
import org.jenkinsci.plugins.workflow.flow.FlowExecution
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint
import org.jenkinsci.plugins.workflow.flow.DurabilityHintProvider
import org.jenkinsci.plugins.workflow.flow.GlobalDefaultFlowDurabilityLevel
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.workflow.cps.CpsFlowFactoryAction2

/**
 * Defines a JTE Pipeline.
 * Performs JTE's Pipeline Initialization Process before creating the CpsFlowExecution for the WorkflowRun
 */
@PersistIn(JOB)
abstract class TemplateFlowDefinition extends FlowDefinition {

    static FlowDurabilityHint determineFlowDurabilityHint(FlowExecutionOwner owner){
        Queue.Executable exec = owner.getExecutable()
        if (!(exec instanceof WorkflowRun)) {
            throw new IllegalStateException("inappropriate context")
        }
        FlowDurabilityHint hint = (exec instanceof Item) ? DurabilityHintProvider.suggestedFor((Item)exec) : GlobalDefaultFlowDurabilityLevel.getDefaultDurabilityHint()
        return hint
    }

    @Override
    FlowExecution create(FlowExecutionOwner owner, TaskListener listener, List<? extends Action> actions) throws Exception {
        // if this is a replay run, just skip initialization
        for (Action a : actions) {
            if (a instanceof CpsFlowFactoryAction2) {
                return ((CpsFlowFactoryAction2) a).create(this, owner, actions)
            }
        }
        String template = initializePipeline(owner)
        FlowDurabilityHint hint = determineFlowDurabilityHint(owner)
        return new CpsFlowExecution(template, true, owner, hint)
    }

    /**
     * Performs JTE's Pipeline Initialization Process
     * @param owner
     * @return the Pipeline Template for the Run
     */
    String initializePipeline(FlowExecutionOwner owner){
        // Step 1: Aggregate pipeline configs
        PipelineConfigurationAggregator configurationAggregator = new PipelineConfigurationAggregator(owner)
        PipelineConfigurationObject config = configurationAggregator.aggregate()
        // Step 2: Invoke TemplatePrimitiveInjectors
        TemplatePrimitiveInjector.orchestrate(owner, config)
        // Step 3: determine the Pipeline Template
        PipelineTemplateResolver templateResolver = new PipelineTemplateResolver(owner)
        String template = templateResolver.resolve(config)
        return template
    }

}
