package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.creatorstore.security.Signatures;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SignaturesTest {
  @Test
  void verifiesRazorpayStyleHmacWithoutTimingSensitiveStringComparison() {
    String signature = Signatures.hmac("test-secret", "order_1|pay_1".getBytes(StandardCharsets.UTF_8));
    assertThat(Signatures.verifyHexHmac("test-secret", "order_1|pay_1", signature)).isTrue();
    assertThat(Signatures.verifyHexHmac("test-secret", "order_1|pay_2", signature)).isFalse();
  }

  @Test
  void verifiesStripeSignatureAndRejectsReplayOutsideFiveMinutes() {
    long timestamp = 1_800_000_000L;
    byte[] payload = "{\"type\":\"checkout.session.completed\"}".getBytes(StandardCharsets.UTF_8);
    String signature = Signatures.hmac("whsec_test", (timestamp+"."+new String(payload, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
    String header = "t="+timestamp+",v1="+signature;
    assertThat(Signatures.verifyStripe("whsec_test", payload, header, timestamp+30)).isTrue();
    assertThat(Signatures.verifyStripe("whsec_test", payload, header, timestamp+301)).isFalse();
  }
}
