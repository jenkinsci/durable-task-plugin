package org.jenkinsci.plugins.durabletask.executors;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.durabletask.BourneShellScript;
import org.jenkinsci.plugins.durabletask.WindowsBatchScript;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.SimpleCommandLauncher;
import hudson.Functions;
import hudson.model.Computer;
import hudson.model.LoadStatistics;
import hudson.model.TaskListener;
import hudson.model.Descriptor.FormException;
import hudson.model.labels.LabelAtom;
import hudson.remoting.RequestAbortedException;
import hudson.remoting.Which;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.RemotingDiagnostics;
import jenkins.model.Jenkins;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

public class OnceRetentionStrategyTest {

    private final static Logger LOGGER = Logger.getLogger(OnceRetentionStrategyTest.class.getName());

    @Rule
    public LoggerRule lr = new LoggerRule().record(LOGGER, Level.ALL);

    @Rule
    public JenkinsRule jr = new JenkinsRule();

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Rule
    public BuildWatcher bw = new BuildWatcher();

    @BeforeClass
    public static void setupStatics() {
        LoadStatistics.CLOCK = 100; // 100ms for the LoadStatistics so we have quick provisioning
        // deadConnectionsShouldReLaunched seems to fail always when run as part of the suite - but passes always when run individually without the binary wrapper
        // (because the ping command and its parent cmd.exe get killed :-o )
        WindowsBatchScript.USE_BINARY_WRAPPER=true; 
        BourneShellScript.USE_BINARY_WRAPPER=true;
    }

    @Test
    public void withoutRestartNodesAreCleaned() throws Exception {
        MySimpleCloud sc = new MySimpleCloud("simples", temp.newFolder());

        jr.jenkins.clouds.add(sc);
        jr.jenkins.setNumExecutors(0);
        //jr.jenkins.getLabel("simples").nodeProvisioner. = new ImmediateNodeProvisioner();

        WorkflowJob foo = jr.jenkins.createProject(WorkflowJob.class, "foo");
        foo.setDefinition(new CpsFlowDefinition(String.join(System.lineSeparator(),
                "node('simples') {",
                Functions.isWindows() ? "  bat 'echo hello'" : "  sh 'echo hello'",
                "}"),
                true));
        WorkflowRun run = foo.scheduleBuild2(0).waitForStart();

        jr.waitForCompletion(run);
        jr.assertBuildStatusSuccess(run);

        // agent should have been removed and we should have no agents but this happens on a different Thread..
        await().atMost(Duration.ofSeconds(5)).until(() -> jr.jenkins.getNodes().isEmpty());
        assertThat(jr.jenkins.getNodes(), is(empty()));
        assertThat(jr.jenkins.getComputers(), arrayWithSize(1)); // the Jenkins computer itself
    }

    
    @Test
    @Issue("JENKINS-69277")
    public void deadConnectionsShouldReLaunched() throws Exception {
        MySimpleCloud sc = new MySimpleCloud("simples", temp.newFolder());

        jr.jenkins.clouds.add(sc);
        jr.jenkins.setNumExecutors(0);
        //jr.jenkins.getLabel("simples").nodeProvisioner. = new ImmediateNodeProvisioner();

        WorkflowJob foo = jr.jenkins.createProject(WorkflowJob.class, "foo");
        foo.setDefinition(new CpsFlowDefinition(String.join(System.lineSeparator(),
                "node('simples') {",
                Functions.isWindows() ? "  bat 'echo hello & ping -w 1000 -n 30 localhost'"
                                      : "  sh 'echo hello & sleep 30'",
                "}"),
                true));
        WorkflowRun run = foo.scheduleBuild2(0).waitForStart();
        LOGGER.log(Level.FINE, "waiting for hello");

        jr.waitForMessage("hello", run);
        LOGGER.log(Level.FINE, "killing da agent");
        
        killAgent();
        // retention strategy will kick in but may take up to 60 seconds.
        jr.waitForCompletion(run);
        jr.assertBuildStatusSuccess(run);

        // agent should have been removed and we should have no agents but this happens on a different Thread..
        await().atMost(Duration.ofSeconds(5)).until(() -> jr.jenkins.getNodes().isEmpty());
        assertThat(jr.jenkins.getNodes(), is(empty()));
        assertThat(jr.jenkins.getComputers(), arrayWithSize(1)); // the Jenkins computer itself
    }

    private void killAgent() throws IOException, InterruptedException {
        Computer[] computers = Jenkins.get().getComputers();
        for (Computer c : computers) {
            if (c instanceof MySimpleCloudComputer) {
                LOGGER.log(Level.FINE, "Asking {0} to commit suicide", c);
                try {
                    RemotingDiagnostics.executeGroovy("Runtime.getRuntime().halt(1)", c.getChannel());
                } catch (RequestAbortedException ignored) {
                    // we have just asked the Computer to commit suicide so this is expected.
                }
            }
        }
    }

    public static class MySimpleCloud extends Cloud {

        private final LabelAtom label;
        private final File agentRootDir;
        private int count = 0;
        
        public MySimpleCloud(String name, File agentRootDir) {
            super(name);
            this.agentRootDir = agentRootDir;
            label = Jenkins.get().getLabelAtom(name);
        }

        @Override
        public boolean canProvision(CloudState state) {
            boolean retVal = state.getLabel().matches(Collections.singleton(label));
            LOGGER.log(Level.FINE, "SimpleCloud.canProvision({0},{1}) -> {2}", new Object[] {state.getLabel(), state.getAdditionalPlannedCapacity(), retVal});
            return retVal;
        }

        @Override
        public Collection<PlannedNode> provision(CloudState state, int excessWorkload) {
            LOGGER.log(Level.FINE, "SimpleCloud.provision(({0}, {1}), {2}", new Object[] {state.getLabel(), state.getAdditionalPlannedCapacity(), excessWorkload});
            Collection<PlannedNode> retVal = new HashSet<PlannedNode>();
            for (int i =0; i < excessWorkload - state.getAdditionalPlannedCapacity(); ++i) {
                String agentName;
                synchronized (this) {
                    if (count != 0) {
                        // sometimes we end up with 2 agents due to the lovely cloud API.
                        LOGGER.log(Level.FINE, "not provisioning another agent as we have already provisioned.");
                        return Collections.emptyList();
                    }
                    agentName = "cloud-"+name+"-"+(++count);
                }
                
                PlannedNode n = new PlannedNode(agentName, 
                        Computer.threadPoolForRemoting.submit( () -> new MySimpleCloudSlave(agentName, new File(agentRootDir, agentName), label)),
                                1);
                LOGGER.log(Level.FINE, "SimpleCloud.provision() -> Added planned node for {0}", name);
                retVal.add(n);
            }
            return retVal;
        }
    }

    public static class MySimpleCloudSlave extends AbstractCloudSlave {

        private final LabelAtom label;
        
        public MySimpleCloudSlave(String name, File remoteFS, LabelAtom label) throws FormException, IOException {
            super(name, 
                  remoteFS.getAbsolutePath(), 
                  new SimpleCommandLauncher("java -Xmx512m -Djava.awt.headless=true -jar " + Which.jarFile(hudson.remoting.Launcher.class) + " -jar-cache " + remoteFS + " -workDir " + remoteFS));
            this.setRetentionStrategy(new OnceRetentionStrategy(1));
            LOGGER.log(Level.FINE, "SimpleCloudSlave()");
            this.label = label;
        }

        @Override
        public AbstractCloudComputer createComputer() {
            LOGGER.log(Level.FINE, "SimpleCloudSlave.createComputer()");
            return new MySimpleCloudComputer(this);
        }

        @Override
        protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
            // nothing to do here
        }

        @Override
        public Set<LabelAtom> getAssignedLabels() {
            return new HashSet<LabelAtom>(Arrays.asList(label, getSelfLabel()));
        }
    }

    public static class MySimpleCloudComputer extends AbstractCloudComputer<MySimpleCloudSlave> {

        public MySimpleCloudComputer(MySimpleCloudSlave slave) {
            super(slave);
        }
    }

}
