package habitTracker.relay;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// Shell page behind the second factor; js/relay/relay.js renders devices, sessions and chat (FLOWS.md "UI").
@Controller
public class RelayController {

    @GetMapping("/relay")
    public String relay() {
        return "relay";
    }
}
