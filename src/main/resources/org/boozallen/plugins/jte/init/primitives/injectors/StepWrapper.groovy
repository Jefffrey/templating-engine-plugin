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

package org.boozallen.plugins.jte.init.primitives.injectors

import com.cloudbees.groovy.cps.NonCPS
import org.boozallen.plugins.jte.init.primitives.TemplateException
import org.boozallen.plugins.jte.init.primitives.TemplatePrimitive
import org.boozallen.plugins.jte.init.primitives.hooks.*
import org.boozallen.plugins.jte.util.TemplateLogger
import org.codehaus.groovy.runtime.InvokerHelper
import org.codehaus.groovy.runtime.InvokerInvocationException

/*
    represents a library step. 

    this class serves as a wrapper class for the library step Script. 
    It's necessary for two reasons: 
    1. To give steps binding protection via TemplatePrimitive
    2. To provide a means to do LifeCycle Hooks before/after step execution
*/
class StepWrapper extends TemplatePrimitive implements Serializable{
    private Object impl
    private Binding binding
    private String name
    private String library 

    @NonCPS
    String getName(){ return name }
    
    @NonCPS
    String getLibrary(){ return library }
    
    /*
        need a call method defined on method missing so that 
        CpsScript recognizes the StepWrapper as something it 
        should execute in the binding. 
    */
    def call(Object... args){
        return invoke("call", args) 
    }

    /*
        all other method calls go through CpsScript.getProperty to 
        first retrieve the StepWrapper and then attempt to invoke a 
        method on it. 
    */
    def methodMissing(String methodName, args){
        return invoke(methodName, args)     
    }
    
    /*
        pass method invocations on the wrapper to the underlying
        step implementation script. 
    */
    def invoke(String methodName, Object... args){
        if(InvokerHelper.getMetaClass(impl).respondsTo(impl, methodName, args)){
            def result
            HookContext context = new HookContext(
                step: name, 
                library: library
            )
            try{
                Hooks.invoke(BeforeStep, binding, context)
                TemplateLogger.createDuringRun().print "[Step - ${library}/${name}.${methodName}(${args.collect{ it.getClass().simpleName }.join(", ")})]"
                result = InvokerHelper.getMetaClass(impl).invokeMethod(impl, methodName, args)
            } catch (Exception x) {
                throw new InvokerInvocationException(x)
            } finally{
                Hooks.invoke(AfterStep, binding, context)
                Hooks.invoke(Notify, binding, context)
            }
            return result 
        }else{
            throw new TemplateException("Step ${name} from the library ${library} does not have the method ${methodName}(${args.collect{ it.getClass().simpleName }.join(", ")})")
        }
    }

    void throwPreLockException(){
        throw new TemplateException ("Library Step Collision. The step ${name} already defined via the ${library} library.")
    }

    void throwPostLockException(){
        throw new TemplateException ("Library Step Collision. The variable ${name} is reserved as a library step via the ${library} library.")
    }
}