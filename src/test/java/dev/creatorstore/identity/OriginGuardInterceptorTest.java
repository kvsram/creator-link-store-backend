package dev.creatorstore.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class OriginGuardInterceptorTest {
  private static final String ALLOWED_ORIGIN = "http://77.112.61.68";

  @Test
  void allowsMutationFromConfiguredOrigin() throws Exception {
    OriginGuardInterceptor interceptor = new OriginGuardInterceptor(ALLOWED_ORIGIN);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getMethod()).thenReturn("POST");
    when(request.getHeader("Origin")).thenReturn(ALLOWED_ORIGIN);

    assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
  }

  @Test
  void rejectsMutationFromUnknownOrigin() throws Exception {
    OriginGuardInterceptor interceptor = new OriginGuardInterceptor(ALLOWED_ORIGIN);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    when(request.getMethod()).thenReturn("POST");
    when(request.getHeader("Origin")).thenReturn("https://attacker.example");
    when(response.getWriter()).thenReturn(new PrintWriter(body));

    assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
    verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    assertThat(body.toString()).contains("Origin is not allowed");
  }
}
