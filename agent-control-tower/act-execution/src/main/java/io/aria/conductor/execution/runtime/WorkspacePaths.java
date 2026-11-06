package io.aria.conductor.execution.runtime;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Canonical path decisions shared by workspace admission and cleanup (spec 5.2).
 *
 * <p>Overlap is decided on real (symlink/junction-resolved) paths so an alias,
 * an ancestor or a descendant of an already-leased directory is the same
 * directory tree -- identical path strings are not the test. A directory that
 * does not exist yet has no real path and cannot be admitted as a workspace.
 */
public final class WorkspacePaths {

    /**
     * Serializes platform-owned common-git-directory mutations (worktree add /
     * remove / prune). Different worktrees do not make the shared repository
     * metadata race-free, so callers hold this while invoking such git
     * commands. In-process only: a second platform process is not coordinated.
     */
    private static final ReentrantLock COMMON_GIT_LOCK = new ReentrantLock();

    private WorkspacePaths() {
    }

    /** Resolves real paths and checks whether one directory tree contains or is contained by the other. */
    public static boolean overlaps(Path left, Path right) throws IOException {
        Path a = left.toRealPath();
        Path b = right.toRealPath();
        return a.startsWith(b) || b.startsWith(a);
    }

    /** Canonical real path of an existing directory. */
    public static Path canonicalDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            throw new IOException("Not a directory: " + path);
        }
        return path.toRealPath();
    }

    /**
     * Deletes a platform-owned directory tree without ever traversing a link.
     * A symbolic link, a Windows directory junction (an NTFS reparse point that
     * {@link Files#isSymbolicLink} does not report and that the default attribute view
     * presents as a directory) or any entry whose real path leaves the deleted tree is
     * removed as the entry itself and never descended into -- a link planted inside an
     * owned tree can therefore never turn cleanup into a deletion outside it. A file
     * reached through a directory that was swapped for a link after its attributes were
     * resolved also resolves outside the tree and is left in place rather than deleted.
     *
     * <p>This is the mechanical walk. A link strictly above the deletion root -- the
     * platform root itself, or a component between it and the deletion root, replaced
     * by a junction -- redirects every real path in the walk and cannot be seen from
     * inside the walk. Callers acting for a platform-owned root must therefore use
     * {@link #deleteTree(Path, Path)}, which validates the chain from the canonical
     * root before anything is deleted.
     */
    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (isLinkLikeEntry(root)) {
            Files.deleteIfExists(root);
            return;
        }
        Path treeReal = root.toRealPath();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (isLinkLikeEntry(dir) || !realPathStaysWithin(dir, treeReal)) {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // The walker looked the entry's attributes up before the directory stream
                // that yielded it was opened; a directory swapped in that window leaves a
                // path that now resolves outside the tree, and an unchecked delete would
                // remove the file the swapped-in directory actually holds. A file is
                // deleted only when its real path still stays within the tree; an entry
                // that is itself a link (a broken link resolves nowhere and fails closed)
                // is removed as the entry, never followed.
                if (realPathStaysWithin(file, treeReal)) {
                    Files.delete(file);
                } else if (isLinkLikeEntry(file)) {
                    Files.deleteIfExists(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Link-safe deletion from a platform-owned base (spec 5.2): the deletion root must
     * still be reached from {@code canonicalBase} through canonical parents -- no
     * junction or symbolic link may sit at or between them -- before anything is
     * deleted. A junction strictly above the deletion root would otherwise redirect
     * the walk into the link target's tree; when the check cannot be satisfied, the
     * deletion is refused and nothing is deleted. A deletion root that does not exist
     * at all is a no-op: there is nothing to delete, so no chain needs to resolve.
     *
     * @param canonicalBase the platform-owned root as adopted at construction (its real path)
     * @param root the deletion root, spelled below {@code canonicalBase}
     */
    public static void deleteTree(Path canonicalBase, Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!reachedThroughCanonicalParents(canonicalBase, root)) {
            throw new IOException("Refusing to delete " + root
                    + ": it is not reached through canonical parents from " + canonicalBase
                    + " (a link above the deletion root would redirect the deletion outside the owned tree)");
        }
        deleteTree(root);
    }

    /**
     * True when {@code target} is still reached from {@code canonicalBase} without crossing
     * a link: the base must be exactly its own real path (a junction replacing the base, or
     * any of its ancestors, fails here) and each component between the base and the target
     * must resolve exactly to the path its canonical parent implies, so a link strictly
     * above the deletion root is detected instead of being walked through. A chain that
     * cannot be resolved fails the check: nothing is deleted on a guess.
     */
    public static boolean reachedThroughCanonicalParents(Path canonicalBase, Path target) {
        try {
            if (!canonicalBase.toRealPath().equals(canonicalBase)) {
                return false;
            }
            Path relative = canonicalBase.relativize(target);
            if (relative.isAbsolute()) {
                return false;
            }
            if (relative.getNameCount() == 0) {
                return true; // the base itself
            }
            if ("..".equals(relative.getName(0).toString())) {
                return false; // spelled outside the base
            }
            Path real = canonicalBase;
            for (Path component : relative) {
                Path spelled = real.resolve(component.getFileName());
                Path resolved = spelled.toRealPath();
                if (!resolved.equals(spelled)) {
                    return false;
                }
                real = resolved;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }


    /**
     * True when an entry is a symbolic link or a Windows junction / reparse point, or
     * resolves to a directory that its parent does not imply (a link in disguise): such
     * an entry is removed as itself and never descended into. An entry whose type cannot
     * be established is also reported as link-like, so an unknown entry is never followed.
     */
    public static boolean isLinkLikeEntry(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()) {
                return true;
            }
            Path parent = path.getParent();
            if (parent == null) {
                return false;
            }
            return !path.toRealPath().equals(parent.toRealPath().resolve(path.getFileName()));
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean realPathStaysWithin(Path path, Path treeReal) {
        try {
            return path.toRealPath().startsWith(treeReal);
        } catch (IOException e) {
            return false;
        }
    }

    /** Runs a platform-owned common-git-directory mutation under the shared serialization lock. */
    public static <T> T withCommonGitLock(GitMutation<T> mutation) throws IOException {
        COMMON_GIT_LOCK.lock();
        try {
            return mutation.run();
        } finally {
            COMMON_GIT_LOCK.unlock();
        }
    }

    /** A git invocation that mutates the shared common git directory. */
    @FunctionalInterface
    public interface GitMutation<T> {
        T run() throws IOException;
    }
}
