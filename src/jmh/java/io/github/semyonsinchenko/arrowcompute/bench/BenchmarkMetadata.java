package io.github.semyonsinchenko.arrowcompute.bench;

public record BenchmarkMetadata(
        String className,
        String benchmarkId,
        String layer,
        String type,
        int rows,
        int nullProfile,
        String question,
        String baseline,
        String outputAllocationPolicy
) {
    public String toJsonLine() {
        return "{\"className\":\"" + className
                + "\",\"benchmarkId\":\"" + benchmarkId
                + "\",\"layer\":\"" + layer
                + "\",\"type\":\"" + type
                + "\",\"rows\":" + rows
                + ",\"nullProfile\":" + nullProfile
                + ",\"question\":\"" + question
                + "\",\"baseline\":\"" + baseline
                + "\",\"outputAllocationPolicy\":\"" + outputAllocationPolicy
                + "\"}";
    }
}
