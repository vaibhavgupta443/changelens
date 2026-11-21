package com.changelens.backend.analysis;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code changelens.*}.
 *
 * @param workspace folder that contains the repositories the API may analyse (one sub-folder per repository)
 * @param workers   analyses that run at the same time
 * @param queue     analyses that may wait for a worker before new requests are refused
 */
@ConfigurationProperties("changelens")
public record ChangeLensProperties(Path workspace, Integer workers, Integer queue) {

    public ChangeLensProperties {
        workspace = workspace == null ? Path.of("workspace") : workspace;
        workers = workers == null || workers < 1 ? 2 : workers;
        queue = queue == null || queue < 0 ? 20 : queue;
    }
}
