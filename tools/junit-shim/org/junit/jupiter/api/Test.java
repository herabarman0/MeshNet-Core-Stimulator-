package org.junit.jupiter.api;
import java.lang.annotation.*;
/** Offline stand-in for JUnit 5's @Test (see tools/junit-shim/README.md). Gradle builds use the real JUnit. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface Test { }
