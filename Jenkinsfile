def builds = [:]
builds['docker'] = { buildPlugin(platforms: ['docker']) }
builds['windows'] = {
    node {
        withEnv(['SKIP_BINARY_GENERATION=true']) {
            buildPlugin(platforms: ['windows'])
        }
    }
}
parallel builds
