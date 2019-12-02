package com.veertu.plugin.anka;

import com.veertu.ankaMgmtSdk.AnkaMgmtVm;
import com.veertu.ankaMgmtSdk.AnkaAPI;
import com.veertu.ankaMgmtSdk.exceptions.AnkaMgmtException;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.*;
import jenkins.slaves.RemotingWorkDirSettings;
import org.apache.commons.lang.RandomStringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by asafgur on 16/11/2016.
 */
public class AnkaOnDemandSlave extends AbstractAnkaSlave {

    private boolean acceptingTasks = true;

    protected AnkaOnDemandSlave(String name, String nodeDescription, String remoteFS, int numExecutors,
                                Mode mode, String labelString, ComputerLauncher launcher,
                                List<? extends NodeProperty<?>> nodeProperties,
                                AnkaCloudSlaveTemplate template, AnkaMgmtVm vm) throws Descriptor.FormException, IOException {
        super(name, nodeDescription, remoteFS, numExecutors, mode, labelString,
                launcher, template.getRetentionStrategy(), nodeProperties, template, vm);
    }

    public static String generateName(AnkaCloudSlaveTemplate template){
        String randomString = RandomStringUtils.randomAlphanumeric(16);
        String nameTemplate = template.getNameTemplate();
        if (nameTemplate != null && !nameTemplate.isEmpty()) {
            nameTemplate = nameTemplate.replace("$intance_id", "");
            nameTemplate = nameTemplate.replace("$node_id", "");
            nameTemplate = nameTemplate.replace("$node_name", "");
            nameTemplate = nameTemplate.replace("$template_name", "");
            nameTemplate = nameTemplate.replace("$template_id", template.getMasterVmId());
            if (nameTemplate.contains("$ts")) {
                Long unixTime = System.currentTimeMillis() / 1000L;
                nameTemplate = nameTemplate.replace("$ts", unixTime.toString());
                return nameTemplate;
            }
            return nameTemplate + "_" + randomString;
        }
        return template.getMasterVmId() + randomString;
    }

    public static AnkaOnDemandSlave createProvisionedSlave(AnkaAPI ankaAPI, AnkaCloudSlaveTemplate template)
            throws IOException, AnkaMgmtException, Descriptor.FormException, InterruptedException {
        if (template.getLaunchMethod().toLowerCase().equals(LaunchMethod.SSH)) {
            return createSSHSlave(ankaAPI, template);
        } else if (template.getLaunchMethod().toLowerCase().equals(LaunchMethod.JNLP)) {
            return createJNLPSlave(ankaAPI, template);
        }
        return null;
    }

    private static AnkaOnDemandSlave createJNLPSlave(AnkaAPI ankaAPI, AnkaCloudSlaveTemplate template) throws AnkaMgmtException, IOException, Descriptor.FormException {
//        AnkaMgmtCloud.Log("vm %s is booting...", vm.getId());
        String nodeName = generateName(template);
        String jnlpCommand = JnlpCommandBuilder.makeStartUpScript(nodeName, template.getExtraArgs(), template.getJavaArgs(), template.getJnlpJenkinsOverrideUrl());

        AnkaMgmtVm vm = ankaAPI.makeAnkaVm(
                template.getMasterVmId(), template.getTag(), template.getNameTemplate(), template.getSSHPort(), jnlpCommand, template.getGroup(), template.getPriority());
        try {
            vm.waitForBoot(template.getSchedulingTimeout());
        } catch (InterruptedException| IOException|AnkaMgmtException e) {
            vm.terminate();
            throw new AnkaMgmtException(e);
        }
        AnkaMgmtCloud.Log("vm %s %s is booted, creating jnlp launcher", vm.getId(), vm.getName());

        String tunnel = "";
        JNLPLauncher launcher = new JNLPLauncher(template.getJnlpTunnel(),
                "",
                RemotingWorkDirSettings.getEnabledDefaults());
        ArrayList<EnvironmentVariablesNodeProperty.Entry> a = new ArrayList<EnvironmentVariablesNodeProperty.Entry>();
        for (AnkaCloudSlaveTemplate.EnvironmentEntry e :template.getEnvironments()) {
            a.add(new EnvironmentVariablesNodeProperty.Entry(e.name, e.value));
        }

        EnvironmentVariablesNodeProperty env = new EnvironmentVariablesNodeProperty(a);
        ArrayList<NodeProperty<?>> props = new ArrayList<>();
        props.add(env);

        AnkaMgmtCloud.Log("launcher created for vm %s %s", vm.getId(), vm.getName());
        AnkaOnDemandSlave slave = new AnkaOnDemandSlave(nodeName, template.getTemplateDescription(), template.getRemoteFS(),
                template.getNumberOfExecutors(),
                template.getMode(),
                template.getLabelString(),
                launcher,
                props, template, vm);
        slave.setDisplayName(vm.getName());
        return slave;
    }

    private static AnkaOnDemandSlave createSSHSlave(AnkaAPI ankaAPI, AnkaCloudSlaveTemplate template) throws InterruptedException, AnkaMgmtException, IOException, Descriptor.FormException {
        AnkaMgmtVm vm = ankaAPI.makeAnkaVm(
                template.getMasterVmId(), template.getTag(), template.getNameTemplate(), template.getSSHPort(), null, template.getGroup(), template.getPriority());
        try {

            ArrayList<EnvironmentVariablesNodeProperty.Entry> a = new ArrayList<EnvironmentVariablesNodeProperty.Entry>();
            for (AnkaCloudSlaveTemplate.EnvironmentEntry e : template.getEnvironments()) {
                a.add(new EnvironmentVariablesNodeProperty.Entry(e.name, e.value));
            }

            EnvironmentVariablesNodeProperty env = new EnvironmentVariablesNodeProperty(a);
            ArrayList<NodeProperty<?>> props = new ArrayList<>();
            props.add(env);

            AnkaOnDemandSlave slave = new AnkaOnDemandSlave(vm.getId(), template.getTemplateDescription(), template.getRemoteFS(),
                    template.getNumberOfExecutors(),
                    template.getMode(),
                    template.getLabelString(),
                    null,
                    props, template, vm);
            AnkaMgmtCloud.Log("vm %s is booting...", vm.getId());
            try {
                vm.waitForBoot(template.getSchedulingTimeout());
            } catch (InterruptedException| IOException|AnkaMgmtException e) {
                vm.terminate();
                throw new AnkaMgmtException(e);
            }
            AnkaMgmtCloud.Log("vm %s %s is booted, creating ssh launcher", vm.getId(), vm.getName());
            SSHLauncher launcher = new SSHLauncher(vm.getConnectionIp(), vm.getConnectionPort(),
                    template.getCredentialsId(),
                    template.getJavaArgs(), null, null, null, launchTimeoutSeconds, maxNumRetries, retryWaitTime, null);



            slave.setLauncher(launcher);

            AnkaMgmtCloud.Log("launcher created for vm %s %s", vm.getId(), vm.getName());
            String name = vm.getName();
            slave.setNodeName(name);
            return slave;
        }
        catch (Exception e) {
            e.printStackTrace();
            vm.terminate();
            throw e;
        }
    }


    public void setDescription(String jobAndNumber) {
        String description = String.format("master image: %s, job name and build number: %s, vm info: (%s)",
                template.getMasterVmId(), jobAndNumber, this.vm.getInfo());
        super.setNodeDescription(description);

    }


    public boolean isKeepAliveOnError(){
        return this.template.isKeepAliveOnError();
    }

    public boolean canTerminate(){
        if(hadProblemsInBuild) {
            if(isKeepAliveOnError()){
                return false;
            }
        }
        return true;
    }

    public void setHadErrorsOnBuild(boolean value){
        this.hadProblemsInBuild = value;
    }

    @Extension
    public static class VeertuCloudComputerListener extends ComputerListener {

        @Override
        public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            super.preLaunch(c, taskListener);
        }
    }
}
