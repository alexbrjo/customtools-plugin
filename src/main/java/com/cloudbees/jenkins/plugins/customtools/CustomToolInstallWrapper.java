/*
 * Copyright 2012, CloudBees Inc., Synopsys Inc. and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudbees.jenkins.plugins.customtools;

import com.synopsys.arc.jenkinsci.plugins.customtools.CustomToolsLogger;
import com.synopsys.arc.jenkinsci.plugins.customtools.CustomToolException;
import com.synopsys.arc.jenkinsci.plugins.customtools.EnvVariablesInjector;
import com.synopsys.arc.jenkinsci.plugins.customtools.LabelSpecifics;
import com.synopsys.arc.jenkinsci.plugins.customtools.PathsList;
import com.synopsys.arc.jenkinsci.plugins.customtools.multiconfig.MulticonfigWrapperOptions;
import com.synopsys.arc.jenkinsci.plugins.customtools.versions.ToolVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.matrix.MatrixBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.Run.RunnerAbortedException;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import hudson.model.TaskListener;
import hudson.tasks.BuildWrapperDescriptor;
import jenkins.model.Jenkins;

import jenkins.tasks.SimpleBuildWrapper;
import net.sf.json.JSONObject;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Installs tools selected by the user. Exports configured paths and a home variable for each tool.
 * 
 * @author rcampbell
 * @author Oleg Nenashev
 *
 */
public class CustomToolInstallWrapper extends SimpleBuildWrapper {

    /**
     * Ceremony needed to satisfy NoStaplerConstructionException:
     * "There's no @DataBoundConstructor on any constructor of class java.lang.String"
     * @author rcampbell
     *
     */
    public static class SelectedTool {
        private final String name;
        
        @DataBoundConstructor
        public SelectedTool(String name) {
            this.name = name;
        }
        
        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
        
        public @CheckForNull CustomTool toCustomTool() {
            return ((CustomTool.DescriptorImpl) Jenkins.getActiveInstance().getDescriptor(CustomTool.class)).byName(name);
        }
        
        public @Nonnull CustomTool toCustomToolValidated() throws CustomToolException {
            CustomTool tool = toCustomTool();
            if (tool == null) {
                throw new CustomToolException(
                    Messages.CustomTool_GetToolByName_ErrorMessage(name));
            }
            return tool;
        }
    }
    
    private @Nonnull SelectedTool[] selectedTools = new SelectedTool[0];
    private final @CheckForNull MulticonfigWrapperOptions multiconfigOptions;    
    private final boolean convertHomesToUppercase;
    
    @DataBoundConstructor
    public CustomToolInstallWrapper(SelectedTool[] selectedTools, MulticonfigWrapperOptions multiconfigOptions, boolean convertHomesToUppercase) {
        this.selectedTools = (selectedTools != null) ? selectedTools : new SelectedTool[0];
        this.multiconfigOptions = (multiconfigOptions != null) ? multiconfigOptions : MulticonfigWrapperOptions.DEFAULT;
        this.convertHomesToUppercase = convertHomesToUppercase;
    }
    
    public boolean isConvertHomesToUppercase() {
        return convertHomesToUppercase;
    }   
    
    @Override
    public void setUp(Context context, Run<?, ?> run, FilePath workspace, Launcher launcher,
                      TaskListener listener, EnvVars initialEnvironment) throws IOException, InterruptedException {
        decorateContext(context, run, launcher, listener);
        Map<String, String> env = context.getEnv();
                for (SelectedTool selectedTool : selectedTools) {
                    CustomTool tool = selectedTool.toCustomTool();
                    if (tool != null && tool.hasVersions()) {
                        ToolVersion version = ToolVersion.getEffectiveToolVersion(tool, run.getEnvironment(listener), workspace.toComputer().getNode());
                        if (version != null && !env.containsKey(version.getVariableName())) {
                            env.put(version.getVariableName(), version.getDefaultVersion());
                        }
                    }
                }
            }
    
    public @Nonnull SelectedTool[] getSelectedTools() {
        return selectedTools.clone();
    }
    
    /**
     * The heart of the beast. Installs selected tools and exports their paths to the 
     * PATH and their HOMEs as environment variables.
     */
    private void decorateContext(Context context, Run<?, ?> run, final Launcher launcher,
            TaskListener listener) throws IOException, InterruptedException,
            RunnerAbortedException {
        EnvVars buildEnv = run.getEnvironment(listener);
        final EnvVars homes = new EnvVars();
        final EnvVars versions = new EnvVars();
        
        final PathsList paths = new PathsList();
        final List<EnvVariablesInjector> additionalVarInjectors = new LinkedList<EnvVariablesInjector>();
        
        // Handle multi-configuration build
        if (run instanceof MatrixBuild) {
            CustomToolsLogger.logMessage(listener, "Skipping installation of tools at the master job");
            if (getMulticonfigOptions().isSkipMasterInstallation()) {
                return;
            }
        }
        
        // Each tool can export zero or many directories to the PATH
        final Node node = launcher.getComputer().getNode();
        if (node == null) {
            throw new CustomToolException("Cannot install tools on the deleted node");
        }
        
        for (SelectedTool selectedToolName : selectedTools) {
            CustomTool tool = selectedToolName.toCustomToolValidated();            
            CustomToolsLogger.logMessage(listener, tool.getName(), "Starting installation");
            
            // Check versioning
            checkVersions(tool, listener, buildEnv, node, versions);
            
            // This installs the tool if necessary
            CustomTool installed = tool
                    .forNode(node, listener)
                    .forEnvironment(buildEnv);
                    // .forBuildProperties(run.getParent().getProperties()); TODO: add equivalent
            
            try {
                installed.check();
            } catch (CustomToolException ex) {
                throw new AbortException(ex.getMessage());
            }
            
            // Handle global options of the tool      
            //TODO: convert to label specifics?
            final PathsList installedPaths = installed.getPaths(node);          
            installed.correctHome(installedPaths);
            paths.add(installedPaths);
            final String additionalVars = installed.getAdditionalVariables();
            if (additionalVars != null) {
                additionalVarInjectors.add(EnvVariablesInjector.create(additionalVars));
            }

            // Handle label-specific options of the tool
            for (LabelSpecifics spec : installed.getLabelSpecifics()) {              
                if (!spec.appliesTo(node)) {
                    continue;
                }
                CustomToolsLogger.logMessage(listener, installed.getName(), "Label specifics from '"+spec.getLabel()+"' will be applied");
                    
                final String additionalLabelSpecificVars = spec.getAdditionalVars();
                if (additionalLabelSpecificVars != null) {
                    additionalVarInjectors.add(EnvVariablesInjector.create(additionalLabelSpecificVars));
                }
            }
            
            CustomToolsLogger.logMessage(listener, installed.getName(), "Tool is installed at "+ installed.getHome());
            String homeDirVarName = (convertHomesToUppercase ? installed.getName().toUpperCase(Locale.ENGLISH) : installed.getName()) +"_HOME";
            CustomToolsLogger.logMessage(listener, installed.getName(), "Setting "+ homeDirVarName+"="+installed.getHome());
            homes.put(homeDirVarName, installed.getHome());
        }

                EnvVars vars = new EnvVars();

                // Inject paths
                final String injectedPaths = paths.toListString();
                if (injectedPaths != null) {
                    vars.override("PATH+", injectedPaths);
                }

                // Inject additional variables
                vars.putAll(homes);
                vars.putAll(versions);
                for (EnvVariablesInjector injector : additionalVarInjectors) {
                    injector.Inject(vars);
                }

                // Override paths to prevent JENKINS-20560
                if (vars.containsKey("PATH")) {
                    final String overallPaths=vars.get("PATH");
                    vars.remove("PATH");
                    vars.put("PATH+", overallPaths);
                }

                for (Map.Entry<String, String> var : vars.entrySet()) {
                    context.env(var.getKey(), var.getValue());
                }
    }
    
    /**
     * @deprecated The method is deprecated. It will be removed in future versions.
     * @throws CustomToolException
     */
    @SuppressFBWarnings(value = "NM_METHOD_NAMING_CONVENTION", justification = "Deprecated, will be removed later")
    public void CheckVersions (CustomTool tool, BuildListener listener, EnvVars buildEnv, Node node, EnvVars target) 
            throws CustomToolException  {
        checkVersions(tool, listener, buildEnv, node, target);
    }
    
    /**
     * Checks versions and modify build environment if required.
     * @param tool Custom Tool
     * @param listener Build Listener
     * @param buildEnv Build Environment (can be modified)
     * @param node Target Node
     * @param target Build Internal Environment (can be modified)
     * @throws CustomToolException 
     * @since 0.4
     */
    public void checkVersions (@Nonnull CustomTool tool, @Nonnull TaskListener listener,
            @Nonnull EnvVars buildEnv, @Nonnull Node node, @Nonnull EnvVars target) throws CustomToolException {
        // Check version
        if (tool.hasVersions()) {
            ToolVersion version = ToolVersion.getEffectiveToolVersion(tool, buildEnv, node);   
            if (version == null) {
                CustomToolsLogger.logMessage(listener, tool.getName(), "Error: No version has been specified, no default version. Failing the build...");
                throw new CustomToolException("Version has not been specified for the "+tool.getName());
            }
            
            CustomToolsLogger.logMessage(listener, tool.getName(), "Version "+version.getActualVersion()+" has been specified by "+version.getVersionSource());
            
            // Override default versions
            final String versionSource = version.getVersionSource();
            if (versionSource != null && versionSource.equals(ToolVersion.DEFAULTS_SOURCE)) {            
                String envStr = version.getVariableName()+"="+version.getDefaultVersion();
                target.addLine(envStr);
                buildEnv.addLine(envStr);
            }
        }  
    }
    
    /**
     * Check if build has specific multi-configuration options
     * @return True if multi-configuration options are configured
     * @since 0.3
     */
    public boolean hasMulticonfigOptions() {
        return multiconfigOptions != MulticonfigWrapperOptions.DEFAULT;
    }

    /**
     * Gets multi-configuration job parameters.
     * @return Configured options. Returns default options if not specified.
     * @since 0.3
     */
    public @Nonnull MulticonfigWrapperOptions getMulticonfigOptions() {
        return multiconfigOptions != null ? multiconfigOptions : MulticonfigWrapperOptions.DEFAULT;
    }

    @Extension
    @Symbol("customTool")
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.Descriptor_DisplayName();
        }
        
        public CustomTool[] getInstallations() {
            return Jenkins.getActiveInstance().getDescriptorByType(CustomTool.DescriptorImpl.class).getInstallations();
        }
        
        @Override
        public boolean configure(StaplerRequest req, JSONObject json)
                throws hudson.model.Descriptor.FormException {
            //TODO: Auto-generated method stub
            return super.configure(req, json);
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }
    }
}

