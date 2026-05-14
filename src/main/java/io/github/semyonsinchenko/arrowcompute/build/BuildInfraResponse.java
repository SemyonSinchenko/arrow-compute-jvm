package io.github.semyonsinchenko.arrowcompute.build;

import java.util.List;

public record BuildInfraResponse(
        boolean testRunnable,
        boolean jmhRunnable,
        boolean packageLayoutReady,
        List<String> verificationResults
) {
}
