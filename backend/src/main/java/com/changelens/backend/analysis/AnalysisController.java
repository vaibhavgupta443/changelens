package com.changelens.backend.analysis;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * POST /api/analyses              start an analysis (202 + Location)
 * GET  /api/analyses              recent analyses, newest first
 * GET  /api/analyses/{id}         status and numbers of one analysis
 * GET  /api/analyses/{id}/report  the rendered report (format=pr-comment | markdown)
 * </pre>
 */
@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

    private static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown;charset=UTF-8");

    private final AnalysisService service;
    private final AnalysisRepository runs;

    public AnalysisController(AnalysisService service, AnalysisRepository runs) {
        this.service = service;
        this.runs = runs;
    }

    public record PageView(List<AnalysisView> items, int page, int size, long total) {
    }

    @PostMapping
    public ResponseEntity<AnalysisView> start(@Valid @RequestBody AnalysisRequest request) {
        AnalysisRun run = service.submit(request);
        return ResponseEntity.accepted().location(URI.create("/api/analyses/" + run.getId())).body(AnalysisView.of(run));
    }

    @GetMapping
    public PageView list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        var result = runs.findAll(PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageView(result.map(AnalysisView::of).getContent(), safePage, safeSize, result.getTotalElements());
    }

    @GetMapping("/{id}")
    public AnalysisView get(@PathVariable String id) {
        return AnalysisView.of(service.get(id));
    }

    @GetMapping("/{id}/report")
    public ResponseEntity<String> report(@PathVariable String id, @RequestParam(defaultValue = "pr-comment") String format) {
        AnalysisRun run = service.get(id);
        if (run.getStatus() != AnalysisStatus.DONE) {
            throw new RunNotReadyException(id, run.getStatus());
        }
        String body = switch (format) {
            case "pr-comment" -> run.getPrComment();
            case "markdown" -> run.getMarkdown();
            default -> throw new IllegalArgumentException("format must be pr-comment or markdown");
        };
        return ResponseEntity.ok().contentType(MARKDOWN).body(body);
    }
}
