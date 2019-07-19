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

package org.boozallen.plugins.jte

import org.boozallen.plugins.jte.config.TemplateConfigBuilder
import org.boozallen.plugins.jte.console.TemplateLogger
import org.boozallen.plugins.jte.binding.* 
import org.boozallen.plugins.jte.config.* 
import org.boozallen.plugins.jte.hooks.* 
import org.boozallen.plugins.jte.utils.*
import jenkins.model.Jenkins
import hudson.Extension
import hudson.ExtensionList 
import org.jenkinsci.plugins.workflow.cps.GlobalVariable
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.AbstractWhitelist
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted 
import java.lang.reflect.Method
import java.lang.reflect.Constructor
import javax.annotation.Nonnull

@Extension public class TemplateEntryPointVariable extends GlobalVariable {

    @Nonnull
    @Override
    public String getName() {
        return "template"
    }

    @Nonnull
    @Override
    public Object getValue(CpsScript script) throws Exception {

        Object template

        if (script.getBinding().hasVariable(getName())) {
            template = binding.getVariable(getName())
        } else {
            // override script binding with JTE implementation 
            script.setBinding(new TemplateBinding())

            // set pipelineConfig object 
            PipelineConfig pipelineConfig = new PipelineConfig()

            // aggregate pipeline configs
            aggregateTemplateConfigurations(pipelineConfig)

            // make accessible to libs if they need to access
            // more than just their own library config block 
            script.getBinding().setVariable("pipelineConfig", pipelineConfig.getConfig().getConfig())

            script.getBinding().setVariable("templateConfigObject", pipelineConfig.getConfig())

            // populate the template
            initializeBinding(pipelineConfig, script) 

            // parse entrypoint and return 
            String entryPoint = Jenkins.instance
                                .pluginManager
                                .uberClassLoader
                                .loadClass(getClass().getName())
                                .getResource("TemplateEntryPoint.groovy")
                                .text

            template = TemplateScriptEngine.parse(entryPoint, script.getBinding())
            script.getBinding().setVariable(getName(), template)
        }
        return template
    }


    void aggregateTemplateConfigurations(PipelineConfig pipelineConfig){

        List<GovernanceTier> tiers = GovernanceTier.getHierarchy()
       
        //  we get the configs in ascending order of governance
        //  so reverse the list to get the highest precedence first
        tiers.reverse().each{ tier ->
            TemplateConfigObject config = tier.getConfig()
            if (config){
                pipelineConfig.join(config) 
            }
        }

        // get job config if present 
        FileSystemWrapper fsw = FileSystemWrapper.createFromJob()

        String repoConfigFile = fsw.getFileContents(GovernanceTier.CONFIG_FILE, "Template Configuration File", false)
        if (repoConfigFile){
            TemplateConfigObject repoConfig = TemplateConfigDsl.parse(repoConfigFile)
            pipelineConfig.join(repoConfig)
        }
        
    }

    void initializeBinding(PipelineConfig pipelineConfig, CpsScript script){
        // get the pipeline configuration 
        TemplateConfigObject config = pipelineConfig.getConfig() 
        
        // get registered injectors
        ExtensionList<TemplatePrimitiveInjector> injectors = TemplatePrimitiveInjector.all() 
        
        // do first pass at binding  
        injectors.each{ injector -> 
            injector.doInject(config, script)
        }

        // give injectors opportunity to plug
        // holes in the template. 
        injectors.each{ injector ->
            injector.doPostInject(config, script)
        }

        /*
            seal the binding.  
            <? extends TemplatePrimitive>.throwPostLockException() will now be thrown
        */
        script.getBinding().lock()   
    }


    /*
        called from TemplateEntryPoint.groovy at the start of a pipeline
        run to determine the template to be executed
    */
    @Whitelisted
    static String getTemplate(Map config){

        // tenant Jenkinsfile if allowed 
        FileSystemWrapper fs = FileSystemWrapper.createFromJob()
        String repoJenkinsfile = fs.getFileContents("Jenkinsfile", "Repository Jenkinsfile", false)
        if (repoJenkinsfile){
            if (config.allow_scm_jenkinsfile){
                return repoJenkinsfile
            }else{
                TemplateLogger.print "Warning: Repository provided Jenkinsfile that will not be used, per organizational policy."
            }
        }

        // specified pipeline template from pipeline template directories in governance tiers
        List<GovernanceTier> tiers = GovernanceTier.getHierarchy()
        if (config.pipeline_template){ 
            for (tier in tiers){
                String pipelineTemplate = tier.getTemplate(config.pipeline_template)
                if (pipelineTemplate){
                    return pipelineTemplate 
                }
            }
            throw new TemplateConfigException("Pipeline Template ${config.pipeline_template} could not be found in hierarchy.")
        }

        /*
            look for default Jenkinsfile in ascending order of governance tiers
        */
        for (tier in tiers){
            String pipelineTemplate = tier.getJenkinsfile()
            if (pipelineTemplate){
                return pipelineTemplate 
            }
        }

        throw new TemplateConfigException("Could not determine pipeline template.")

    }

    @Whitelisted
    static void runTemplate(String template, TemplateBinding binding){
        TemplateScriptEngine.parse(template, binding).run() 
    }


    @Extension public static class MiscWhitelist extends AbstractWhitelist {    
        @Override public boolean permitsMethod(Method method, Object receiver, Object[] args) {
            return ( 
                receiver in TemplateConfigBuilder || 
                receiver in TemplatePrimitive || 
                receiver in TemplateBinding || 
                receiver in TemplatePrimitiveInjector
            )
        }

        @Override public boolean permitsConstructor(Constructor<?> constructor, Object[] args){
            return constructor.getDeclaringClass().equals(TemplateConfigBuilder) 
        }

        @Override public boolean permitsStaticMethod(Method method, Object[] args){
            return (
                method.getDeclaringClass().equals(Hooks)
            )
        }

    }

}