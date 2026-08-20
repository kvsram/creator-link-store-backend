package dev.creatorstore.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AuthInterceptor authInterceptor;
  private final OriginGuardInterceptor originGuard;

  @Value("${app.allowed-origins:http://localhost:5173,http://localhost:3000}")
  private String allowedOrigins;

  WebConfig(AuthInterceptor authInterceptor, OriginGuardInterceptor originGuard) {
    this.authInterceptor = authInterceptor;
    this.originGuard = originGuard;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(originGuard).addPathPatterns("/api/**");
    registry.addInterceptor(authInterceptor)
        .addPathPatterns("/api/v1/**")
        .excludePathPatterns(
            "/api/v1/authentication/**",
            "/api/v1/checkout/**",
            "/api/v1/payments/**",
            "/api/v1/webhooks/**",
            "/api/v1/integrations/instagram/config");
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
        .allowedOrigins(allowedOrigins.split(","))
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(true);
  }
}
