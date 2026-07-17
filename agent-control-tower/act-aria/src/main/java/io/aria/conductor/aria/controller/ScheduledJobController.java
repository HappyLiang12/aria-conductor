package io.aria.conductor.aria.controller;

import io.aria.conductor.aria.dto.ScheduledJobDto;
import io.aria.conductor.aria.service.ScheduledJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/aria/jobs")
public class ScheduledJobController {

    private final ScheduledJobService scheduledJobService;

    public ScheduledJobController(ScheduledJobService scheduledJobService) {
        this.scheduledJobService = scheduledJobService;
    }

    @GetMapping
    public ResponseEntity<List<ScheduledJobDto>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(scheduledJobService.list(category, status));
    }

    @PostMapping
    public ResponseEntity<ScheduledJobDto> create(@RequestBody ScheduledJobDto input) {
        return ResponseEntity.ok(scheduledJobService.create(input));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScheduledJobDto> update(@PathVariable String id,
                                                   @RequestBody ScheduledJobDto input) {
        return ResponseEntity.ok(scheduledJobService.update(id, input));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        scheduledJobService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/pause")
    public ResponseEntity<ScheduledJobDto> pause(@PathVariable String id) {
        return ResponseEntity.ok(scheduledJobService.pause(id));
    }

    @PatchMapping("/{id}/resume")
    public ResponseEntity<ScheduledJobDto> resume(@PathVariable String id) {
        return ResponseEntity.ok(scheduledJobService.resume(id));
    }
}
