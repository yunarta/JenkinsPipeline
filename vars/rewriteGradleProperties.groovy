def call() {
    def gradle = new Properties()

    if (fileExists("gradle.properties")) {
        Properties properties = readProperties file: "gradle.properties"
        gradle.putAll(properties)
    }

    gradle["org.gradle.configureondemand"] = "false"
    gradle["org.gradle.parallel"] = "false"
    gradle["org.gradle.caching"] = "true"
    gradle["org.gradle.daemon"] = "true"

    def writer = new StringWriter()
    gradle.store(writer, "Jenkins gradle.properties")

    def text = writer.toString()
    writer = null

    writeFile text: text, file: "gradle.properties"
}
