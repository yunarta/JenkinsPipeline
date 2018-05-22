#!/usr/bin/env groovy

def call(params) {

    pipeline {
        agent {
            node {
                label params.agent
            }
        }

        triggers {
            upstream(upstreamProjects: params.upstreamProjects, threshold: hudson.model.Result.SUCCESS)
        }

        stages {
            stage('Checkout') {
                steps {
                    checkoutSubmodule()
                }
            }

            stage('Configure') {
                steps {
                    sh "printenv"
                    cleanSteps()
                    rewriteGradleProperties()
                }
            }

            stage("Build") {
                steps {
                    buildProject()
                }
            }

            stage("Analysis") {
                when {
                    expression { !params.analyzeCommand.isEmpty() }
                }

                steps {
                    runGradleAnalysis params.analyzeCommand
                }
            }

            stage("Report") {
                steps {
                    processGradleProfile()
                    publishHTML([
                            allowMissing         : false,
                            alwaysLinkToLastBuild: false,
                            keepAll              : false,
                            reportDir            : "reports",
                            reportFiles          : "gradle/profile.html",
                            reportName           : "Job Reports",
                            reportTitles         : ""
                    ])
                }
            }

            stage("Publish Snapshot") {
                steps {
                    buildProjectSnapshot(params.publishCommand)
                    snapshotProject(params)
                }
            }

        }

        post {
            success {
                androidLint canComputeNew: false, defaultEncoding: "", healthy: "", pattern: "**/reports/lint-results*.xml", unHealthy: ""
            }
        }
    }
}

private void buildProject() {
    if (BRANCH_NAME == "develop") {
        echo "Running Integration Build"

        def buildMode = "integration"
        if (params.options != null && params.options.contains("unstable")) {
            buildMode = ",unstable"
        }

        sh "./gradlew build -PbuildMode=${buildMode} --profile --warn"
    } else {
        echo "Running Stable Build"

        sh "./gradlew build --profile --warn"
    }
}

import publishModule

private void buildProjectSnapshot(command) {
    echo "Running Release Build"
    sh "./gradlew ${command} --profile --warn"
}

private void snapshotProject(params) {
    gitMessage = "sign off" // sh(script: "git log -2 --format=\"%s\"", returnStdout: true).trim()
    if ("${gitMessage}".toLowerCase().startsWith("sign off")) {
        def report = params.modules.collect {
            "module file for ${it} exists = ${fileExists(it + "/module.properties")}"
        }
        println("""Module check
                   ${report}""")

        params.modules.each {
            def module = readProperties file: it + "/module.properties"
            module["repo"] = params.repo.snapshot
            publishModule module
        }
    } else {
        echo """To make release, write "sign off" as commit message"""
    }
}

