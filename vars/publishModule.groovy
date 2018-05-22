def call(map) {
    def group = map.group.replaceAll("\\.", "/")
    def version = map.version
    def artifact = map.name
    def repo = map.repo

    println("""Publishing ${group}:${artifact}:${version}""")
    uploadSpec = """{
                          "files": [
                             {
                              "pattern": "${artifact}/build/libs/(${artifact}-${version}*)",
                              "target": "${repo}/${group}/${artifact}/${version}/{1}"
                             }
                          ]
                        }"""

    def server = Artifactory.server "REPO"

    def publishInfo = server.upload spec: uploadSpec
    server.publishBuildInfo publishInfo
}
