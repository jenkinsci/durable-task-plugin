def builds = [:]
builds['linux-with-docker-wrapper'] = { buildPlugin(platforms: ['docker']) }
builds['windows-wrapper'] = {
    withEnv(['SKIP_DURABLE_TASK_BINARY_GENERATION=true']) {
        buildPlugin(useAci: true, platforms: ['windows'])
    }
}
parallel builds
