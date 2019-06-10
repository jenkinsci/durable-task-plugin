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
                bat 'xcopy hpi\\org\\jenkins-ci\\plugins\\durable-task\\*\\*.hpi target\\hpi'
                bat 'dir /s target'
                List<String> env = [
                        "JAVA_HOME=${tool 'jdk8'}",
                        'PATH+JAVA=${JAVA_HOME}/bin',
                        "PATH+MAVEN=${tool 'mvn'}/bin"
                ]
                String commands = """
                                :: Get the path to the jar binary
                                echo %JAVA_HOME%
                                set jar=%JAVA_HOME%\\bin\\jar
                                %jar%
                                chdir target\\hpi
                                dir
                                %jar% -xvf durable-task.file
                                dir
                                xcopy WEB-INF\\lib\\durable-task.jar ..\\classes 
                                chdir ..\\classes
                                dir
                                %jar% -xvf durable-task.jar
                                dir
                                chdir ..\\..
                                mvn resources:testResources
                                mvn compiler:testCompile
                                mvn surefire:test
                              """
                withEnv(env) {
                    bat commands
                    // record test results
                    junit '**/target/surefire-reports/**/*.xml'
                }
            }
        }
    }
}
