package sg.vcube.jenkins

def execute() {
    properties([
            buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '10')),
            disableConcurrentBuilds(),
            pipelineTriggers([cron('H H(18-23) * * *')])
    ])

    project = [
            swiftVersion     : "4.0",
            notifyUpsource   : false,
            runStaticAnalysis: false,
            waitForRelease   : false,
            language         : ""
    ]

    scmSpec = [
            sourceBranch: "develop"
    ]

    def faults = [:]
    def tests = [
            "version correctness",
            "source correctness"
    ]

    def jobFailed = false
    def jobComment = ""
    deleteDir()

    try {
        stage("Checkout") {
            dir("pod") {
                checkout scm
                gitCommit = sh(script: "git rev-parse HEAD", returnStdout: true,).trim()
                gitMessage = sh(script: "git log -2 --format=\"%s\"", returnStdout: true).trim()
                shortCommit = gitCommit.take(6)

                def config = readYaml file: "Jenkinsfile.yaml"
                pod = [
                        spec   : config.pod.spec,
                        source : config.pod.source,
                        project: config.pod.project,
                ]

                project.swiftVersion = config.project.swiftVersion
                project.notifyUpsource = config.project.notifyUpsource
                project.runStaticAnalysis = config.project.runStaticAnalysis
                project.language = config.project.language

                scmSpec.sourceBranch = config.scm.sourceBranch

                privateRepo = [
                        name: config.privateRepo.name,
                        url : config.privateRepo.url
                ]

                upsource = [
                        name   : "${JOB_NAME}",
                        project: "${pod.project}",
                        key    : "${BUILD_TAG}",
                        url    : "${RUN_DISPLAY_URL}",
                ]
            }

            if (project.notifyUpsource) {
                build job: "upsource/upsource-notify-build",
                        parameters: [
                                string(name: "KEY", value: "${upsource.key}"),
                                string(name: "URL", value: "${upsource.url}"),
                                string(name: "NAME", value: "${upsource.name}"),
                                string(name: "PROJECT", value: "${upsource.project}"),
                                string(name: "COMMIT", value: gitCommit),
                                string(name: "STATE", value: "in_progress")
                        ], wait: false
            }
        }

        stage("Prepare") {
            dir("pod") {
                sh "pod repo add ${privateRepo.name} ${privateRepo.url} | pod repo update ${privateRepo.name} | cat"

                String podSpec = readFile file: pod.spec
                def matcher = podSpec =~ /(?ms).*s\.version.*=.*['"](?<version>\d+.\d+.\d+)['"].*/
                version = matcher.matches() ? matcher.group('version').toString() : null
                matcher = null

                if (version == null) {
                    faults["version correctness"] = podSpec
                }

                matcher = podSpec =~ /.*(:tag => ['"]v['"] \+ s\.version\.to_s)/
                def foundMatches = matcher.find()
                matcher = null

                if (!foundMatches) {
                    faults["source correctness"] = podSpec
                }
            }
        }

        stage("Lint") {
            if (faults.isEmpty()) {
                dir("project.git") {
                    deleteDir()
                    sh "git init --bare"
                }

                dir("pod") {
                    lintRepo = "$WORKSPACE/project.git"
                    if (sh(script: "git remote -v | grep cocoapod | cat", returnStdout: true) == "") {
                        sh "git remote add cocoapod ${lintRepo}"
                    }

                    def podRepo = pod.source.replaceAll("/", "\\\\/")
                    def lintRepo = "${lintRepo}".replaceAll("/", "\\\\/")

                    def podSpecTag = ":tag => [\\\"']v[\\\"'] + s.version.to_s"
                    def lintSpecTag = ":commit => \\\"${gitCommit}\\\""

                    sh """sed -ie "s/${podSpecTag}/${lintSpecTag}/" ${pod.spec}
                      sed -ie "s/${podRepo}/${lintRepo}/" ${pod.spec}
                      git add ${pod.spec}
                      git commit -m "Podspec lint"
                      git branch ${BRANCH_NAME}
                      git checkout ${BRANCH_NAME}
                      git push cocoapod HEAD:${BRANCH_NAME}
                      rm ${pod.spec}e
                      pod spec lint ${pod.spec} \
                          --sources="https://github.com/CocoaPods/Specs,${privateRepo.name}" \
                          --swift-version=${project.swiftVersion} --allow-warnings --private"""
                }
            } else {
                echo "Skipping stage due to faults"
            }
        }

        stage("Static Analysis") {
            try {
                dir("pod") {
                    if (project.runStaticAnalysis) {
                        def hasDotSwiftLintYml = fileExists(".swiftlint.yml")
                        if ((hasDotSwiftLintYml || fileExists("swiftlint.yml"))) {
                            def swiftLintYml = hasDotSwiftLintYml ? ".swiftlint.yml" : "swiftlint.yml"
                            sh """sed -e 's/xcode/checkstyle/' ${swiftLintYml} > checkstyle.yml
                                  rmdir report | cat
                                  mkdir report
                                  swiftlint version
                                  swiftlint --config checkstyle.yml > report/lintreport.xml | cat"""
                            checkstyle canComputeNew: false, defaultEncoding: "", healthy: "", pattern: "report/lintreport.xml", unHealthy: ""
                        }

                        if ([scmSpec.sourceBranch, "master"].contains(BRANCH_NAME.toString())) {
                            def exists = fileExists("sonar-project.properties")
                            if (exists) {
                                if (fileExists("pre-analyze.sh")) {
                                    sh """chmod +x pre-analyze.sh
                                          ./pre-analyze.sh"""
                                }

                                switch (project.language) {
                                    case "objc":
                                        def sonar = readProperties file: "sonar-project.properties"
                                        def workspace = sonar["sonar.objectivec.workspace"]
                                        def scheme = sonar["sonar.objectivec.appScheme"]

                                        sh """mkdir sonar-reports | cat
                                              xcodebuild -workspace ${workspace} -scheme ${scheme} \
                                                CLANG_ENABLE_MODULE_DEBUGGING=NO CODE_SIGN_IDENTITY="" CODE_SIGNING_REQUIRED=NO \
                                                ENABLE_BITCODE=NO COMPILER_INDEX_STORE_ENABLE=NO clean build | \
                                                tee xcodebuild.log | \
                                                xcpretty -r json-compilation-database --output compile_commands.json
                                              oclint-json-compilation-database -- -extra-arg=-Wno-everything \
                                                -rc LONG_LINE=250 -max-priority-1 100000 -max-priority-2 100000 -max-priority-3 100000 \
                                                -report-type pmd -o sonar-reports/oclint.xml"""

                                        pmd canComputeNew: false, defaultEncoding: "UTF-8", healthy: "", pattern: "sonar-reports/oclint.xml", unHealthy: ""
                                        writeFile file: "sonar-reports/TEST-report.xml", text: """<?xml version='1.0' encoding='UTF-8' standalone='yes'?><testsuites name='AllTestUnits'></testsuites>"""
                                        sh """sonar-scanner -Dsonar.projectVersion=${version}.${BUILD_NUMBER} \
                                                -Dsonar.upsource.revision=${gitCommit} \
                                                -Dsonar.branch=${BRANCH_NAME}"""
                                        break

                                    case "swift":
                                        if ((hasDotSwiftLintYml || fileExists("swiftlint.yml"))) {
                                            def swiftLintYml = hasDotSwiftLintYml ? ".swiftlint.yml" : "swiftlint.yml"
                                            sh """mkdir sonar-reports | cat
                                                  swiftlint --config ${swiftLintYml} > sonar-reports/swiftlint.txt | cat
                                                  sonar-scanner -Dsonar.projectVersion=${version}.${BUILD_NUMBER} \
                                                                -Dsonar.upsource.revision=${gitCommit} \
                                                                -Dsonar.branch=${BRANCH_NAME}"""
                                        }
                                        break

                                    default:
                                        break
                                }
                            } else {
                                echo "Not executing sonar scanner"
                            }
                        } else {
                            echo "Not analysis executing on branch ${BRANCH_NAME}"
                        }
                    } else {
                        echo "Static analysis not requested"
                    }
                }
            } catch (exception) {
                def writer = new StringWriter()
                exception.printStackTrace(new PrintWriter(writer))

                echo "${exception.message}\n${writer.toString()}"
            }
        }

        if ("${env.CHANGE_ID}" != "null" && "${env.CHANGE_BRANCH}".startsWith("release/")) {
            stage("Release") {
                if (version == null) {
                    currentBuild.result = "FAILED"
                } else {
                    def release = true
                    if (!project.waitForRelease) {
                        tests.add("automatic release")
                        tests.add("target correctness")

                        if (String.valueOf(CHANGE_TARGET) != "master") {
                            faults["target correctness"] = "To make release, create a pull request against master branch instead of "
                            release = false
                        }

                        def message = "${gitMessage}".readLines().last()
                        if ("${message}".toLowerCase().startsWith("sign off")) {
                            echo "Automatic release requested"
                        } else {
                            release = false
                            faults["automatic release"] = """Automatic release requested, but commit message is not "Sign off"
                                                         Commit message:
                                                         ${gitMessage}""".stripMargin()
                        }
                    } else {
                        input "Proceed with release version = ${version}?"
                    }

                    if (release) {
                        dir("pod") {
                            deleteDir()
                            checkout scm
                            tag = "v${version}"

                            sh """git remote set-url origin ${pod.source}
                              git remote set-url upstream ${pod.source}
                              git config --add remote.origin.fetch +refs/heads/${scmSpec.sourceBranch}:refs/remotes/origin/${scmSpec.sourceBranch}
                              git config --add remote.origin.fetch +refs/heads/${CHANGE_BRANCH}:refs/remotes/origin/${CHANGE_BRANCH}
                              git config --add remote.origin.fetch +refs/heads/${CHANGE_TARGET}:refs/remotes/origin/${CHANGE_TARGET}
                              git config --add remote.upstream.fetch +refs/heads/${CHANGE_BRANCH}:refs/remotes/upstream/${CHANGE_BRANCH}
                              git config --add remote.upstream.fetch +refs/heads/${CHANGE_TARGET}:refs/remotes/upstream/${CHANGE_TARGET}
                              git fetch
                              git checkout -b ${CHANGE_BRANCH} origin/${CHANGE_BRANCH}
                              git reset --hard
                              git checkout -b ${CHANGE_TARGET} origin/${CHANGE_TARGET}
                              git merge origin/${CHANGE_BRANCH} --no-ff -m "Merged in ${CHANGE_BRANCH} (pull request #${CHANGE_ID})"
                              git tag -a ${tag} -m "Release ${tag}"
                              git push origin --tags
                              git checkout -b ${scmSpec.sourceBranch} origin/${scmSpec.sourceBranch}
                              git merge origin/${CHANGE_BRANCH} --no-ff -m "Merged in ${scmSpec.sourceBranch} (pull request #${CHANGE_ID})"
                              pod repo push vcube ${pod.spec} \
                                  --sources="https://github.com/CocoaPods/Specs,${privateRepo.name}" \
                                  --swift-version=${project.swiftVersion} --allow-warnings --private
                              git push origin --all
                              git push origin --delete ${CHANGE_BRANCH}
                              """
                        }
                    }
                }
            }
        } else if (BRANCH_NAME.startsWith("release/")) {
            stage("Release Dry-run") {
                tests.add("automatic release")
                tests.add("release branch naming")
                tests.add("release branch exists")
                if (BRANCH_NAME.startsWith("release/v")) {
                    if (version == null) {
                        echo "Skipping stage due to faults"
                    } else {
                        dir("pod") {
                            deleteDir()
                            checkout scm

                            if ("release/v${version}" != String.valueOf(BRANCH_NAME)) {
                                faults["release branch naming"] = """Podspec version ${version} need to be release in the correct named branch""".stripMargin()
                            }

                            sh "git remote set-url origin ${pod.source}"
                            def gitTag = sh(script: "git ls-remote --tags | grep v${version} | cat", returnStdout: true).trim()
                            if (!"".equals(gitTag)) {
                                faults["release branch exists"] = """Podspec version ${version} is already being release""".stripMargin()
                            }

                            if (!project.waitForRelease) {
                                if (!"${gitMessage}".toLowerCase().startsWith("sign off")) {
                                    faults["automatic release"] = """Automatic release requested, but commit message is not "Sign off"
                                                         Commit message:
                                                         ${gitMessage}""".stripMargin()
                                }
                            }

                            tag = "v${version}"

                            CHANGE_BRANCH = BRANCH_NAME
                            CHANGE_TARGET = "master"

                            sh """git remote set-url origin ${pod.source}
                              git remote add upstream ${pod.source}
                              git config --add remote.origin.fetch +refs/heads/${scmSpec.sourceBranch}:refs/remotes/origin/${scmSpec.sourceBranch}
                              git config --add remote.origin.fetch +refs/heads/${CHANGE_BRANCH}:refs/remotes/origin/${CHANGE_BRANCH}
                              git config --add remote.origin.fetch +refs/heads/${CHANGE_TARGET}:refs/remotes/origin/${CHANGE_TARGET}
                              git config --add remote.upstream.fetch +refs/heads/${CHANGE_BRANCH}:refs/remotes/upstream/${CHANGE_BRANCH}
                              git config --add remote.upstream.fetch +refs/heads/${CHANGE_TARGET}:refs/remotes/upstream/${CHANGE_TARGET}
                              git fetch
                              git checkout -b ${CHANGE_BRANCH} origin/${CHANGE_BRANCH}
                              git reset --hard
                              git checkout -b ${CHANGE_TARGET} origin/${CHANGE_TARGET}
                              git merge origin/${CHANGE_BRANCH} --no-ff -m "Merged in ${CHANGE_BRANCH} (pull request #dry-run)"
                              git checkout -b ${scmSpec.sourceBranch} origin/${scmSpec.sourceBranch}
                              git merge origin/${CHANGE_BRANCH} --no-ff -m "Merged in ${scmSpec.sourceBranch} (pull request #dry-run)"
                              """
                        }
                    }
                } else {
                    faults["release branch naming"] = """Release branch must in start with v, example: v1.0.0"""
                }
            }
        }
    } catch (exception) {
        def writer = new StringWriter()
        exception.printStackTrace(new PrintWriter(writer))

        echo "${exception.message}\n${writer.toString()}"

        jobFailed = true
        jobComment = "Failed due to ${exception.message}"
        currentBuild.result = "FAILED"
    } finally {
        stage("Post Build") {
            if (currentBuild.result != "FAILED") {
                def testResult = """<testsuite name="" tests="${tests.size()}" skipped="0" failures="${faults.size()}" errors="0" time="0.0">"""
                tests.each {
                    testResult += """<testcase name="${it}" classname="pipeline.tests" time="0.0">"""
                    def fault = faults.get(it)
                    if (fault != null) {
                        testResult += """<failure message="" type="assertion error"><![CDATA[${fault}]]></failure>"""
                    }

                    testResult += """</testcase>"""
                }
                testResult += """</testsuite>"""
                if (!faults.isEmpty()) {
                    currentBuild.result = "UNSTABLE"
                }

                writeFile file: "test-reports/job-tests.xml", text: testResult
                junit allowEmptyResults: true, testResults: "test-reports/*.xml"
            }

            if (project.notifyUpsource) {
                build job: "upsource/upsource-notify-build",
                        parameters: [
                                string(name: "KEY", value: "${upsource.key}"),
                                string(name: "URL", value: "${upsource.url}"),
                                string(name: "NAME", value: "${upsource.name}"),
                                string(name: "PROJECT", value: "${upsource.project}"),
                                string(name: "COMMIT", value: gitCommit),
                                string(name: "STATE", value: jobFailed ? "failed" : "success"),
                                string(name: "DESCRIPTION", value: jobComment)
                        ], wait: false
            }
        }

        deleteDir()
    }
}
