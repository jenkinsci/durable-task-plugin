## Changelog

* For newer versions, see [GitHub Releases](https://github.com/jenkinsci/durable-task-plugin/releases)

### Version 1.36

Release date: 2021-05-04

- Fix: PowerShell command invocation errors will now fail at the pipeline step ([JENKINS-59529](https://issues.jenkins.io/browse/JENKINS-59529))
- Add an option to load the PowerShell profile ([PR 130](https://github.com/jenkinsci/durable-task-plugin/pull/130))

### Version 1.35

Release date: 2020-09-02

- Fix: Convert `FileMonitoringTask$FileMonitoringController$1` to a named class to avoid log warnings related to serializing anonymous classes ([JENKINS-55145](https://issues.jenkins-ci.org/browse/JENKINS-55145))
- Internal: Fix CI build on Windows ([PR 125](https://github.com/jenkinsci/durable-task-plugin/pull/125))

### Version 1.34

Release date: 2020-03-10

- Internal: Clean up deprecated code and unused imports ([PR-109](https://github.com/jenkinsci/durable-task-plugin/pull/109))
- Internal: Remove utility function for tempDir and use implementation from core instead ([PR-110](https://github.com/jenkinsci/durable-task-plugin/pull/110))
- Internal: Upgrade parent pom to 3.54 ([PR-119](https://github.com/jenkinsci/durable-task-plugin/pull/119))
- Fix: Explicitly close `ProcessPipeInputStream` to prevent agent-side OOM ([JENKINS-60960](https://issues.jenkins-ci.org/browse/JENKINS-60960))
- Internal: Make `generate-binaries` script return error. Refactor unit tests to support infra changes and to skip binary tests
  when binary is not generated ([PR-121](https://github.com/jenkinsci/durable-task-plugin/pull/121))

### Version 1.33

Release date: 2019-10-29

- Disable binary wrapper caching when there are inadequate permissions to access the cache dir (namely containerized instances).
  ([JENKINS-59903](https://issues.jenkins-ci.org/browse/JENKINS-59903))
- Do not use binary wrapper on non-x86 and FreeBSD architectures ([JENKINS-59907](https://issues.jenkins-ci.org/browse/JENKINS-59907))

### Version 1.32

Release date: 2019-10-28

> **WARNING**: The bugs introduced in 1.31 are still present (([JENKINS-59903](https://issues.jenkins-ci.org/browse/JENKINS-59903),
> [JENKINS-59907](https://issues.jenkins-ci.org/browse/JENKINS-59907))

- Migrate changelog from wiki to github, add README ([PR \#113](https://github.com/jenkinsci/durable-task-plugin/pull/113))
- Disable binary wrapper (introduced in 1.31) by default.
    - To enable binary wrapper, pass the system property
      `org.jenkinsci.plugins.durabletask.BourneShellScript.FORCE_BINARY_WRAPPER=true` to the Java command line used to start Jenkins.

### Version 1.31

Release date: 2019-10-22

> **WARNING**: This version (1.31) introduced bugs where scripts will not be able to launch on non-x86 platforms and
> container-based agents that do not have access to the agent node's root directory.
 
> **NOTE**: To revert to previous release behavior, pass the system property
> `org.jenkinsci.plugins.durabletask.BourneShellScript.FORCE_SHELL_WRAPPER=true` to the Java command line used to start Jenkins.

-   Update ssh-slaves ([PR \#100](https://github.com/jenkinsci/durable-task-plugin/pull/100))
-   Do not fail tests when run on a machine without Docker installed.
    ([PR \#101](https://github.com/jenkinsci/durable-task-plugin/pull/101))
-   Improve watcher logging
    ([PR \#102](https://github.com/jenkinsci/durable-task-plugin/pull/102))
-   Refactor UNIX unit tests for greater test coverage
    ([PR \#103](https://github.com/jenkinsci/durable-task-plugin/pull/103))
-   Allow setting pwsh as Powershell executable
    ([PR \#112](https://github.com/jenkinsci/durable-task-plugin/pull/111))
-   Bugfix: Use setsid instead of nohup ([JENKINS-25503](https://issues.jenkins-ci.org/browse/JENKINS-25503))
    - For \*NIX systems only, the shell wrapper has been replaced with a pre-compiled golang binary.
    - The binary launches the script under a new session to better survive unexpected Jenkins terminations.
    - Just like how the shell wrapper executes in the background (since 1.30), the script launcher
      is a daemonized process. The means that there is an expectation of orphaned-child cleanup
      (i.e. zombie-reaping) within the underlying environment.
    - The binary itself is \~2.5MB per binary. There are 4 pre-compiled binaries (32 and 64bit versions
      for UNIX and DARWIN).
    - The memory footprint is \~800KB heavier than the shell wrapper.
        - The two shell processes (610-640KB) and single sleep process (548KB) are replaced by a
          single process (\~2560KB)

### Version 1.30

Release date: 2019-07-05

-   Bugfix: Run the wrapper process for shell scripts in the background.
    ([JENKINS-58290](https://issues.jenkins-ci.org/browse/JENKINS-58290)). This
    means that when the script exits, the wrapper process will be orphaned. In most cases, the
    orphaned process is cleaned up by the underlying OS (ie zombie-reaping). Special flags must
    be used to enable zombie-reaping in docker containers (--init) or kubernetes pods (shared
    process namespaces).
-   Bugfix: Use `sh` to run shell scripts rather than attempting to
    use the absolute path to the default shell from the master on
    agents. ([PR \#95](https://github.com/jenkinsci/durable-task-plugin/pull/95))
-   Bugfix: Make PowerShell exit codes propagate correctly. Fixes a
    regression from version 1.23
    ([JENKINS-52884](https://issues.jenkins-ci.org/browse/JENKINS-52884))

### Version 1.29

Release date: 2019-01-31

-   Enhancement: Add support for z/OS Unix System Services to the `sh`
    step. ([JENKINS-37341](https://issues.jenkins-ci.org/browse/JENKINS-37341))

### Version 1.28

Release date: 2018-11-14

-   Bugfix: Do not rely on a shebang line to select the interpreter
    for `sh` scripts. This means that Pipelines can now use relative
    names for interpreters accessible from the `PATH` environment
    variable.
    ([JENKINS-50902](https://issues.jenkins-ci.org/browse/JENKINS-50902))

### Version 1.27

Release date: 2018-11-01

-   Do not print the working directory or the type of script being run
    when a durable task starts. ([PR \#83](https://github.com/jenkinsci/durable-task-plugin/pull/83))
-   Internal: Shut down thread pools when Jenkins shuts down. Should
    only affect other plugins using this plugin in their tests.

### Version 1.26

Release date: 2018-09-25

-   Bugfix: Increase the default heartbeat interval used to detect dead
    processes from 15 seconds to 5 minutes
    ([JENKINS-48300](https://issues.jenkins-ci.org/browse/JENKINS-48300))
-   Developer: Define API for pushing durable task logs from build
    agents directly instead of having the Jenkins master pull logs from
    build agents
    ([JENKINS-52165](https://issues.jenkins-ci.org/browse/JENKINS-52165))

### Version 1.25

Release date: 2018-08-08

-   Major bugfix: Fix regressions in 1.23 and 1.24 that caused build
    failures when running `sh` steps in minimal environments such as
    Alpine and Cygwin
    ([JENKINS-52881](https://issues.jenkins-ci.org/browse/JENKINS-52881))

### Version 1.24

Release date: 2018-08-07

-   **(Warning: Fix is incomplete. Full fix is in version 1.25)** Major
    bugfix: Fix regression in 1.23 that caused build failures on
    Alpine-based build agents
    ([JENKINS-52847](https://issues.jenkins-ci.org/browse/JENKINS-52847))
-   Developer: Define API for gathering command output in a local
    encoding
    ([JEP-206](https://github.com/jenkinsci/jep/blob/master/jep/206/README.adoc))

### Version 1.23

Release date: 2018-07-31

-   Major bugfix: properly count log content sent to avoid
    endlessly-repeating logs
    ([JENKINS-37575](https://issues.jenkins-ci.org/browse/JENKINS-37575))
-   Bugfix: Ensure that the logfile-touching process exits when the
    wrapper script running the durable task dies
    ([JENKINS-50892](https://issues.jenkins-ci.org/browse/JENKINS-50892))
-   Fix/Enhancement: Simplify Powershell execution and make it more
    consistent
    ([JENKINS-50840](https://issues.jenkins-ci.org/browse/JENKINS-50840))
-   Admin: support incrementals

### Version 1.22

Release date: 2018-03-13

-   Bugfix: Fix issues with Powershell error handling
    ([JENKINS-50029](https://issues.jenkins-ci.org/browse/JENKINS-50029))

### Version 1.21

Release date: 2018-03-08

-   Bugfix: Resolves regression with Batch steps "hanging" on some
    Windows build agents
    ([JENKINS-50025](https://issues.jenkins-ci.org/browse/JENKINS-50025))
    introduced by 1.19
    -   Fix for existing builds suffering the issue: go into the control
        directory in the workspace and run 'move jenkins-result.txt.tmp
        jenkins-result.txt' - the batch step will complete normally

### Version 1.20

Release date: 2018-03-07

-   Bugfix: Prevent PowerShell stdout pollution when using returnStdout
    ([JENKINS-49754](https://issues.jenkins-ci.org/browse/JENKINS-49754))

### Version 1.19

Release date: 2018-03-07

-   Bugfix: Fix bogus DurableTask failures with "exit status code -1"
    due to non-atomic write of exit status code from processes
    ([JENKINS-25519](https://issues.jenkins-ci.org/browse/JENKINS-25519))

### Version 1.18

Release date: 2018-02-16

-   **Major Bug Fixes to PowerShell step:**
    -   Incorrect exit
        codes ([JENKINS-46876](https://issues.jenkins-ci.org/browse/JENKINS-46876))
    -   Hanging
        ([JENKINS-46508](https://issues.jenkins-ci.org/browse/JENKINS-46508))
    -   Does not output UTF-8 byte order mark
        ([JENKINS-46496](https://issues.jenkins-ci.org/browse/JENKINS-46496))
    -   Does not show live output as of version 1.15
        ([JENKINS-48057](https://issues.jenkins-ci.org/browse/JENKINS-48057))
    -   Step always returns success
        ([JENKINS-47797](https://issues.jenkins-ci.org/browse/JENKINS-47797))
-   Add passing of TaskListener API
    ([JENKINS-48300](https://issues.jenkins-ci.org/browse/JENKINS-48300))
-   Test fixes

### Version 1.17

Release date: 2017-11-21

-   Version 1.16 accidentally declared a Java dependency of 8+, despite
    being otherwise compatible with Jenkins 2.7.x+ which run on Java 7.
    Reverted to 7+.
-   Internal: improved resilience of Docker-based test suites.

### Version 1.16

Release date: 2017-11-14

> **WARNING**: This version (1.16) temporarily introduced a dependency on Java 8 which
was reverted to Java 7 with version 1.17

-   [JENKINS-47791](https://issues.jenkins-ci.org/browse/JENKINS-47791): Using a new system for determining
    whether `sh` step processes are still alive, which should solve
    various robustness issues.
-   [JENKINS-46496](https://issues.jenkins-ci.org/browse/JENKINS-46496): Fixed BOM issue with the `powershell` step, perhaps

### Version 1.15

Release date: 2017-10-13

-   Apply a timeout to checking when processes are started, so that we
    can't hang indefinitely

### Version 1.14

Release date: 2017-06-15

-   JENKINS-34581 Powershell support.
-   JENKINS-43639 File descriptor leak.

### Version 1.13

Release date: 2017-01-18

-   [JENKINS-40734](https://issues.jenkins-ci.org/browse/JENKINS-40734)
    Environment variable values containing `$` were not correctly passed
    to subprocesses.
-   [JENKINS-40225](https://issues.jenkins-ci.org/browse/JENKINS-40225)
    Replace backslashes when on Cygwin to allow `sh` to be used.

> **WARNING**: Users setting node (or global) environment variables like
`PATH=/something:$PATH` will see Pipeline `sh` failures with this update
unless you also update the [Pipeline Nodes and Processes
Plugin](https://wiki.jenkins.io/display/JENKINS/Pipeline+Nodes+and+Processes+Plugin)
to 2.9 or later
([JENKINS-41339](https://issues.jenkins-ci.org/browse/JENKINS-41339)).
Anyway you are advised to use the syntax `PATH+ANYKEY=/something`, as
documented in inline help.

### Version 1.12

Release date: 2016-07-28

-   Infrastructure for
    [JENKINS-26133](https://issues.jenkins-ci.org/browse/JENKINS-26133).

### Version 1.11

Release date: 2016-06-29

-   Infrastructure for
    [JENKINS-31842](https://issues.jenkins-ci.org/browse/JENKINS-31842).

### Version 1.10

Release date: 2016-05-19

-   [JENKINS-34150](https://issues.jenkins-ci.org/browse/JENKINS-34150)
    `bat` hangs under some conditions.

### Version 1.9

Release date: 2016-03-24

-   [JENKINS-32701](https://issues.jenkins-ci.org/browse/JENKINS-32701)
    Handle percent signs in the working directory for batch scripts, for
    example due to a Pipeline branch project based on a Git branch with
    a `/` in its name.

### Version 1.8

Release date: 2016-03-03

-   [JENKINS-27152](https://issues.jenkins-ci.org/browse/JENKINS-27152)
    Store control directory outside of the workspace.
-   [JENKINS-28400](https://issues.jenkins-ci.org/browse/JENKINS-28400)
    Better diagnostics when wrapper shell script fails to start.
-   [JENKINS-25678](https://issues.jenkins-ci.org/browse/JENKINS-25678)
    Refinement of fix in 1.4.

### Version 1.8-beta-1

Release date: 2016-01-19

-   [JENKINS-32264](https://issues.jenkins-ci.org/browse/JENKINS-32264)
    Linux-only process liveness check broke usage on FreeBSD.

### Version 1.7

Release date: 2015-12-03

-   [JENKINS-27152](https://issues.jenkins-ci.org/browse/JENKINS-27152)
    Not a fix, but use a more predictable control directory name.
-   [JENKINS-27419](https://issues.jenkins-ci.org/browse/JENKINS-27419)
    Handle batch scripts that `exit` without `/b`.

### Version 1.6

Release date: 2015-04-08

-   Do not kill a one-shot agent merely because a flyweight task
    happened to run on it (rather than on master as usual). Works around
    a bug in the Multibranch API plugin.

### Version 1.5

Release date: 2015-05-04

-   Requires Jenkins 1.565.3+.
-   Richer API for launching and stopping processes.

### Version 1.4

Release date: 2015-03-06

-   [JENKINS-25678](https://issues.jenkins-ci.org/browse/JENKINS-25678)
    Space-in-path bug affecting Windows builds.

### Version 1.3

Release date: 2015-02-02

-   Continuing to try to fix deadlocks.

### Version 1.2

Release date: 2015-01-13

-   [JENKINS-26380](https://issues.jenkins-ci.org/browse/JENKINS-26380)
    Occasional deadlocks when running against Jenkins 1.592+.

### Version 1.1

Release date: 2014-12-05

-   [JENKINS-25848](https://issues.jenkins-ci.org/browse/JENKINS-25848)
    Failure to run shell tasks on some Mac OS X installations.

### Version 1.0

Release date: 2014-11-25

-   [JENKINS-25727](https://issues.jenkins-ci.org/browse/JENKINS-25727)
    Race condition causing spurious -1 exit codes, especially for
    short-lived shell scripts.
-   Print a warning when asked to run an empty shell script.
-   Avoid allocating a useless thread on the agent while running a task.

### Version 0.7

Release date: 2014-10-10

-   [JENKINS-25727](https://issues.jenkins-ci.org/browse/JENKINS-25727)
    Better reliability of liveness checker when short-lived scripts are
    being run. (Amended in 1.0.)

### Version 0.6

Release date: 2014-10-08

-   [JENKINS-22249](https://issues.jenkins-ci.org/browse/JENKINS-22249)
    Detect if the wrapper shell script is dead, for example because the
    machine was rebooted.
-   Efficiency improvements in log copying.

### Version 0.5

Release date: 2014-09-24

-   New APIs `ContinuableExecutable` and `OnceRetentionStrategy`.
-   Moved `ContinuedTask` into a subpackage.

### Version 0.4

Release date: 2014-08-27

-   New API: <https://trello.com/c/JKJFUWXo/33-prioritizedtask>
-   Better error handling.
-   [JENKINS-23027](https://issues.jenkins-ci.org/browse/JENKINS-23027)
    Print nicer output when running on newer versions of Jenkins.

### Version 0.3

Release date: 2014-07-22

-   Supporting `java.io.Serializable`.

### Version 0.2

Release date: 2014-05-29

-   [JENKINS-22248](https://issues.jenkins-ci.org/browse/JENKINS-22248)
    Allow multiple scripts to run in the same workspace concurrently.
-   Allow a “shell” script to override the interpreter.
-   Use the default configured shell.
-   Start the shell with `-xe` (echo commands, fail on error).

### Version 0.1

Release date: 2014-03-18

-   Initial release.
