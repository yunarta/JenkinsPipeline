node("java") {
    stage("Checkout") {
        deleteDir()
        sh "git clone https://github.com/emartynov/sonar-kotlin.git ."
    }

    stage("Build") {
        sh "./gradlew build"
    }

    stage("Publish") {
        archiveArtifacts "build/libs/sonar-kotlin-*.jar"
    }
}