// The plugin must be built on a docker platform.
// TODO: remove skiptests if can't test manual like windows
buildPlugin(platforms: ['docker'], tests: [skip: true])


node('windows') {
    timeout(60) {
        stage ('Test windows') {

            dir('durable-task') {
                deleteDir()
                // checkout the repo again
                checkout scm
                bat 'dir'
                bat 'mkdir target\\classes'
                bat 'mkdir target\\hpi'
                // Need compiled java jar. Because multiple jars are archived,
                // easier to get the hpi that contains the compiled jar
                unarchive mapping: ['**/*.hpi': 'hpi']
                bat 'dir'
                // find the  *.hpi file nested  in the directory
                bat 'for /r hpi %%f in (*.hpi) do move %%f target\\hpi\\durable-task.hpi'
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
                    bat 'mvn resources:testResources'
                    bat 'echo adding some noise here'
                    bat 'mvn compiler:testCompile'
                    bat 'mvn surefire:test'
                    // record test results
                    junit '**/target/surefire-reports/**/*.xml'
                }
            }
        }
    }
}
