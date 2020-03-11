def builds = [:]
builds['docker'] = { buildPlugin(platforms: ['docker']) }
builds['windows'] = {
    withEnv(['SKIP_DURABLE_TASK_BINARY_GENERATION=true']) {
        buildPlugin(platforms: ['windows'])
    }
}
parallel builds