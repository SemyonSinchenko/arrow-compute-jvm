package io.github.semyonsinchenko.arrowcompute.build;

public record BuildInfraRequest(String jiraId, String requirementSource, boolean includeJmh) {
}
