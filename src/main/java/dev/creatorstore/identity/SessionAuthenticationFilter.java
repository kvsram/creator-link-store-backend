package dev.creatorstore.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves the opaque Creator Store cookie into a Spring Security principal. */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {
  private final SessionService sessions;

  public SessionAuthenticationFilter(SessionService sessions) {
    this.sessions = sessions;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication() == null) {
      String token = SessionCookies.readToken(request);
      sessions.resolve(token).ifPresent(session -> {
        CreatorPrincipal principal = new CreatorPrincipal(session.accountId(), session.publicId());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
            List.of(new SimpleGrantedAuthority("ROLE_CREATOR")));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      });
    }
    chain.doFilter(request, response);
  }
}
