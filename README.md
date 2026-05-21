# arrow-compute

Java 25 build baseline for Arrow compute JVM scaffolding.

## Build and verification commands

- `./gradlew clean test`: compile and run smoke-level tests.
- `./gradlew check`: enforce build contract checks and test execution.
- `./gradlew jmh`: run benchmark harness wiring.
- `./gradlew jmh -PjmhInclude="..."`: run only benchmarks matching regex.
- `./gradlew jmh -PjmhParam.nullPercent=0`: run only `nullPercent=0` cells.

## JVM constraints

- Shared flags: `--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED`
- Test JVM property: `-Darrow.memory.debug.allocator=true`
