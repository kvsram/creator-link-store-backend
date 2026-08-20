package dev.creatorstore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.creatorstore.dto.CheckoutRequest;
import dev.creatorstore.integration.ProviderSession;
import dev.creatorstore.integration.RazorpayClient;
import dev.creatorstore.repository.CheckoutRepository;
import dev.creatorstore.repository.ProductRepository;
import dev.creatorstore.support.HttpResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {
  private ProductRepository products;
  private CheckoutRepository checkouts;
  private RazorpayClient razorpay;
  private PaymentService service;

  @BeforeEach
  void setUp() {
    products = mock(ProductRepository.class);
    checkouts = mock(CheckoutRepository.class);
    razorpay = mock(RazorpayClient.class);
    when(razorpay.provider()).thenReturn("razorpay");
    when(razorpay.configured()).thenReturn(true);
    when(razorpay.publicKeyId()).thenReturn("rzp_test_public");
    service = new PaymentService(products, checkouts, List.of(razorpay), razorpay,
        new ObjectMapper(), "test", "razorpay", "secret");
  }

  @Test
  void rejectsCheckoutWithoutBuyerEmailBeforeCreatingACharge() throws Exception {
    HttpResult result = service.createCheckout("key-1",
        new CheckoutRequest(1, 2, "razorpay", "invalid", "Buyer", Map.of(), null, null));

    assertEquals(400, result.status());
    verify(products, never()).findCheckoutProduct(any(Long.class), any(Long.class));
    verify(razorpay, never()).createSession(any());
  }

  @Test
  void rejectsPlanFromAnotherProduct() throws Exception {
    when(products.findCheckoutProduct(2, 1)).thenReturn(List.of(product()));
    when(checkouts.findPlan(99, 2)).thenReturn(List.of());

    HttpResult result = service.createCheckout("key-2",
        new CheckoutRequest(1, 2, null, "buyer@example.com", "Buyer", Map.of(), null, 99L));

    assertEquals(400, result.status());
    verify(checkouts, never()).create(any(), any(Long.class), any(Long.class), any(), any(), any(),
        any(Integer.class), any(), any(), any(), any(), any());
    verify(razorpay, never()).createSession(any());
  }

  @Test
  void persistsValidatedBuyerSelectionsAndUsesPlanAmount() throws Exception {
    when(products.findCheckoutProduct(2, 1)).thenReturn(List.of(product()));
    when(checkouts.findPlan(9, 2)).thenReturn(List.of(Map.of("id", 9L, "amount_subunits", 70000)));
    when(checkouts.isOpenSlot(7, 2)).thenReturn(true);
    when(checkouts.checkoutFields(2)).thenReturn(
        List.of(Map.of("id", 5L, "label", "Goal", "required", true)));
    when(razorpay.createSession(any())).thenReturn(new ProviderSession("order_123", null));

    HttpResult result = service.createCheckout("key-3",
        new CheckoutRequest(1, 2, null, " Buyer@Example.COM ", " A Buyer ",
            Map.of("5", "Learn architecture"), 7L, 9L));

    assertEquals(201, result.status());
    verify(checkouts).create(any(), eq(1L), eq(2L), eq("razorpay"), eq("key-3"), eq("INR"),
        eq(70000), eq("buyer@example.com"), eq("A Buyer"),
        eq("{\"5\":\"Learn architecture\"}"), eq(7L), eq(9L));
    verify(razorpay).createSession(any());
  }

  private static Map<String, Object> product() {
    return Map.of("id", 2L, "creator_id", 1L, "title", "Session",
        "amount_subunits", 90000, "currency", "INR", "handle", "creator");
  }
}
