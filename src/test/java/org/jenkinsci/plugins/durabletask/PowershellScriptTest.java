/*
 * The MIT License
 *
 * Copyright 2017 Gabriel Loewen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.durabletask;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.IOException;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import org.apache.commons.io.output.TeeOutputStream;
import static org.hamcrest.Matchers.containsString;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import java.util.Properties;
import java.util.*;
import org.apache.commons.io.IOUtils;
import org.junit.AssumptionViolatedException;

public class PowershellScriptTest {	
    @Rule public JenkinsRule j = new JenkinsRule();

    private StreamTaskListener listener;
    private FilePath ws;
    private Launcher launcher;
    private int psVersion;
    
    @Before public void vars() throws IOException, InterruptedException {
        listener = StreamTaskListener.fromStdout();
        ws = j.jenkins.getRootPath().child("ws");
        launcher = j.jenkins.createLauncher(listener);
        Properties properties = System.getProperties();
        String pathSeparator = properties.getProperty("path.separator");
        String[] paths = System.getenv("PATH").split(pathSeparator);
        boolean powershellExists = false;
        String cmd = launcher.isUnix()?"pwsh":"powershell.exe";
        for (String p : paths) {
            File f = new File(p, cmd);
            if (f.exists()) {
                powershellExists = true;
                break;
            }
        }
        Assume.assumeTrue("This test should only run if powershell is available", powershellExists == true);
        
        // Assume Powershell major version is at least 3
        if (powershellExists == true) {
            List<String> args = new ArrayList<>(Collections.singleton(cmd));
            if (!launcher.isUnix()) {
                args.addAll(Arrays.asList("-ExecutionPolicy", "Bypass"));
            }
            args.addAll(Arrays.asList("-Command", "& {Write-Output $PSVersionTable.PSVersion.Major}"));
            Launcher.ProcStarter ps = launcher.launch().cmds(args).quiet(true);
            ps.readStdout();
            Proc proc = ps.start();
            String psVersionStr = IOUtils.toString(proc.getStdout());
            try {
                psVersion = Integer.parseInt(psVersionStr.trim());
            } catch (NumberFormatException x) {
                throw new AssumptionViolatedException(psVersionStr, x);
            }
            Assume.assumeTrue("This test should only run if the powershell major version is at least 3", psVersion >= 3);
        }
    }

    @Test public void explicitExit() throws Exception {
        Controller c = new PowershellScript("Write-Output \"Hello, World!\"; exit 1;").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(1), c.exitStatus(ws, launcher));
        assertThat(baos.toString(), containsString("Hello, World!"));
        c.cleanup(ws);
    }
    
    @Test public void implicitExit() throws Exception {
        Controller c = new PowershellScript("Write-Output \"Success!\";").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(0), c.exitStatus(ws, launcher));
        assertThat(baos.toString(), containsString("Success!"));
        c.cleanup(ws);
    }
    
    @Test public void implicitError() throws Exception {
        Controller c = new PowershellScript("MyBogus-Cmdlet").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher).intValue() != 0);
        assertThat(baos.toString(), containsString("MyBogus-Cmdlet"));
        c.cleanup(ws);
    }
    
    @Test public void implicitErrorNegativeTest() throws Exception {
        Controller c = new PowershellScript("$ErrorActionPreference = 'SilentlyContinue'; MyBogus-Cmdlet").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        assertTrue(c.exitStatus(ws, launcher).intValue() == 0);
        c.cleanup(ws);
    }
    
    @Test public void explicitError() throws Exception {
        DurableTask task = new PowershellScript("Write-Output \"Hello, World!\"; throw \"explicit error\";");
        task.captureOutput();
        Controller c = task.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() != 0);
        assertThat(baos.toString(), containsString("explicit error"));
        assertEquals("Hello, World!\r\n", new String(c.getOutput(ws, launcher)));
        c.cleanup(ws);
    }
    
    @Test public void verbose() throws Exception {
        DurableTask task = new PowershellScript("$VerbosePreference = \"Continue\"; Write-Verbose \"Hello, World!\"");
        task.captureOutput();
        Controller c = task.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        assertEquals(0, c.exitStatus(ws, launcher).intValue());
        assertEquals("VERBOSE: Hello, World!\r\n", new String(c.getOutput(ws, launcher)));
        c.cleanup(ws);
    }

    @Test public void spacesInWorkspace() throws Exception {
        final FilePath newWs = new FilePath(ws, "subdirectory with spaces");
        Controller c = new PowershellScript("Write-Host 'Running in a workspace with spaces in the path'").launch(new EnvVars(), newWs, launcher, listener);
        while (c.exitStatus(newWs, launcher, listener) == null) {
            Thread.sleep(100);
        }
        assertEquals(0, c.exitStatus(newWs, launcher).intValue());
        c.cleanup(ws);
    }

    @Test public void echoEnvVar() throws Exception {
        Controller c = new PowershellScript("echo envvar=$env:MYVAR").launch(new EnvVars("MYVAR", "power$hell"), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("envvar=power$hell"));
        c.cleanup(ws);
    }
    
    @Test public void unicodeChars() throws Exception {
        Controller c = new PowershellScript("Write-Output \"Helló, Wõrld ®\";").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(0), c.exitStatus(ws, launcher));
        String log = baos.toString("UTF-8");
        assertTrue(log, log.contains("Helló, Wõrld ®"));
        c.cleanup(ws);
    }
}
