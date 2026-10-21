package com.dukankhata.subscription;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayClientSignatureTest {
    private final RazorpayClient client = new RazorpayClient("test-key-id", "EnLs21M47BllR3X8PSFtjtbd");

    @Test
    void verifiesCheckoutSignatureUsingProviderDocumentedOrderAndPaymentTuple() {
        assertThat(client.verifySignature("order_IEIaMR65cu6nz3", "pay_IH4NVgf4Dreq1l",
                "0d4e745a1838664ad6c9c9902212a32d627d68e917290b0ad5f08ff4561bc50f"))
                .isTrue();
    }

    @Test
    void rejectsCheckoutSignatureForChangedPaymentOrMalformedHex() {
        assertThat(client.verifySignature("order_IEIaMR65cu6nz3", "pay_other",
                "0d4e745a1838664ad6c9c9902212a32d627d68e917290b0ad5f08ff4561bc50f"))
                .isFalse();
        assertThat(client.verifySignature("order_IEIaMR65cu6nz3", "pay_IH4NVgf4Dreq1l", "not-hex"))
                .isFalse();
    }

    @Test
    void verifiesWebhookSignatureOverExactRawPayloadAndRequiresSecret() throws Exception {
        String payload = "{\"id\":\"evt_example\",\"event\":\"payment.captured\"}";
        String signature = hmac(payload, "test-webhook-secret");

        assertThat(client.verifyWebhookSignature(payload, signature, "test-webhook-secret")).isTrue();
        assertThat(client.verifyWebhookSignature(payload + " ", signature, "test-webhook-secret")).isFalse();
        assertThat(client.verifyWebhookSignature(payload, signature, "")).isFalse();
    }

    private String hmac(String payload, String secret) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
