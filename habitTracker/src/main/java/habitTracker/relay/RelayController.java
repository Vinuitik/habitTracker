package habitTracker.relay;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// Placeholder landing page behind the second factor. The device list and chat client come next.
@Controller
public class RelayController {

    @GetMapping("/relay")
    public String relay() {
        return "relay";
    }
}
