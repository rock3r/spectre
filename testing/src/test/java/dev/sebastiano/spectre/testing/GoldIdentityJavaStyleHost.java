package dev.sebastiano.spectre.testing;

import org.junit.Ignore;
import org.junit.Test;

/**
 * Ordinary JUnit 4 Java host: non-final by language default. Used reflectively to prove name-only
 * gold identity still resolves when the class is executed directly.
 */
@Ignore("reflective fixture for Java gold identity")
public class GoldIdentityJavaStyleHost {
    @Test
    public GoldTestIdentity probe() {
        return ScreenshotGoldIdentityKt.inferTestIdentity(null);
    }
}
