package com.CS335_Project3.api_gateway.filter;

import com.CS335_Project3.api_gateway.RateLimiter;
import com.CS335_Project3.api_gateway.config.ApiKeyConfig;
import com.CS335_Project3.api_gateway.config.RuntimeRateLimitPolicyService;
import com.CS335_Project3.api_gateway.ratelimiter.FixedWindowRateLimiterStrategy;
import com.CS335_Project3.api_gateway.ratelimiter.LeakyBucketRateLimiterStrategy;
import com.CS335_Project3.api_gateway.ratelimiter.SlidingWindowRateLimiterStrategy;
import com.CS335_Project3.api_gateway.ratelimiter.TokenBucketRateLimiterStrategy;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;


import java.io.IOException;
import java.util.List;


import static org.assertj.core.api.Assertions.assertThat;


class ApiKeyFilterTest {


    private ApiKeyFilter filter;


    @BeforeEach
    void setUp() {
        ApiKeyConfig config = new ApiKeyConfig();
        // Update to match actual keys used in application.properties
        config.setApiKeys(List.of(
            "dev-key-token",    // Token Bucket Algorithm, Limit: 3
            "dev-key-fixed",    // Fixed Window Algorithm, Limit: 3
            "dev-key-sliding",  // Sliding Window Algorithm, Limit: 3
            "dev-key-business"  // Token Bucket Algorithm, Limit: 6
        ));
        FixedWindowRateLimiterStrategy fixedWindow = Mockito.mock(FixedWindowRateLimiterStrategy.class);
        RuntimeRateLimitPolicyService policyService = Mockito.mock(RuntimeRateLimitPolicyService.class);
        Mockito.when(policyService.getPolicy()).thenReturn(null);
        RateLimiter rateLimiter = new RateLimiter(
            new TokenBucketRateLimiterStrategy(),
            fixedWindow,
            new SlidingWindowRateLimiterStrategy(),
            new LeakyBucketRateLimiterStrategy(),
            null,
            policyService
        );
        filter = new ApiKeyFilter(config, rateLimiter);
    }


    @Test
    void validKey_shouldPassThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // To test selective multi-rate limit algorithm,
        // change this line to use one of the valid keys as below 
        // dev-key-token, dev-key-token, dev-key-sliding, or dev-key-business
        request.addHeader("X-API-Key", "dev-key-token");
        request.setRequestURI("/api/test123/notes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        // chain.getRequest() is non-null only if doFilter was called
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void missingKey_shouldReturn401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/test123/notes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();  // chain was NOT called
        assertThat(response.getContentAsString())
            .contains("Unauthorized");
    }

    @Test
    void invalidKey_shouldReturn401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "wrong-key");
        request.setRequestURI("/api/test123/notes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString())
            .contains("Unauthorized");
    }

    @Test
    void healthPath_shouldSkipValidation() throws ServletException, IOException {
        // /health has no API key but should still pass through
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void keyMatchingIsCaseInsensitive() throws ServletException, IOException {
        // DEV-KEY-TOKEN is stored lowercase, client sends uppercase should still pass
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "DEV-KEY-TOKEN");
        request.setRequestURI("/api/test123/notes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void responseBody_containsExpectedFields() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/test123/notes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        String body = response.getContentAsString();
        assertThat(body).contains("\"status\":401");
        assertThat(body).contains("\"error\":\"Unauthorized\"");
        assertThat(body).contains("\"path\":\"/api/test123/notes\"");
    }

    @Test
    void fourthRequest_shouldReturn429() throws ServletException, IOException {
        for (int i = 1; i <= 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-API-Key", "dev-key-token");
            request.setRequestURI("/api/test123/notes");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
    }

    MockHttpServletRequest request4 = new MockHttpServletRequest();
    // To test selective multi-rate limit algorithm,
    // use one of the valid keys
    request4.addHeader("X-API-Key", "dev-key-token");
    request4.setRequestURI("/api/test123/notes");
    MockHttpServletResponse response4 = new MockHttpServletResponse();
    MockFilterChain chain4 = new MockFilterChain();

    filter.doFilterInternal(request4, response4, chain4);

    assertThat(response4.getStatus()).isEqualTo(429);
    assertThat(chain4.getRequest()).isNull();
    assertThat(response4.getContentAsString())
        .contains("Too Many Requests");
    }
}
