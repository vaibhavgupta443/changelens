package com.changelens.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Starts ChangeLens as an MCP server on standard input and output. Run it from an MCP client, for example:
 *
 * <pre>{@code java -jar changelens-mcp.jar /path/to/folder-with-your-repos}</pre>
 *
 * The workspace is the first argument, or the {@code CHANGELENS_WORKSPACE} environment variable, or the current folder. Standard
 * output carries only protocol messages, so nothing else may print to it; the packaged jar has logging switched off.
 */
public final class McpMain {

    private McpMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        String configured = args.length > 0 ? args[0] : System.getenv("CHANGELENS_WORKSPACE");
        Path workspace = Path.of(configured == null || configured.isBlank() ? "." : configured);
        CountDownLatch inputEnded = new CountDownLatch(1);
        InputStream in = new FilterInputStream(System.in) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b < 0) {
                    inputEnded.countDown();
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = super.read(buffer, offset, length);
                if (n < 0) {
                    inputEnded.countDown();
                }
                return n;
            }
        };
        var server = new ChangeLensMcpServer(workspace).start(in, System.out);
        inputEnded.await();
        server.close();
        System.exit(0);
    }
}
