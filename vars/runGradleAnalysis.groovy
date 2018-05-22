def call(command) {
    sh "./gradlew ${command} --warn -Dsonar.branch=${BRANCH_NAME}"
}