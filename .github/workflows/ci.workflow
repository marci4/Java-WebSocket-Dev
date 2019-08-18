workflow "Continuous Integration" {
  on = "push"
  resolves = ["SonarQube"]
}

action "Build" {
  uses = "LucaFeger/action-maven-cli@765e218a50f02a12a7596dc9e7321fc385888a27"
  runs = "mvn -DskipTests package --file pom.xml"
}

action "Test" {
  uses = "LucaFeger/action-maven-cli@765e218a50f02a12a7596dc9e7321fc385888a27"
  needs = ["Build"]
  runs = "mvn test --file pom.xml"
}

action "SonarQube" {
  uses = "LucaFeger/action-maven-cli@765e218a50f02a12a7596dc9e7321fc385888a27"
  needs = ["Test"]
  runs = "sonar:sonar -Dsonar.junit.reportPaths='target/surefire-reports' -Dsonar.organization=marci4-github -Dsonar.host.url=https://sonarcloud.io -Dsonar.login=${{SONAR_TOKEN}} --file pom.xml"
  secrets = ["SONAR_TOKEN"]
}
