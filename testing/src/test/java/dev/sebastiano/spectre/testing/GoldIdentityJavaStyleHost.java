package dev.sebastiano.spectre.testing;

import org.junit.Ignore;
import org.junit.Test;

/**
 * Ordinary JUnit 4 Java host: non-final by language default. Name-only inference must fail
 * closed; pass {@code getClass()} so the running class is the gold key.
 */
@Ignore("reflective fixture for Java gold identity")
public class GoldIdentityJavaStyleHost {
    @Test
    public GoldTestIdentity probe() {
        return ScreenshotGoldIdentityKt.inferTestIdentity(null);
    }

    @Test
    public GoldTestIdentity probeKeyed() {
        return ScreenshotGoldIdentityKt.inferTestIdentity(getClass());
    }
}
