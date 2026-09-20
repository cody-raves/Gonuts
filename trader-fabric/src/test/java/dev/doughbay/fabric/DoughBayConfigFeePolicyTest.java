package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoughBayConfigFeePolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void missingFeeFieldsRemainValidButUnconfirmed() {
        DoughBayConfig.FeePolicy policy =
                DoughBayConfig.parseFeePolicy(JSON.createObjectNode());

        assertTrue(policy.valid());
        assertFalse(policy.confirmed());
        assertEquals(0L, policy.fees().listingFeeFlat());
        assertEquals(0.0, policy.fees().listingFeePercent());
        assertEquals(0.0, policy.fees().saleTaxPercent());
    }

    @Test
    void explicitlyConfirmedZeroFeesAreDistinguishableFromUnknownFees() {
        DoughBayConfig.FeePolicy policy =
                DoughBayConfig.parseFeePolicy(confirmedFees(0, 0.0, 0.0));

        assertTrue(policy.valid());
        assertTrue(policy.confirmed());
        assertEquals(0L, policy.fees().listingFeeFlat());
        assertEquals(0.0, policy.fees().listingFeePercent());
        assertEquals(0.0, policy.fees().saleTaxPercent());
    }

    @Test
    void confirmedNonzeroFeesParseExactly() {
        DoughBayConfig.FeePolicy policy =
                DoughBayConfig.parseFeePolicy(confirmedFees(250, 1.5, 4.25));

        assertTrue(policy.valid());
        assertTrue(policy.confirmed());
        assertEquals(250L, policy.fees().listingFeeFlat());
        assertEquals(1.5, policy.fees().listingFeePercent());
        assertEquals(4.25, policy.fees().saleTaxPercent());
    }

    @Test
    void confirmationRequiresEveryExplicitFeeField() {
        for (String field : List.of(
                "listingFeeFlat", "listingFeePercent", "saleTaxPercent")) {
            ObjectNode root = confirmedFees(0, 0.0, 0.0);
            root.remove(field);
            assertLocked(DoughBayConfig.parseFeePolicy(root));
        }
    }

    @Test
    void malformedOrUnsafeFeesLockPolicy() {
        List<Consumer<ObjectNode>> invalidMutations = List.of(
                root -> root.put("auctionFeesConfirmed", "true"),
                root -> root.put("listingFeeFlat", -1),
                root -> root.set("listingFeeFlat",
                        JSON.getNodeFactory().numberNode(
                                new BigInteger("1000000000000001"))),
                root -> root.put("listingFeePercent", "1.0"),
                root -> root.set("saleTaxPercent", DoubleNode.valueOf(Double.NaN)),
                root -> root.put("saleTaxPercent", -0.1),
                root -> root.put("listingFeePercent", 100.0),
                root -> {
                    root.put("listingFeePercent", 60.0);
                    root.put("saleTaxPercent", 40.0);
                });

        for (Consumer<ObjectNode> mutation : invalidMutations) {
            ObjectNode root = confirmedFees(0, 0.0, 0.0);
            mutation.accept(root);
            assertLocked(DoughBayConfig.parseFeePolicy(root));
        }
    }

    private static ObjectNode confirmedFees(long flat, double listing, double tax) {
        ObjectNode root = JSON.createObjectNode();
        root.put("auctionFeesConfirmed", true);
        root.put("listingFeeFlat", flat);
        root.put("listingFeePercent", listing);
        root.put("saleTaxPercent", tax);
        return root;
    }

    private static void assertLocked(DoughBayConfig.FeePolicy policy) {
        assertFalse(policy.valid());
        assertFalse(policy.confirmed());
        assertEquals(0L, policy.fees().listingFeeFlat());
        assertEquals(0.0, policy.fees().listingFeePercent());
        assertEquals(0.0, policy.fees().saleTaxPercent());
    }
}
