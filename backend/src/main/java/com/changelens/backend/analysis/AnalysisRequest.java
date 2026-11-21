package com.changelens.backend.analysis;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What to compare. {@code repo} is the name of a repository folder inside the configured workspace (never a path), and the
 * revisions are plain git revisions such as {@code main}, {@code origin/main}, {@code HEAD~1}, a tag or a commit id.
 */
public record AnalysisRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*", message = "must be a folder name, not a path") String repo,
        @NotBlank @Size(max = 200) @Pattern(regexp = AnalysisRequest.REVISION, message = "not a valid git revision") String base,
        @NotBlank @Size(max = 200) @Pattern(regexp = AnalysisRequest.REVISION, message = "not a valid git revision") String head) {

    /** Letters, digits and the characters git revisions use. No spaces, no leading dash (so it can never look like an option). */
    static final String REVISION = "[A-Za-z0-9._/~^@{}][A-Za-z0-9._/~^@{}-]*";
}
