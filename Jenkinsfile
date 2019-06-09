// The plugin must be built on a docker platform.
// TODO: remove skiptests if can't test manual like windows
buildPlugin(platforms: ['docker'], tests: [skip: true])

node('windows') {
    timeout(60) {
        stage ('Test windows') {
            bat 'java -h'
            bat 'echo %JAVA_HOME%'
            bat 'dir %JAVA_HOME%'
            bat 'dir %JAVA_HOME%\\bin'
            // checkout the repo again
            checkout scm
            bat 'dir'
            // Need compiled java jar. Because multiple jars are archived,
            // easier to get the hpi that contains the compiled jar
            unarchive mapping: ['**/*.hpi': 'durable-task.hpi']
            bat 'dir'
            dir ('target/hpi') {
                bat 'move ..\\..\\durable-task.hpi %cd%'
                bat 'dir'
//                bat 'jar -xvf durable-task.hpi'
                bat 'unzip durable-task.jar'
                bat 'dir'
            }
            bat 'dir'
            // unpack the jar with the compiled sources into target/classes
            dir ('target/classes') {
                bat 'xcopy ..\\hpi\\WEB-INF\\lib\\durable-task.jar %cd%'
                bat 'dir'
//                bat 'jar -xvf durable-task.jar'
                bat 'unzip durable-task.jar'
                bat 'dir'
            }

            // compile only test code and run tests against compiled source
            bat """
                    mvn resources:testResources
                    mvn compiler:testCompiler
                    mvn surefire:test
                """

            // record test results
            junit '**/target/surefire-reports/**/*.xml'
        }
    }
}
