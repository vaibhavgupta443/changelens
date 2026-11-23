package com.changelens.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.changelens.ask.ChatModel.ModelException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The client is tested against a real HTTP server on localhost, so nothing leaves the machine and nothing costs money. */
class OpenAiCompatibleModelTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<JsonNode> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiCompatibleModel model(String key) {
        return new OpenAiCompatibleModel("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", key, "some/model", 321, Duration.ofSeconds(5));
    }

    @Test
    void sendsOneChatCompletionRequestAndReturnsTheReplyText() {
        String text = model("sk-test").complete("be brief", "what changed?");

        assertThat(text).isEqualTo("hello");
        assertThat(path.get()).isEqualTo("/v1/chat/completions"); // the trailing slash of the base url does not double up
        assertThat(authorization.get()).isEqualTo("Bearer sk-test");
        JsonNode sent = body.get();
        assertThat(sent.path("model").asString()).isEqualTo("some/model");
        assertThat(sent.path("temperature").asInt(-1)).isZero();
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(321);
        assertThat(sent.path("messages").get(0).path("role").asString()).isEqualTo("system");
        assertThat(sent.path("messages").get(0).path("content").asString()).isEqualTo("be brief");
        assertThat(sent.path("messages").get(1).path("role").asString()).isEqualTo("user");
        assertThat(sent.path("messages").get(1).path("content").asString()).isEqualTo("what changed?");
    }

    @Test
    void aLocalServerThatNeedsNoKeyGetsNoAuthorizationHeader() {
        model("").complete("s", "u");
        assertThat(authorization.get()).isNull();
        model(null).complete("s", "u");
        assertThat(authorization.get()).isNull();
    }

    @Test
    void aRejectedRequestIsReportedWithItsStatusAndNotTheKey() {
        status = 401;
        reply = "{\"error\":{\"message\":\"invalid api key\"}}";

        assertThatThrownBy(() -> model("sk-secret").complete("s", "u")).isInstanceOf(ModelException.class)
                .hasMessageContaining("HTTP 401").hasMessageContaining("invalid api key").hasMessageNotContaining("sk-secret");
    }

    @Test
    void aReplyThatIsNotAChatCompletionIsAnErrorNotAnEmptyAnswer() {
        reply = "<html>gateway error</html>";
        assertThatThrownBy(() -> model("k").complete("s", "u")).isInstanceOf(ModelException.class).hasMessageContaining("did not return JSON");

        reply = "{\"choices\":[]}";
        assertThatThrownBy(() -> model("k").complete("s", "u")).isInstanceOf(ModelException.class).hasMessageContaining("no text");

        reply = "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}";
        assertThatThrownBy(() -> model("k").complete("s", "u")).isInstanceOf(ModelException.class).hasMessageContaining("no text");
    }

    @Test
    void anUnreachableServerIsReportedAsThat() {
        int port = server.getAddress().getPort();
        server.stop(0);
        OpenAiCompatibleModel gone = new OpenAiCompatibleModel("http://127.0.0.1:" + port, "k", "m", 10, Duration.ofSeconds(2));

        assertThatThrownBy(() -> gone.complete("s", "u")).isInstanceOf(ModelException.class).hasMessageContaining("Could not reach");
    }

    @Test
    void theModelNameIsTheModelId() {
        assertThat(model("k").name()).isEqualTo("some/model");
    }
}
