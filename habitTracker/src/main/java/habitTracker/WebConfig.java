package habitTracker;

import habitTracker.relay.TwoFactorInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final TwoFactorInterceptor twoFactorInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /relay/pair (pairing code is the credential) and /relay/uplink (device token) are not browser sessions.
        registry.addInterceptor(twoFactorInterceptor).addPathPatterns("/relay", "/relay/**")
                .excludePathPatterns("/relay/pair", "/relay/uplink");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(0) // Cache for 0 seconds
                .resourceChain(true)
                .addResolver(new org.springframework.web.servlet.resource.PathResourceResolver());
    }
}