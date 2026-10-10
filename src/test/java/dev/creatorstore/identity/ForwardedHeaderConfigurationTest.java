package dev.creatorstore.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

/** Proves the checked-in proxy boundary binds to Tomcat's native RemoteIpValve settings. */
class ForwardedHeaderConfigurationTest {
  @Test
  void mainConfigurationTrustsForwardingHeadersOnlyFromPrivateOrLoopbackPeers()
      throws Exception {
    var environment = new MockEnvironment();
    var loader = new YamlPropertySourceLoader();
    for (var source : loader.load("main-application",
        new FileSystemResource("src/main/resources/application.yml"))) {
      environment.getPropertySources().addLast(source);
    }

    ServerProperties server = Binder.get(environment).bind("server", ServerProperties.class)
        .orElseThrow(() -> new AssertionError("main server configuration did not bind"));
    assertThat(server.getForwardHeadersStrategy())
        .isEqualTo(ServerProperties.ForwardHeadersStrategy.NATIVE);
    assertThat(server.getTomcat().getRemoteip().getRemoteIpHeader())
        .isEqualTo("X-Forwarded-For");
    assertThat(server.getTomcat().getRemoteip().getProtocolHeader())
        .isEqualTo("X-Forwarded-Proto");

    Pattern trustedPeer = Pattern.compile(server.getTomcat().getRemoteip().getInternalProxies());
    assertThat(trustedPeer.matcher("10.0.1.23").matches()).isTrue();
    assertThat(trustedPeer.matcher("192.168.50.10").matches()).isTrue();
    assertThat(trustedPeer.matcher("127.0.0.1").matches()).isTrue();
    assertThat(trustedPeer.matcher("203.0.113.40").matches()).isFalse();
  }
}
