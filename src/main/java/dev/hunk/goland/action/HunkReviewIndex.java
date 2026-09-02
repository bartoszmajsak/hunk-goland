package dev.hunk.goland.action;

import dev.hunk.goland.model.HunkReviewInput;
import dev.hunk.goland.model.HunkReviewNote;
import dev.hunk.goland.model.ReviewSide;
import dev.hunk.goland.model.ReviewTarget;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Immutable navigation projection of the right-side line threads in one live Hunk review. */
record HunkReviewIndex(
        Path repositoryRoot,
        HunkReviewInput reviewInput,
        List<CommentedFile> files
) {
    static HunkReviewIndex empty() {
        return new HunkReviewIndex(Path.of("."), HunkReviewInput.workingTree(), List.of());
    }

    static HunkReviewIndex from(ReviewTarget context, List<HunkReviewNote> notes) {
        var notesByFile = new LinkedHashMap<String, List<HunkReviewNote>>();
        notes.stream()
                .filter(reviewNote -> reviewNote.note().side() == ReviewSide.NEW)
                .collect(java.util.stream.Collectors.groupingBy(
                        HunkReviewNote::filePath,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ))
                .forEach(notesByFile::put);

        var files = notesByFile.entrySet().stream()
                .map(entry -> commentedFile(entry.getKey(), entry.getValue()))
                .toList();
        return new HunkReviewIndex(context.repositoryRoot(), context.reviewInput(), files);
    }

    private static CommentedFile commentedFile(String filePath, List<HunkReviewNote> notes) {
        var threads = notes.stream()
                .map(note -> new ThreadLocation(filePath, note.note().line()))
                .distinct()
                .sorted(Comparator.comparingInt(ThreadLocation::line))
                .toList();
        return new CommentedFile(filePath, threads, notes.size());
    }

    List<ThreadLocation> threads() {
        return files.stream().flatMap(file -> file.threads().stream()).toList();
    }

    ThreadLocation adjacentTo(ReviewTarget current, Direction direction) {
        var threads = threads();
        if (threads.isEmpty()) {
            return null;
        }
        var exact = new ThreadLocation(current.filePath(), current.line());
        var currentIndex = threads.indexOf(exact);
        if (currentIndex >= 0) {
            return threads.get(Math.floorMod(currentIndex + direction.step(), threads.size()));
        }

        var fileRanks = new java.util.HashMap<String, Integer>();
        for (var index = 0; index < files.size(); index++) {
            fileRanks.put(files.get(index).filePath(), index);
        }
        var ordering = Comparator
                .comparingInt((ThreadLocation thread) -> fileRanks.getOrDefault(
                        thread.filePath(),
                        Integer.MAX_VALUE
                ))
                .thenComparingInt(ThreadLocation::line);
        if (direction == Direction.NEXT) {
            return threads.stream()
                    .filter(thread -> ordering.compare(thread, exact) > 0)
                    .findFirst()
                    .orElse(threads.getFirst());
        }
        return threads.reversed().stream()
                .filter(thread -> ordering.compare(thread, exact) < 0)
                .findFirst()
                .orElse(threads.getLast());
    }

    int itemCount() {
        return files.stream().mapToInt(CommentedFile::itemCount).sum();
    }

    enum Direction {
        PREVIOUS(-1),
        NEXT(1);

        private final int step;

        Direction(int step) {
            this.step = step;
        }

        int step() {
            return step;
        }
    }

    record CommentedFile(String filePath, List<ThreadLocation> threads, int itemCount) {
        int threadCount() {
            return threads.size();
        }
    }

    record ThreadLocation(String filePath, int line) {
        String displayName() {
            return filePath + ":" + line;
        }
    }
}
