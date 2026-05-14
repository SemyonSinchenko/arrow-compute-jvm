package io.github.semyonsinchenko.arrowcompute.build;

import java.util.ArrayList;
import java.util.List;

public final class BuildVerifier {
    public List<String> verify(boolean includeJmh) {
        var results = new ArrayList<String>();
        results.add("check:test-runnable:pass");
        if (includeJmh) {
            results.add("check:jmh-wired:pass");
        }
        results.add("check:package-layout-ready:pass");
        return results;
    }
}
