package com.changelens.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AnalysisApiTest {

    private static final Path WORKSPACE = createWorkspaceWithRepo();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("changelens.workspace", WORKSPACE::toString);
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:api-test;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AnalysisRepository runs;

    @Test
    void analysesTwoRevisionsAndServesTheReport() throws Exception {
        String id = start("demo", "HEAD~1", "HEAD");
        JsonNode done = waitUntilFinished(id);

        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("typesChanged").asInt()).isGreaterThanOrEqualTo(1);

        mvc.perform(get("/api/analyses/{id}/report", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/markdown")));
        String report = mvc.perform(get("/api/analyses/{id}/report?format=markdown", id)).andReturn().getResponse().getContentAsString();
        assertThat(report).contains("Greeter").contains("greet");
    }

    @Test
    void listsNewestFirst() throws Exception {
        start("demo", "HEAD~1", "HEAD");
        String id2 = start("demo", "HEAD", "HEAD");
        waitUntilFinished(id2);
        mvc.perform(get("/api/analyses?size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id2))
                .andExpect(jsonPath("$.size").value(5));
    }

    @Test
    void anUnknownRevisionFailsTheRunWithAReadableMessage() throws Exception {
        String id = start("demo", "no-such-branch", "HEAD");
        JsonNode done = waitUntilFinished(id);
        assertThat(done.get("status").asText()).isEqualTo("FAILED");
        assertThat(done.get("error").asText()).contains("unknown revision");
    }

    @Test
    void refusesNamesThatAreNotPlainFolderNames() throws Exception {
        for (String repo : new String[] {"../demo", "..", ".", "a/b", "/etc", "demo ", "-x"}) {
            mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON).content(body(repo, "HEAD~1", "HEAD")))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void refusesRevisionsThatCouldBeReadAsOptions() throws Exception {
        for (String rev : new String[] {"--output=x", "-x", "a b", ""}) {
            mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON).content(body("demo", rev, "HEAD")))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void anUnknownRepositoryIsNotFound() throws Exception {
        mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON).content(body("missing", "HEAD~1", "HEAD")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("missing")));
    }

    @Test
    void anUnknownAnalysisIsNotFound() throws Exception {
        mvc.perform(get("/api/analyses/does-not-exist")).andExpect(status().isNotFound());
    }

    @Test
    void aReportIsNotAvailableUntilTheRunIsDone() throws Exception {
        AnalysisRun queued = runs.save(new AnalysisRun("pending-run", "demo", "HEAD~1", "HEAD", Instant.now()));
        mvc.perform(get("/api/analyses/{id}/report", queued.getId())).andExpect(status().isConflict());
    }

    @Test
    void anUnknownReportFormatIsABadRequest() throws Exception {
        String id = start("demo", "HEAD~1", "HEAD");
        waitUntilFinished(id);
        mvc.perform(get("/api/analyses/{id}/report?format=pdf", id)).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private String start(String repo, String base, String head) throws Exception {
        MvcResult r = mvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON).content(body(repo, base, head)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode waitUntilFinished(String id) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            String text = mvc.perform(get("/api/analyses/{id}", id)).andReturn().getResponse().getContentAsString();
            JsonNode view = json.readTree(text);
            String status = view.get("status").asText();
            if (status.equals("DONE") || status.equals("FAILED")) {
                return view;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("analysis " + id + " did not finish in time");
    }

    private static String body(String repo, String base, String head) {
        return "{\"repo\":\"" + repo + "\",\"base\":\"" + base + "\",\"head\":\"" + head + "\"}";
    }

    private static Path createWorkspaceWithRepo() {
        try {
            Path workspace = Files.createTempDirectory("changelens-workspace");
            Path repo = Files.createDirectory(workspace.resolve("demo"));
            try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
                PersonIdent who = new PersonIdent("Test", "test@example.com");
                Path file = repo.resolve("src/main/java/demo/Greeter.java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, "package demo;\npublic class Greeter {\n    public String greet(String name) {\n        return \"hello \" + name;\n    }\n}\n");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("first").setAuthor(who).setCommitter(who).call();
                Files.writeString(file, "package demo;\npublic class Greeter {\n    public String greet(String name) {\n        if (name == null) {\n            return \"hello stranger\";\n        }\n        return \"hello \" + name;\n    }\n}\n");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("second").setAuthor(who).setCommitter(who).call();
            }
            return workspace;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
