package filters;

import io.jsonwebtoken.Jwts;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.HttpHeaders;
import java.io.IOException;
import java.security.Key;
import java.util.logging.Logger;

/**
 * Same signature check as VC's JWTTokenNeededFilter, applied to the
 * provenance graph servlets. A bearer token must be signed with the shared key.
 */
public class JWTTokenNeededFilter implements Filter {

    private static Logger log;

    @Override
    public void init(FilterConfig filterConfig) {
        log = Logger.getLogger(getClass().getName());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String authorizationHeader = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);
        log.info("#### authorizationHeader : " + authorizationHeader);

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            log.severe("#### invalid authorizationHeader : " + authorizationHeader);
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String token = authorizationHeader.substring("Bearer".length()).trim();

        try {
            Key key = KeyGenerator.generateKey();
            Jwts.parser().setSigningKey(key).parseClaimsJws(token);
            log.info("#### valid token : " + token);
        } catch (Exception e) {
            log.severe("#### invalid token : " + token);
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
    }
}
