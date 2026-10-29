package habitTracker.relay;

import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class UplinkConfig implements WebSocketConfigurer, ServletContextAware {

    private final UplinkHandler handler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // No setAllowedOrigins: runnerd sends no Origin header (passes); a browser on another origin is refused.
        registry.addHandler(handler, "/relay/uplink").addInterceptors(handler);
    }

    /**
     * Frame limits on the real container. Not a ServletServerContainerFactoryBean: that one throws in
     * MockMvc tests, where no ServerContainer exists.
     */
    @Override
    public void setServletContext(ServletContext ctx) {
        if (ctx.getAttribute(ServerContainer.class.getName()) instanceof ServerContainer c) {
            c.setDefaultMaxTextMessageBufferSize(UplinkRegistry.MAX_FRAME_BYTES);
            c.setDefaultMaxBinaryMessageBufferSize(UplinkRegistry.MAX_FRAME_BYTES);
        }
    }
}
