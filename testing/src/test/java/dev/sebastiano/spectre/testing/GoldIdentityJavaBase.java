package dev.sebastiano.spectre.testing;

import org.junit.Ignore;
import org.junit.Test;

/** Concrete Java base whose {@code @Test} can be inherited by {@link GoldIdentityJavaChild}. */
@Ignore("reflective fixture for inherited Java gold identity")
public class GoldIdentityJavaBase {
    @Test
    public GoldTestIdentity inheritedFromJavaBase() {
        return ScreenshotGoldIdentityKt.inferTestIdentity(null);
    }
}
