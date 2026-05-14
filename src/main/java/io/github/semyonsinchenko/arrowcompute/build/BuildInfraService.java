package io.github.semyonsinchenko.arrowcompute.build;

public interface BuildInfraService {
    BuildInfraResponse provision(BuildInfraRequest request);
}
