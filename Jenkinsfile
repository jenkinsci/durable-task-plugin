// The plugin must be built on a docker platform.
// TODO: remove skiptests if can't test manual like windows
buildPlugin(platforms: ['docker'], tests: [skip: true])


node('windows') {
    timeout(60) {
        stage ('Test windows') {

            // checkout the repo again
            checkout scm
            bat 'dir'
            // Need compiled java jar. Because multiple jars are archived,
            // easier to get the hpi that contains the compiled jar
            unarchive mapping: ['**/*.hpi': 'durable-task.hpi']
            bat 'dir'
            List<String> env = [
                    "JAVA_HOME=${tool 'jdk8'}",
                    'PATH+JAVA=${JAVA_HOME}/bin',
                    "PATH+MAVEN=${tool 'mvn'}/bin"
            ]
            String commands = """
                                echo %JAVA_HOME%
                                dir /s %JAVA_HOME%
                                %JAVA_HOME%\\java -h
                                %JAVA_HOME%\\jar -h
                                dir
                                mkdir target\\hpi
                                mkdir target\\classes
                                move durable-task.hpi target\\hpi
                                chdir target\\hpi
                                dir
                                %JAVA_HOME%\\jar -xvf durable-task.hpi
                                dir
                                xcopy WEB-INF\\lib\\durable-task.jar ..\\classes 
                                chdir ..\\classes
                                dir
                                %JAVA_HOME%\\jar -xvf durable-task.jar
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
