package dev.doughbay.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalJsonTest {

    @Test
    void sortsObjectKeysAndNormalizesNumbersRecursively() {
        String left = "{ \"z\": 1.00, \"a\": {\"two\":2e0,\"one\":1}, \"v\":[3, 2] }";
        String right = "{\"v\":[3.0,2.00],\"a\":{\"one\":1.0,\"two\":2},\"z\":1}";

        assertEquals(CanonicalJson.canonicalize(left), CanonicalJson.canonicalize(right));
        assertEquals("{\"a\":{\"one\":1,\"two\":2},\"v\":[3,2],\"z\":1}",
                CanonicalJson.canonicalize(left));
    }

    @Test
    void rejectsAmbiguousDuplicateKeysAndLimitViolations() {
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalJson.canonicalize("{\"id\":1,\"id\":2}"));
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalJson.canonicalize("[[[0]]]", new CanonicalJson.Limits(2, 10, 100)));
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalJson.canonicalize("[1,2,3]", new CanonicalJson.Limits(4, 2, 100)));
    }
}
