// The plugin must be built on a docker platform.
// TODO: remove skiptests if can't test manual like windows
buildPlugin(platforms: ['docker'], tests: [skip: true])

node('windows') {
    timeout(60) {
        stage ('Test windows') {
            bat 'dir /s'
            // checkout the repo again
            checkout scm
            bat 'dir /s'
            // Need compiled java jar. Because multiple jars are archived,
            // easier to get the hpi that contains the compiled jar
            unarchive mapping: ['**/*.hpi': 'durable-task.hpi']
            bat 'dir /s'
            unzip zipfile: 'durable-task.hpi', dir: 'target/hpi'
            bat 'dir /s'

            // unpack the jar with the compiled sources into target/classes
            unzip zipfile: 'target/hpi/WEB-INF/lib/durable-task.jar', dir: 'target/classes'
            bat 'dir /s'

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
