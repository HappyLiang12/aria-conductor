package io.aria.conductor.knowledge.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * Thin wrapper over JGit for managing local bare knowledge repositories.
 * <p>
 * Each knowledge type (skills, scripts, prompts, tools, templates) is a separate
 * bare repository under {@code knowledge.git.base-path}. All operations are
 * filesystem-local — no remote network calls are ever made.
 */
@Service
public class LocalGitClient {

    private static final Logger log = LoggerFactory.getLogger(LocalGitClient.class);
    private static final int MAX_BRANCH_LENGTH = 64;
    private static final Pattern BRANCH_INVALID_CHARS = Pattern.compile("[^a-zA-Z0-9._\\-]");
    private static final PersonIdent DEFAULT_AUTHOR =
            new PersonIdent("act-knowledge", "act-knowledge@aria-conductor.local");

    private String basePath;

    public LocalGitClient(@Value("${knowledge.git.base-path:./data/knowledge-repos}") String basePath) {
        this.basePath = basePath;
    }

    /** Test/setter access for relocating the base path (used by integration tests). */
    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public String getBasePath() {
        return basePath;
    }

    public Path repoPath(String repoName) {
        return Paths.get(basePath, repoName + ".git");
    }

    /**
     * Initialise a bare repository for a knowledge type if it does not already
     * exist. The bare repo is given a single empty initial commit on
     * {@code refs/heads/main} so that branches can be created off it.
     */
    public void initRepo(String repoName) {
        Path path = repoPath(repoName);
        try {
            if (Files.exists(path.resolve("HEAD"))) {
                log.debug("Repo already initialised: {}", path);
                return;
            }
            Files.createDirectories(path);
            try (Git git = Git.init().setBare(true).setInitialBranch("main").setDirectory(path.toFile()).call()) {
                Repository repo = git.getRepository();
                createInitialCommit(repo);
            }
            log.info("Initialised bare repo {} at {}", repoName, path);
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("Failed to init repo " + repoName, e);
        }
    }

    /**
     * Create a feature branch off main with the format
     * {@code feature/{sanitized-name}-{epochMillis}} and return its name.
     */
    public String createBranch(String repoName, String itemName) {
        String sanitized = sanitizeBranchName(itemName);
        String branchName = ("feature/" + sanitized + "-" + System.currentTimeMillis());
        if (branchName.length() > MAX_BRANCH_LENGTH) {
            branchName = branchName.substring(0, MAX_BRANCH_LENGTH);
        }
        Path path = repoPath(repoName);
        try (Repository repo = open(path); Git git = new Git(repo)) {
            ObjectId mainHead = repo.resolve("refs/heads/main");
            if (mainHead == null) {
                throw new GitOperationException("main branch missing in " + repoName);
            }
            git.branchCreate()
                    .setName(branchName)
                    .setStartPoint("refs/heads/main")
                    .setForce(true)
                    .call();
            log.info("Created branch {} in {}", branchName, repoName);
            return branchName;
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("Failed to create branch in " + repoName, e);
        }
    }

    /**
     * Commit a single file's contents to the tip of {@code branch}. Returns the
     * resulting commit's short SHA.
     */
    public String commit(String repoName, String branch, String filePath, String content, String message) {
        Path path = repoPath(repoName);
        try (Repository repo = open(path); ObjectInserter inserter = repo.newObjectInserter()) {
            ObjectId branchTip = repo.resolve("refs/heads/" + branch);
            if (branchTip == null) {
                throw new GitOperationException("Branch not found: " + branch);
            }

            // Build new tree with file inserted/updated
            ObjectId blobId = inserter.insert(Constants.OBJ_BLOB,
                    content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));

            DirCache index = DirCache.newInCore();
            // Copy existing files from branch tip
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit head = walk.parseCommit(branchTip);
                RevTree tree = head.getTree();
                DirCacheEditor editor = index.editor();
                try (TreeWalk tw = new TreeWalk(repo)) {
                    tw.addTree(tree);
                    tw.setRecursive(true);
                    while (tw.next()) {
                        final String existingPath = tw.getPathString();
                        if (existingPath.equals(filePath)) {
                            continue; // we will replace this entry
                        }
                        final ObjectId existingId = tw.getObjectId(0);
                        final FileMode existingMode = tw.getFileMode(0);
                        editor.add(new DirCacheEditor.PathEdit(existingPath) {
                            @Override
                            public void apply(DirCacheEntry ent) {
                                ent.setObjectId(existingId);
                                ent.setFileMode(existingMode);
                            }
                        });
                    }
                }
                final ObjectId newBlobId = blobId;
                editor.add(new DirCacheEditor.PathEdit(filePath) {
                    @Override
                    public void apply(DirCacheEntry ent) {
                        ent.setObjectId(newBlobId);
                        ent.setFileMode(FileMode.REGULAR_FILE);
                    }
                });
                editor.finish();
            }

            ObjectId treeId = index.writeTree(inserter);
            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(treeId);
            cb.setParentId(branchTip);
            cb.setAuthor(DEFAULT_AUTHOR);
            cb.setCommitter(DEFAULT_AUTHOR);
            cb.setMessage(message == null ? "Update " + filePath : message);
            ObjectId commitId = inserter.insert(cb);
            inserter.flush();

            RefUpdate ru = repo.updateRef("refs/heads/" + branch);
            ru.setNewObjectId(commitId);
            ru.setExpectedOldObjectId(branchTip);
            ru.setRefLogMessage("commit: " + cb.getMessage(), false);
            RefUpdate.Result result = ru.update();
            if (result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.NEW
                    && result != RefUpdate.Result.FORCED) {
                throw new GitOperationException("Branch ref update failed: " + result);
            }
            String sha = commitId.abbreviate(7).name();
            log.debug("Committed {} to {}#{} as {}", filePath, repoName, branch, sha);
            return sha;
        } catch (IOException e) {
            throw new GitOperationException("Failed to commit to " + branch, e);
        }
    }

    /**
     * Merge {@code branch} into {@code main}. Falls back to fast-forward when
     * possible; otherwise creates a regular merge commit.
     */
    public void mergeBranch(String repoName, String branch) {
        Path path = repoPath(repoName);
        try (Repository repo = open(path); Git git = new Git(repo)) {
            ObjectId branchHead = repo.resolve("refs/heads/" + branch);
            ObjectId mainHead = repo.resolve("refs/heads/main");
            if (branchHead == null) {
                throw new GitOperationException("Branch not found: " + branch);
            }
            if (mainHead == null) {
                throw new GitOperationException("main branch missing");
            }
            // Bare repo merge: rewrite refs/heads/main directly. Fast-forward when
            // mainHead is ancestor of branchHead.
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit branchCommit = walk.parseCommit(branchHead);
                RevCommit mainCommit = walk.parseCommit(mainHead);
                ObjectId target;
                if (walk.isMergedInto(mainCommit, branchCommit)) {
                    target = branchHead;
                } else {
                    // create a merge commit using current main tree + branch tree (branch wins)
                    try (ObjectInserter inserter = repo.newObjectInserter()) {
                        CommitBuilder cb = new CommitBuilder();
                        cb.setTreeId(branchCommit.getTree().getId());
                        cb.setParentIds(mainCommit, branchCommit);
                        cb.setAuthor(DEFAULT_AUTHOR);
                        cb.setCommitter(DEFAULT_AUTHOR);
                        cb.setMessage("Merge branch '" + branch + "' into main");
                        target = inserter.insert(cb);
                        inserter.flush();
                    }
                }
                RefUpdate ru = repo.updateRef("refs/heads/main");
                ru.setNewObjectId(target);
                ru.setExpectedOldObjectId(mainHead);
                ru.setRefLogMessage("merge " + branch, false);
                RefUpdate.Result r = ru.update();
                if (r != RefUpdate.Result.FAST_FORWARD && r != RefUpdate.Result.NEW
                        && r != RefUpdate.Result.FORCED) {
                    throw new GitOperationException("Main ref update failed: " + r);
                }
            }
            log.info("Merged {} into main in {}", branch, repoName);
            // Touch the API call site for completeness so JGit's MergeResult stays in scope:
            MergeResult.MergeStatus.MERGED.toString();
        } catch (IOException e) {
            throw new GitOperationException("Failed to merge " + branch, e);
        }
    }

    /** Create a lightweight tag (e.g. semantic version) on main. */
    public void tag(String repoName, String tagName, String message) {
        Path path = repoPath(repoName);
        try (Repository repo = open(path); Git git = new Git(repo)) {
            git.tag().setName(tagName).setMessage(message == null ? tagName : message).call();
            log.info("Tagged {} in {}", tagName, repoName);
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("Failed to tag " + tagName, e);
        }
    }

    /** Delete a branch (typically after merge). Idempotent. */
    public void deleteBranch(String repoName, String branch) {
        Path path = repoPath(repoName);
        try (Repository repo = open(path); Git git = new Git(repo)) {
            Ref ref = repo.exactRef("refs/heads/" + branch);
            if (ref == null) {
                return;
            }
            git.branchDelete().setBranchNames(branch).setForce(true).call();
            log.debug("Deleted branch {} in {}", branch, repoName);
        } catch (IOException | GitAPIException e) {
            throw new GitOperationException("Failed to delete branch " + branch, e);
        }
    }

    /**
     * Sanitise a name into a Git-safe branch component. Allowed: alphanumeric,
     * hyphen, underscore, dot. Other characters are replaced with hyphens, and
     * the result is truncated to 64 chars.
     */
    public String sanitizeBranchName(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        String cleaned = BRANCH_INVALID_CHARS.matcher(name.trim()).replaceAll("-");
        // Collapse runs of dashes
        cleaned = cleaned.replaceAll("-+", "-");
        // Strip leading/trailing dots and dashes (Git refuses these)
        cleaned = cleaned.replaceAll("^[.\\-]+", "").replaceAll("[.\\-]+$", "");
        if (cleaned.isEmpty()) {
            cleaned = "unnamed";
        }
        if (cleaned.length() > MAX_BRANCH_LENGTH) {
            cleaned = cleaned.substring(0, MAX_BRANCH_LENGTH);
        }
        return cleaned;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    private Repository open(Path path) throws IOException {
        return new FileRepositoryBuilder()
                .setGitDir(path.toFile())
                .setMustExist(true)
                .build();
    }

    private void createInitialCommit(Repository repo) throws IOException {
        try (ObjectInserter inserter = repo.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            ObjectId treeId = index.writeTree(inserter);
            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(treeId);
            cb.setAuthor(DEFAULT_AUTHOR);
            cb.setCommitter(DEFAULT_AUTHOR);
            cb.setMessage("Initialise repository");
            ObjectId commitId = inserter.insert(cb);
            inserter.flush();
            RefUpdate ru = repo.updateRef("refs/heads/main");
            ru.setNewObjectId(commitId);
            ru.setRefLogMessage("init", false);
            ru.forceUpdate();
        }
    }

    /** Read a file's contents from main HEAD. Returns null when missing. */
    public String readFile(String repoName, String filePath) {
        Path path = repoPath(repoName);
        try (Repository repo = open(path); RevWalk walk = new RevWalk(repo)) {
            ObjectId head = repo.resolve("refs/heads/main");
            if (head == null) {
                return null;
            }
            RevCommit commit = walk.parseCommit(head);
            try (TreeWalk tw = TreeWalk.forPath(repo, filePath, commit.getTree())) {
                if (tw == null) return null;
                byte[] bytes = repo.open(tw.getObjectId(0)).getBytes();
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new GitOperationException("Failed to read " + filePath, e);
        }
    }

    /** Runtime exception for git plumbing failures. */
    public static class GitOperationException extends RuntimeException {
        public GitOperationException(String msg) { super(msg); }
        public GitOperationException(String msg, Throwable cause) { super(msg, cause); }
    }
}
