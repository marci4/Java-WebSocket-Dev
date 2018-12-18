workflow "New workflow" {
  on = "push"
  resolves = ["docker://maven:3.6.0-jdk-8"]
}

action "docker://maven:3.6.0-jdk-8" {
  uses = "docker://maven:3.6.0-jdk-8"
  runs = "mvn -build"
}
