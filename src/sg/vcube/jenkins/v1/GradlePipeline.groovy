package sg.vcube.jenkins.v1

def execute(modules, options = ["unstable"]) {
    if (BRANCH_NAME.startsWith("release/")) {
        properties([
                buildDiscarder(logRotator(artifactDaysToKeepStr: "", artifactNumToKeepStr: "", daysToKeepStr: "", numToKeepStr: "10")),
                disableConcurrentBuilds(),
        ])
    } else {
        properties([
                buildDiscarder(logRotator(artifactDaysToKeepStr: "", artifactNumToKeepStr: "", daysToKeepStr: "", numToKeepStr: "10")),
                disableConcurrentBuilds(),
                pipelineTriggers([cron("H H(18-23) * * *")])
        ])
    }

    if (BRANCH_NAME.startsWith("release/")) {
        release = true
        try {
            stage("Checkout") {
                checkout scm
                sh "git submodule update --init --recursive"
                gitMessage = sh(script: "git log -2 --format=\"%s\"", returnStdout: true).trim()
            }

            stage("Build All") {
                def GRADLE_HOME = tool name: "Gradle", type: "gradle"
                sh "$GRADLE_HOME/bin/gradle compilePublications"
            }

            stage("Release All") {
                if ("${gitMessage}".toLowerCase().startsWith("sign off")) {
                    modules.each {
                        publishModule(it.key, it.value)
                    }
                } else {
                    echo """To make release, write "sign off" as commit message"""
                }
            }
        } catch (exception) {
            def writer = new StringWriter()
            exception.printStackTrace(new PrintWriter(writer))

            echo "${exception.message}\n${writer.toString()}"

            jobFailed = true
            jobComment = "Failed due to ${exception.message}"
            currentBuild.result = "FAILED"
        }
    } else {
        stage("Checkout") {
            checkout scm
            sh "git submodule update --init --recursive"
        }

        stage("Build") {
            def gradleProperties = new java.util.Properties()
            if (fileExists("gradle.properties")) {
                def properties = readProperties file: "gradle.properties"
                gradleProperties.putAll(properties)
            }

            sh """rm -rf build/reports/profile/ | cat"""

            gradleProperties["org.gradle.caching"] = "true"
            gradleProperties["org.gradle.configureondemand"] = "false"
            gradleProperties["org.gradle.daemon"] = "true"
            gradleProperties["org.gradle.parallel"] = "true"

            def stringWriter = new StringWriter()
            gradleProperties.store(stringWriter, "Jenkins gradle.properties")
            def text = stringWriter.buffer.toString()
            stringWriter = null

            writeFile text: text, file: "gradle.properties"

            def GRADLE_HOME = tool name: "Gradle", type: "gradle"
            if (["develop"].contains(BRANCH_NAME.toString())) {
                def buildMode = "integration"
                if (options.contains("unstable")) {
                    buildMode = ",unstable"
                }

                sh "$GRADLE_HOME/bin/gradle build -PbuildMode=${buildMode} --profile --warn"
            } else {
                sh "$GRADLE_HOME/bin/gradle build --profile --warn"
            }
        }

        if (["master", "develop"].contains(BRANCH_NAME.toString())) {
            stage("Analysis") {
                try {
                    def GRADLE_HOME = tool name: "Gradle", type: "gradle"
                    sh "$GRADLE_HOME/bin/gradle sonarqube --warn -Dsonar.branch=${BRANCH_NAME}"
                } catch (exception) {
                    def writer = new StringWriter()
                    exception.printStackTrace(new PrintWriter(writer))

                    echo "${exception.message}\n${writer.toString()}"
                }
            }
        }

        stage("Check Updates") {
            def GRADLE_HOME = tool name: "Gradle", type: "gradle"
            sh "$GRADLE_HOME/bin/gradle dependencyUpdates -DoutputFormatter=json -Drevision=release --warn"

            def html = """<html><style>
                            table {
                                border-collapse: collapse;
                            }
                        
                            table, th, td {
                                border: 1px solid black;
                            }
                        
                            td {
                                padding: 4px;
                            }
                          </style><body>"""
            def pkgAndroid = ""
            def pkgOthers = ""

            modules.each {
                def text = readFile file: "${it.value.path}/build/dependencyUpdates/report.json"
                println(text)

                report = readJSON text: text
                println(report)

                html += "<h3>" + it.key + "</h3>"
                html += """<table>
                           <tr><td>Package</td><td>Artifact</td><td>Current</td><td>Available</td></tr>"""
                for (data in report.outdated.dependencies) {
                    switch (data.group) {
                        case "com.android.support":
                            pkgAndroid += "<tr><td>" + String.valueOf(data.group) + "</td><td>" + String.valueOf(data.name) + "</td><td>" + String.valueOf(data.version) + "</td><td>" + String.valueOf(data.available.release) + "</td></tr>"
                            break

                        default:
                            pkgOthers += "<tr><td>" + String.valueOf(data.group) + "</td><td>" + String.valueOf(data.name) + "</td><td>" + String.valueOf(data.version) + "</td><td>" + String.valueOf(data.available.release) + "</td></tr>"
                    }
                    data = null
                }
                report = null

                html += pkgAndroid + pkgOthers
                html += "</table>"
            }
            html += "</body></html>"

            writeFile text: html, file: "reports/dependency-update.html"
            sh """rm -rf ./reports | cat
                  mkdir -p reports/gradle
                  mv build/reports/profile/profile*.html build/reports/profile/profile.html 
                  mv build/reports/profile ./reports
                  mv reports/profile ./reports/gradle"""
            publishHTML([
                    allowMissing         : false,
                    alwaysLinkToLastBuild: false,
                    keepAll              : false,
                    reportDir            : "reports",
                    reportFiles          : "dependency-update.html, gradle/profile.html",
                    reportName           : "Job Reports",
                    reportTitles         : ""
            ])
        }
    }
}

def publishModule(name, module) {
    def path = module.root

    def build = readProperties file: module.path + "/module.properties"
    def group = build.group.replaceAll("\\.", "/")
    def version = build.version

    echo """Path = ${path}
            Group = ${group}
            Id = ${name}
            Version = ${version}""".stripMargin().stripIndent()

    uploadSpec = """{
                          "files": [
                             {
                              "pattern": "${path}/build/libs/(${name}-${version}-*)",
                              "target": "libs-android-gate/${group}/${name}/${version}/{1}"
                             },
                             {
                              "pattern": "${path}/build/publications/projectRelease/pom-default.xml",
                              "target": "libs-android-gate/${group}/${name}/${version}/${
        name
    }-${version}.pom"
                             },
                             {
                              "pattern": "${path}/build/**/(${name}-${version}.*)",
                              "target": "libs-android-gate/${group}/${name}/${version}/{1}"
                             }
                          ]
                        }"""

    def server = Artifactory.server "REPO"

    def publishInfo = server.upload spec: uploadSpec
    server.publishBuildInfo publishInfo
}
