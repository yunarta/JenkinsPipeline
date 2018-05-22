#!/usr/bin/env groovy

def call(body) {
    // evaluate the body block, and collect configuration into the object
    params = [
            debug           : false,
            option          : [],
            repo            : "",
            analyzeCommand  : "",
            publishCommand  : "worksGeneratePublication",
            upstreamProjects: null,
            modules         : []
    ]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = params
    body()

    String branchName = String.valueOf(BRANCH_NAME)
    if (branchName.startsWith("release/")) {
        androidComponentReleasePipeline params
    } else if (branchName.startsWith("develop")) {
        androidComponentDevelopPipeline params
    } else {
        androidComponentBuildPipeline params
    }
}