package infofilters;

import filters.KeyGenerator;

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
 * Same signature check as VC's JWTTokenNeededFilter, applied to every route of
 * the info war: the Jersey resources and the two annotated servlets. The UI
 * does not call this service, and before this filter it answered anyone.
 * A bearer token must be signed with the shared key.
 */
public class JwtServletFilter implements Filter {

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

        // Get the HTTP Authorization header from the request. Do not log the header or the token.
        String authorizationHeader = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            log.severe("#### missing or malformed Authorization header");
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String token = authorizationHeader.substring("Bearer".length()).trim();

        try {
            Key key = KeyGenerator.generateKey();
            Jwts.parser().setSigningKey(key).parseClaimsJws(token);
            log.info("#### valid token");
        } catch (Exception e) {
            log.severe("#### invalid token");
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
    }
}
