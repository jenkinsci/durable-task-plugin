// The plugin must be built on a docker platform.
// TODO: remove skiptests if can't test manual like windows
buildPlugin(platforms: ['docker'], tests: [skip: true])

node('windows') {
    timeout(60) {
        stage ('Test windows') {
            // checkout the repo again
            checkout scm
            // Need compiled java jar. Because multiple jars are archived,
            // easier to get the hpi that contains the compiled jar
            unarchive mapping: ['org/jenkins-ci/plugins/durable-task/*.hpi': 'durable-task.hpi']
            unzip zipfile: 'durqble-task.hpi', dir: 'target/hpi'

            // unpack the jar with the compiled sources into target/classes
            unzip zipfile: 'target/hpi/WEB-INF/lib/durable-task.jar', dir: 'target/classes'

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
