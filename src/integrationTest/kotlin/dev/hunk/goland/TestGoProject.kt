package dev.hunk.goland

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Creates the complete Git-backed Go project used by IDE integration tests. */
internal object TestGoProject {
    const val MAIN_FILE = "cmd/main.go"
    const val ADDED_FILE = "cmd/added.go"
    const val CHANGED_LINE = 4
    const val UNCHANGED_HUNK_LINE = 6
    const val UNIFIED_VISUAL_LINE = 7
    const val UNIFIED_SOURCE_LINE = 5
    const val ADDED_FILE_LINE = 4

    /**
     * Creates a committed two-file Go module, then leaves one modified and one added file.
     * The fixture uses only the local Git executable; it never invokes Go or accesses the network.
     */
    fun create(temporaryDirectory: Path): Path {
        val project = temporaryDirectory.resolve("hunk-goland-fixture")
        Files.createDirectories(project.resolve("cmd"))
        write(project.resolve(".gitignore"), ".idea/\n")
        write(project.resolve("go.mod"), GO_MODULE)
        write(project.resolve(MAIN_FILE), BASELINE_MAIN)

        git(project, "init", "--quiet")
        git(project, "config", "user.name", "Hunk GoLand test")
        git(project, "config", "user.email", "hunk-goland@example.invalid")
        git(project, "add", ".")
        git(project, "commit", "--quiet", "--no-gpg-sign", "--no-verify", "-m", "Initial fixture")
        git(project, "branch", BASELINE_BRANCH)

        write(project.resolve(MAIN_FILE), CHANGED_MAIN)
        write(project.resolve(ADDED_FILE), ADDED_GO)
        git(project, "add", ADDED_FILE)
        git(project, "diff", "--check")
        check(git(project, "diff", "--name-only").lineSequence().filter(String::isNotBlank).toList() == listOf(MAIN_FILE)) {
            "The generated Go project must contain exactly one changed file: $MAIN_FILE"
        }
        check(git(project, "diff", "--cached", "--name-only") == ADDED_FILE) {
            "The generated Go project must contain exactly one added file: $ADDED_FILE"
        }
        val comparisonCommit = git(project, "stash", "create", "Hunk branch comparison fixture")
        check(comparisonCommit.isNotBlank()) { "Git did not create the branch-comparison fixture commit" }
        git(project, "branch", FEATURE_BRANCH, comparisonCommit)
        return project
    }

    fun resolveRevision(project: Path, revision: String): String = git(project, "rev-parse", revision)

    /** Switches the generated repository to its committed feature state for branch-popup tests. */
    fun checkoutFeature(project: Path) {
        git(project, "checkout", "--quiet", "--force", FEATURE_BRANCH)
        check(git(project, "status", "--porcelain").isEmpty()) {
            "The branch-comparison fixture must be clean after checkout"
        }
    }

    private fun write(path: Path, contents: String) {
        Files.writeString(path, contents, StandardCharsets.UTF_8)
    }

    private fun git(project: Path, vararg arguments: String): String {
        val command = listOf(System.getProperty("hunk.gitExecutable", "git"), *arguments)
        val process = ProcessBuilder(command)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment()["GIT_CONFIG_GLOBAL"] = project.resolveSibling("empty-gitconfig").toString()
                environment()["GIT_AUTHOR_DATE"] = COMMIT_DATE
                environment()["GIT_COMMITTER_DATE"] = COMMIT_DATE
            }
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        check(process.waitFor() == 0) {
            "`${command.joinToString(" ")}` failed in $project:\n${output.ifBlank { "<no output>" }}"
        }
        return output.trim()
    }

    private const val COMMIT_DATE = "2000-01-01T00:00:00Z"
    const val BASELINE_BRANCH = "baseline"
    const val FEATURE_BRANCH = "feature-review"
    private const val GO_MODULE = """module example.com/hunk-goland-fixture

go 1.25
"""
    private const val BASELINE_MAIN = """package main

import (
	"crypto/tls"
	"flag"
	"fmt"
)

func main() {
	flag.Parse()
	fmt.Println("hello from Hunk", tls.VersionTLS13)
}
"""
    private const val CHANGED_MAIN = """package main

import (
	"crypto/tls" // Hunk UI test working-tree change.
	"fmt"
)

func main() {
	fmt.Println("hello from Hunk", tls.VersionTLS13)
}
"""
    private const val ADDED_GO = """package main

func greeting(name string) string {
	return "hello, " + name
}
"""
}
