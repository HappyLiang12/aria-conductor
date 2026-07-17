package io.aria.conductor.app.dev;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("h2")
@RequestMapping("/api/v1/dev/sql")
public class DevSqlController {

    private final DevSqlService devSqlService;

    public DevSqlController(DevSqlService devSqlService) {
        this.devSqlService = devSqlService;
    }

    @PostMapping("/execute")
    public ResponseEntity<DevSqlResponse> execute(@RequestBody DevSqlRequest request) {
        return ResponseEntity.ok(devSqlService.execute(request.sql()));
    }
}
