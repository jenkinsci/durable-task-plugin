// The plugin must be built on a docker platform.
buildPlugin(platforms: ['docker'], test: [skip: true])


node('windows') {
    timeout(60) {
        stage ('Test (windows-8)') {

            dir('durable-task') {
                deleteDir()
                checkout scm
                bat 'mkdir target\\classes'
                bat 'mkdir target\\hpi'
                // Need compiled java jar. Since we don't know the specific name, and because
                // multiple jars are archived, get the hpi that contains the compiled jar
                unarchive mapping: ['**/*.hpi': 'hpi']
                // find the  *.hpi file nested in the directory
                bat 'for /r hpi %%f in (*.hpi) do move %%f target\\hpi\\durable-task.hpi'
                // In order to extract the hpi and jar files, need the jar bin
                List<String> env = [
                        "JAVA_HOME=${tool 'jdk8'}",
                        'PATH+JAVA=${JAVA_HOME}/bin',
                        "PATH+MAVEN=${tool 'mvn'}/bin"
                ]
                String setup = """
                                :: Get the path to the jar binary
                                echo %JAVA_HOME%
                                set jar=%JAVA_HOME%\\bin\\jar
                                chdir target\\hpi
                                :: Extract the .hpi
                                %jar% -xvf durable-task.hpi
                                dir /s
                                :: move the compiled jar to target\\classes
                                move WEB-INF\\lib\\durable-task.jar ..\\classes
                                chdir ..\\classes
                                :: extract the compiled classes
                                %jar% -xvf durable-task.jar
                                :: go back to root of project
                                chdir ..\\..
                              """
                withEnv(env) {
                    bat setup
                    // Compile only the test resources and test code, then run tests
                    bat 'mvn resources:testResources'
                    bat 'mvn compiler:testCompile'
                    // do not let the test script fail so we can record results
                    bat script: 'mvn surefire:test', returnStatus: true
                }
            }
        }
        stage("Archive (windows-8") {
            // record test results
            junit '**/target/surefire-reports/**/*.xml'
            if (currentBuild.result == 'UNSTABLE') {
                error 'There were test failures'
            }
        }
    }
}
