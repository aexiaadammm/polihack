package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.model.RepositoryAnalysisRequest;
import org.example.model.SourceFile;
import org.example.service.AnalysisService;
import org.example.service.GitRepositoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;
    private final GitRepositoryService gitRepositoryService;

    
    @GetMapping("/debug/version")
    public ResponseEntity<?> debugVersion() {
        return ResponseEntity.ok(Map.of(
                "version", "ORCHESTRATION_QUESTIONS_PSEUDOCODE_V1",
                "message", "Backendul genereaza intrebari, raspunsuri si pseudocod explicat"
        ));
    }

    @PostMapping("/repository")
    public ResponseEntity<?> analyzeRepository(@RequestBody RepositoryAnalysisRequest request) {
        try {
            if (request.getRepoUrl() == null || request.getRepoUrl().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "repoUrl este obligatoriu"));
            }



            List<SourceFile> files = gitRepositoryService.cloneAndReadSourceFiles(
                    request.getRepoUrl(),
                    request.getAccessToken(),
                    request.getBranch()
            );

            if (files.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Nu am gasit fisiere sursa relevante in repository."
                ));
            }

            return ResponseEntity.ok(analysisService.analyzeSourceFiles(files));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFiles(@RequestParam("files") List<MultipartFile> files) {
        try {
            if (files == null || files.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Niciun fisier trimis."));
            }

            return ResponseEntity.ok(analysisService.uploadAndAnalyze(files));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<?> getSession(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(analysisService.getSessionState(sessionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{sessionId}/chat")
    public ResponseEntity<?> chat(@PathVariable String sessionId,
                                  @RequestBody Map<String, String> body) {
        try {
            String questionId = body.get("questionId");
            String answer = body.get("answer");
            String freeText = body.getOrDefault("freeText", "");

            if (questionId == null || answer == null || answer.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "questionId si answer sunt obligatorii"
                ));
            }

            return ResponseEntity.ok(
                    analysisService.processAnswer(sessionId, questionId, answer, freeText)
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{sessionId}/result")
    public ResponseEntity<?> getResult(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(analysisService.getResult(sessionId));
        } catch (IllegalStateException e) {
            return ResponseEntity.accepted().body(Map.of(
                    "status", "GENERATING",
                    "message", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
