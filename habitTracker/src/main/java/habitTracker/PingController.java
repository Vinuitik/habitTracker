package habitTracker;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Cheap, unauthenticated reachability probe — used by the offline-sync client
// (static/js/offline/connectivity.js) to tell "server unreachable" apart from
// "server up but my session expired", without any DB/auth work.
@RestController
public class PingController {

    @GetMapping("/api/ping")
    public String ping() {
        return "ok";
    }
}
