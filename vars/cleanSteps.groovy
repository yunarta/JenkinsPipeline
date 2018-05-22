def call() {
    echo "cleanSteps"

    sh """rm -rf build/reports/profile
          rm -rf reports/gradle"""
}
