package io.github.semyonsinchenko.arrowcompute.compute.codegen;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import org.codehaus.janino.SimpleCompiler;

/**
 * Probe-only Janino loader for static computeAll kernels.
 *
 * <p>Operation scope: compile project-owned source and resolve one static entrypoint.
 * Null policy: delegated to compiled kernel. Lifecycle/aliasing: caller owns all Arrow buffers
 * backing MemorySegment args and keeps them live through invocation. Non-goals: no caching,
 * registry, expression DSL, or user-provided source handling.</p>
 */
public final class JaninoLoader {
    public MethodHandle compileToHandle(String source, String className, String methodName) {
        var expectedType = MethodType.methodType(
                void.class,
                MemorySegment.class,
                MemorySegment.class,
                MemorySegment.class,
                int.class
        );
        return compileToHandle(source, className, methodName, expectedType);
    }

    public MethodHandle compileToHandle(String source, String className, String methodName, MethodType expectedType) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(className, "className must not be null");
        Objects.requireNonNull(methodName, "methodName must not be null");
        Objects.requireNonNull(expectedType, "expectedType must not be null");

        try {
            var compiler = new SimpleCompiler();
            compiler.cook(source);
            var loader = compiler.getClassLoader();
            var dynamicClass = loader.loadClass(className);
            return MethodHandles.lookup().findStatic(dynamicClass, methodName, expectedType);
        } catch (CodeGenProbeException ex) {
            throw ex;
        } catch (ReflectiveOperationException ex) {
            throw new CodeGenProbeException(
                    "CODEGEN-LINK-001",
                    "Failed to resolve compiled kernel method: " + ex.getClass().getSimpleName(),
                    ex
            );
        } catch (RuntimeException ex) {
            throw new CodeGenProbeException(
                    "CODEGEN-COMPILE-001",
                    "Failed to compile dynamic kernel source: " + ex.getClass().getSimpleName(),
                    ex
            );
        } catch (Exception ex) {
            throw new CodeGenProbeException(
                    "CODEGEN-COMPILE-002",
                    "Unexpected checked failure during dynamic compilation: " + ex.getClass().getSimpleName(),
                    ex
            );
        }
    }
}
