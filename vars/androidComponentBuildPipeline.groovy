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