def call() {
    causeMap = [
            hudson.model.Cause$UserIdCause.class                       : "user",
            hudson.model.Cause$UpstreamCause.class                     : "upstream",
            hudson.model.Cause$RemoteCause.class                       : "remote",
            org.jenkinsci.plugins.workflow.cps.replay.ReplayCause.class: "replay"
    ]

    return currentBuild.rawBuild.causes.collect {
        causeMap[it]
    }
}
