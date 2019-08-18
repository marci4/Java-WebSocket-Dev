workflow "Continuous Integration" {
  on = "push"
  resolves = ["GitHub Action for Maven"]
}

action "Build Project" {
  uses = "LucaFeger/action-maven-cli@765e218a50f02a12a7596dc9e7321fc385888a27"
  runs = "-DskipTests package --file pom.xml"
}

action "Test project" {
  uses = "LucaFeger/action-maven-cli@765e218a50f02a12a7596dc9e7321fc385888a27"
  needs = ["Build Project"]
  runs = "test --file pom.xml"
}

action "GitHub Action for Maven" {
  uses = "LucaFeger/action-maven-cli@765e218a50f02a12a7596dc9e7321fc385888a27"
  needs = ["Test project"]
  runs = "-Dmaven.test.failure.ignore=true test sonar:sonar -Dsonar.junit.reportPaths='target/surefire-reports' -Dsonar.organization=marci4-github -Dsonar.host.url=https://sonarcloud.io --file pom.xml "
  secrets = ["SONAR_TOKEN", "GITHUB_TOKEN"]
}
