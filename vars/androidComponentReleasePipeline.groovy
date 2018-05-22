#!/usr/bin/env groovy

def call(params) {

    pipeline {
        agent {
            node {
                label params.agent
            }
        }

        stages {
            stage('Checkout') {
                steps {
                    checkoutSubmodule()
                }
            }

            stage('Configure') {
                steps {
                    cleanSteps()
                    rewriteGradleProperties()
                }
            }

            stage("Build") {
                steps {
                    buildProject(params.publishCommand)
                }
            }

            stage("Release") {
                steps {
                    releaseProject(params)
                }
            }
        }
    }
}

private void buildProject(command) {
    echo "Running Release Build"
    sh "./gradlew ${command} --profile --warn"
}


import publishModule

private void releaseProject(params) {
    gitMessage = "sign off" // sh(script: "git log -2 --format=\"%s\"", returnStdout: true).trim()
    if ("${gitMessage}".toLowerCase().startsWith("sign off")) {
        def report = params.modules.collect {
            "module file for ${it} exists = ${fileExists(it + "/module.properties")}"
        }
        println("""Module check
                   ${report}""")

        params.modules.each {
            def module = readProperties file: it + "/module.properties"
            module["repo"] = params.repo.relese
            publishModule module
        }
    } else {
        echo """To make release, write "sign off" as commit message"""
    }
}

