package dev.creatorstore.identity;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final OriginGuardInterceptor originGuard;

  WebConfig(OriginGuardInterceptor originGuard) {
    this.originGuard = originGuard;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(originGuard).addPathPatterns("/api/**");
  }
}
