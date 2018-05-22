def call() {
    if (new File(".gitmodules").exists()) {
        echo "Checking out submodule"
        sh "git submodule update --init --recursive"
    } else {
        echo "No submodule found"
    }
}