package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.example.model.SourceFile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Slf4j
@Service
public class GitRepositoryService {

    private static final long MAX_FILE_BYTES = 250_000;

    public List<SourceFile> cloneAndReadSourceFiles(String repoUrl, String accessToken) throws Exception {
        return cloneAndReadSourceFiles(repoUrl, accessToken, "main");
    }

    public List<SourceFile> cloneAndReadSourceFiles(String repoUrl, String accessToken, String branch) throws Exception {
        validateGithubUrl(repoUrl);

        Path tempDir = Files.createTempDirectory("repo-analysis-");

        try {
            String branchToClone = branch == null || branch.isBlank() ? "main" : branch.trim();
            log.info("Cloning repository {} branch {}", repoUrl, branchToClone);

            var cloneCommand = Git.cloneRepository()
                    .setURI(normalizeRepoUrl(repoUrl))
                    .setDirectory(tempDir.toFile())
                    .setBranch(branchToClone);

            if (accessToken != null && !accessToken.isBlank()) {
                cloneCommand.setCredentialsProvider(
                        new UsernamePasswordCredentialsProvider("x-access-token", accessToken)
                );
            }

            cloneCommand.call().close();

            try (Stream<Path> paths = Files.walk(tempDir)) {
                return paths
                        .filter(Files::isRegularFile)
                        .filter(this::isRelevantSourceFile)
                        .filter(this::isSmallEnough)
                        .map(path -> readSourceFile(tempDir, path))
                        .toList();
            }
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private void validateGithubUrl(String repoUrl) {
        if (!repoUrl.startsWith("https://github.com/")) {
            throw new IllegalArgumentException("Momentan sunt acceptate doar URL-uri GitHub HTTPS.");
        }
    }

    private String normalizeRepoUrl(String repoUrl) {
        String clean = repoUrl.trim();
        return clean.endsWith(".git") ? clean : clean + ".git";
    }

    private boolean isRelevantSourceFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);

        if (name.contains(".min.")) {
            return false;
        }

        return name.endsWith(".java")
                || name.endsWith(".js")
                || name.endsWith(".ts")
                || name.endsWith(".tsx")
                || name.endsWith(".jsx")
                || name.endsWith(".py")
                || name.endsWith(".kt")
                || name.endsWith(".go")
                || name.endsWith(".yml")
                || name.endsWith(".yaml")
                || name.endsWith(".json");
    }

    private boolean isSmallEnough(Path path) {
        try {
            return Files.size(path) <= MAX_FILE_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private SourceFile readSourceFile(Path root, Path path) {
        try {
            String relativeName = root.relativize(path).toString().replace("\\", "/");
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return new SourceFile(relativeName, content);
        } catch (Exception e) {
            throw new RuntimeException("Nu pot citi fisierul: " + path, e);
        }
    }

    private void deleteDirectory(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
