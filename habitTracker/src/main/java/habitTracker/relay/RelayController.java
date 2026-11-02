package habitTracker.relay;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// Shell page behind the second factor; js/relay/relay.js renders devices, sessions and chat (FLOWS.md "UI").
@Controller
public class RelayController {

    // /relay/claim/{id}: the link inside the runner's pairing QR; relay.js shows the confirm view for it.
    @GetMapping({"/relay", "/relay/claim/{id}"})
    public String relay() {
        return "relay";
    }
}
