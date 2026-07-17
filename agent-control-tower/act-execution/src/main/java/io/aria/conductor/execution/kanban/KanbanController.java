package io.aria.conductor.execution.kanban;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/kanban/items")
public class KanbanController {

    private final KanbanService kanbanService;

    public KanbanController(KanbanService kanbanService) {
        this.kanbanService = kanbanService;
    }

    @PostMapping
    public ResponseEntity<KanbanItem> create(@Valid @RequestBody CreateKanbanItemRequest request) {
        KanbanItem created = kanbanService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<KanbanItem>> list(@RequestParam(required = false) KanbanStatus status) {
        return ResponseEntity.ok(kanbanService.list(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<KanbanItem> get(@PathVariable String id) {
        return ResponseEntity.ok(kanbanService.get(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<KanbanItem> update(@PathVariable String id,
                                             @Valid @RequestBody UpdateKanbanItemRequest request) {
        return ResponseEntity.ok(kanbanService.update(id, request));
    }

    @PostMapping("/{id}/transition")
    public ResponseEntity<KanbanItem> transition(@PathVariable String id,
                                                 @Valid @RequestBody TransitionRequest request) {
        return ResponseEntity.ok(
                kanbanService.transition(id, request.getStatus(), request.getComment()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        kanbanService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
