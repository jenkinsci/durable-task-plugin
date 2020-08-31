// TODO: Come up with a way to enable binary generation on Linux but not
// on Windows without needing to run buildPlugin multiple times to avoid
// https://github.com/jenkinsci/durable-task-plugin/pull/126#issuecomment-681964838.
withEnv(['SKIP_DURABLE_TASK_BINARY_GENERATION=true']) {
  buildPlugin(platforms: ['docker', 'maven-windows'])
}
