def call() {
    echo "processGradleProfile"

    sh """mkdir -p reports/gradle
          mv build/reports/profile/profile*.html build/reports/profile/profile.html 
          mv build/reports/profile/* reports/gradle"""
}