/* ---------------------------------------------------------------
Práctica 1.
Código fuente: SolverRec.java
Grau GEIADE
41533494W - Antonio Cayuela Lopez
48054965F - Alejandro Fernandez Mimbrera
--------------------------------------------------------------- */

/*
Sudoku - a fast Java Sudoku game creation library.
Copyright (C) 2017-2018  Stephan Fuhrmann

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Library General Public
License as published by the Free Software Foundation; either
version 2 of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Library General Public License for more details.

You should have received a copy of the GNU Library General Public
License along with this library; if not, write to the
Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
Boston, MA  02110-1301, USA.
 */
package de.sfuhrm.sudoku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Solves a partially filled Sudoku. Can find multiple solutions if they are
 * there.
 *
 * @author Stephan Fuhrmann
 */
public final class SolverRec {

    /**
     * Current working copy.
     */
    private final CachedGameMatrixImpl riddle;

    /**
     * The possible solutions for this riddle.
     */
    private final List<GameMatrix> possibleSolutions;

    /** The default limit.
     * @see #limit
     */
    public static final int DEFAULT_LIMIT = 1;

    /**
     * The maximum number of solutions to search.
     */
    private int limit;

    /**
     * Maximum number of concurrent threads allowed for the recursive solver.
     * It is computed as min(requestedByUser, availableProcessors), with sane fallbacks.
     */
    private final int maxThreads;

    /** Cooperative cancellation and publication of result (no synchronization). */
    private volatile boolean cancel = false;
    private volatile GameMatrix found = null;

    /**
     * Counter for recursive calls on backtrack algorithm (Atomic to avoid synchronized).
     */
    private static final java.util.concurrent.atomic.AtomicLong recursive_calls = new java.util.concurrent.atomic.AtomicLong(0);
    public static long start;

    private static final long DEFAULT_STATS_STEP = 1000000;

    /**
     * Creates a solver for the given riddle.
     *
     * @param solveMe the riddle to solve.
     */
    public SolverRec(final GameMatrix solveMe) {
        Objects.requireNonNull(solveMe, "solveMe is null");
        limit = DEFAULT_LIMIT;
        riddle = new CachedGameMatrixImpl(solveMe.getSchema());
        riddle.setAll(solveMe.getArray());
        possibleSolutions = new ArrayList<>();

        // Determine the maximum number of threads to use:
        // - Default: number of available processors (>=1).
        // - If user specified -p via Client, we read it from "sudoku.threads".
        // - If user asks for more than cores, cap to cores. If invalid (<=0), fallback to 1.
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int requested = cores; // default if property is missing

        String threadsProp = System.getProperty("sudoku.threads");
        if (threadsProp != null) {
            try {
                requested = Integer.parseInt(threadsProp.trim());
            } catch (NumberFormatException ignored) {
                requested = cores;
            }
        }

        if (requested <= 1) {
            requested = cores;
        }

        this.maxThreads = Math.min(requested, cores);
    }

    public static long getRecursive_calls() {
        return recursive_calls.get();
    }

    public static void setRecursive_calls(long value) {
        recursive_calls.set(value);
    }

    public static long incRecursive_calls() {
        return recursive_calls.incrementAndGet();
    }

    /** Set the limit for maximum results.
     * @param set the new limit.
     */
    public void setLimit(final int set) {
        this.limit = set;
    }

    /** Effective maximum number of threads this solver will use. */
    public int getMaxThreads() {
        return maxThreads;
    }

    public List<GameMatrix> solve() {
        start = System.currentTimeMillis();
        cancel = false;
        found = null;
        possibleSolutions.clear();
        setRecursive_calls(0);
        System.out.printf("Iniciando resolución concurrente recursiva con %d hilos.%n", maxThreads);

        final int total = riddle.getSchema().getTotalFields();
        final int freeCells = total - riddle.getSetCount();

        // Trivial: already solved
        if (freeCells == 0) {
            GameMatrix gmi = new GameMatrixImpl(riddle.getSchema());
            gmi.setAll(riddle.getArray());
            possibleSolutions.add(gmi);
            return Collections.unmodifiableList(possibleSolutions);
        }

        // Build up to maxThreads distinct starting boards (seeds) using a small BFS.
        // Rule: apply forced moves (cells with exactly 1 candidate) INLINE on the same board,
        // and only branch/enqueue when a cell has >=2 candidates.
        List<CachedGameMatrixImpl> seedBoards = new ArrayList<>(maxThreads);
        List<Integer> seedFree = new ArrayList<>(maxThreads);

        java.util.ArrayDeque<CachedGameMatrixImpl> qBoards = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> qFree = new java.util.ArrayDeque<>();

        // Start from a clean copy of the initial board
        CachedGameMatrixImpl startBoard = new CachedGameMatrixImpl(riddle.getSchema());
        startBoard.setAll(riddle.getArray());
        qBoards.add(startBoard);
        qFree.add(freeCells);

        CellIndex tmp = new CellIndex();

        while (seedBoards.size() < maxThreads && !qBoards.isEmpty()) {
            CachedGameMatrixImpl cur = qBoards.removeFirst();
            int curFree = qFree.removeFirst();

            // compress by applying single-candidate cells inline
            boolean expanded = false;
            while (true) {
                if (curFree == 0) {
                    // already solved -> accept as a seed
                    seedBoards.add(cur);
                    seedFree.add(curFree);
                    expanded = true;
                    break;
                }
                GameMatrixImpl.FreeCellResult r = cur.findLeastFreeCell(tmp);
                if (r != GameMatrixImpl.FreeCellResult.FOUND) {
                    // dead end
                    expanded = true;
                    break;
                }
                int rr = tmp.row, cc = tmp.column;
                int m = cur.getFreeMask(rr, cc);
                int cnt = Integer.bitCount(m);
                if (cnt == 0) {
                    // dead end
                    expanded = true;
                    break;
                }
                if (cnt == 1) {
                    // forced move: stay on this same board; do not enqueue
                    int v = Creator.getSetBitOffset(m, 0);
                    cur.set(rr, cc, (byte) v);
                    curFree -= 1;
                    continue; // continue compressing
                } else {
                    // branching point: generate children
                    boolean enqueuedOneForDeeper = false;
                    for (int b = 0; b < cnt; b++) {
                        int v = Creator.getSetBitOffset(m, b);
                        CachedGameMatrixImpl child = new CachedGameMatrixImpl(cur.getSchema());
                        child.setAll(cur.getArray());
                        child.set(rr, cc, (byte) v);

                        if (seedBoards.size() < maxThreads) {
                            // promote to seed
                            seedBoards.add(child);
                            seedFree.add(curFree - 1);
                            // also enqueue ONE child to allow deeper expansion (use a distinct copy to avoid aliasing)
                            if (!enqueuedOneForDeeper) {
                                CachedGameMatrixImpl childForQueue = new CachedGameMatrixImpl(cur.getSchema());
                                childForQueue.setAll(child.getArray());
                                qBoards.add(childForQueue);
                                qFree.add(curFree - 1);
                                enqueuedOneForDeeper = true;
                            }
                        } else {
                            // we already have enough seeds; further children not needed
                            break;
                        }
                    }
                    expanded = true;
                    break;
                }
            }
            if (!expanded) {
                // should not happen, but keep loop safe
                break;
            }
        }

        // If no viable seeds, return (unsatisfiable)
        if (seedBoards.isEmpty()) {
            long endEmpty = System.currentTimeMillis();
            System.out.printf("[%3.3f] SUDOKU DONE. Free Cells: %d. Solutions Found: %d.%n%n",
                    (endEmpty - start) / 1000.0, freeCells, possibleSolutions.size());
            return Collections.unmodifiableList(possibleSolutions);
        }

        int workersToStart = Math.min(maxThreads, seedBoards.size());
        List<Thread> workers = new ArrayList<>(workersToStart);
        List<Worker> tasks = new ArrayList<>(workersToStart);

        for (int i = 0; i < workersToStart; i++) {
            CachedGameMatrixImpl sb = seedBoards.get(i);
            int sf = seedFree.get(i);
            Worker w = new Worker(i, sb, sf);
            Thread t = new Thread(w, "SolverRec-Worker-" + i);
            tasks.add(w);
            workers.add(t);
            t.start();
        }

        // Polling loop (no join, no wait/notify)
        try {
            while (found == null) {
                boolean anyAlive = false;
                for (Thread t : workers) {
                    if (t.isAlive()) { anyAlive = true;
                        break;
                    }
                }
                if (!anyAlive) break; // all finished
                try {
                    Thread.sleep(3); // be nice to CPU
                } catch (InterruptedException ignored) {
                    break;
                }
                // Optional: early pick if any worker reported solved
                for (Worker w : tasks) {
                    if (w.solved && found == null && w.mySolution != null) {
                        found = w.mySolution;
                        break;
                    }
                }
            }
        } finally {
            // Best-effort cooperative stop (workers check cancel)
            cancel = (found != null);
        }

        if (found != null && possibleSolutions.size() < limit) {
            possibleSolutions.add(found);
        }

        long end = System.currentTimeMillis();
        System.out.printf("[%3.3f] SUDOKU DONE. Free Cells: %d. Solutions Found: %d.%n%n",
                (end - start) / 1000.0, freeCells, possibleSolutions.size());

        return Collections.unmodifiableList(possibleSolutions);
    }


    // Pure sequential backtracking. If 'self' is not null, it may publish a solution and set cancel.
    private int backtrack(final int freeCells, final CellIndex minimumCell,
                          final CachedGameMatrixImpl board, final Worker self) {
        assert freeCells >= 0 : "freeCells is negative";

        if ((this.incRecursive_calls() % DEFAULT_STATS_STEP) == 0) {
            long now = System.currentTimeMillis();
            Thread cur = Thread.currentThread();
            System.out.printf("[%3.3f] [TID=%d|%s] Recursive Calls: %d. Free Cells: %d. Solutions Found: %d.%n",
                    (now - start) / 1000.0,
                    cur.getId(), cur.getName(),
                    this.getRecursive_calls(), freeCells, possibleSolutions.size());
        }

        if (cancel) {
            return 0;
        }
        if (possibleSolutions.size() >= limit || found != null) {
            cancel = true;
            return 0;
        }

        if (freeCells == 0) {
            GameMatrix gmi = new GameMatrixImpl(board.getSchema());
            gmi.setAll(board.getArray());
            if (self != null && !self.solved && found == null) {
                Thread cur = Thread.currentThread();
                System.out.printf("[%.3f] [TID=%d|%s] -> SOLUCIÓN ENCONTRADA por worker #%d%n",
                        (System.currentTimeMillis() - start) / 1000.0,
                        cur.getId(), cur.getName(), self.id);
                self.mySolution = gmi;
                self.solved = true;
                cancel = true; // stop others cooperatively
            }
            return 1;
        }

        GameMatrixImpl.FreeCellResult freeCellResult = board.findLeastFreeCell(minimumCell);
        if (freeCellResult != GameMatrixImpl.FreeCellResult.FOUND) {
            return 0;
        }

        int result = 0;
        int r = minimumCell.row;
        int c = minimumCell.column;
        int freeMask = board.getFreeMask(r, c);
        int count = Integer.bitCount(freeMask);

        for (int b = 0; b < count; b++) {
            if (cancel) break;
            int val = Creator.getSetBitOffset(freeMask, b);
            assert val > 0;

            board.set(r, c, (byte) val);
            result += backtrack(freeCells - 1, minimumCell, board, self);
        }

        board.set(r, c, board.getSchema().getUnsetValue());
        return result;
    }

    /** First-level worker: explores one branch sequentially, cooperatively checking cancel. */
    private final class Worker implements Runnable {
        private final int id;
        private final CachedGameMatrixImpl board;
        private final int initialFreeCells;
        volatile boolean solved = false;
        volatile GameMatrix mySolution = null;

        Worker(int id, CachedGameMatrixImpl board, int freeCells) {
            this.id = id;
            this.board = board;
            this.initialFreeCells = freeCells;
        }

        @Override
        public void run() {
            try {
                Thread cur = Thread.currentThread();
                System.out.printf("[%.3f] [TID=%d|%s] Worker #%d START%n",
                        (System.currentTimeMillis() - start) / 1000.0,
                        cur.getId(), cur.getName(), id);
                backtrack(initialFreeCells, new CellIndex(), board, this);
            } catch (Throwable ex) {
                System.err.println("Worker " + id + " error: " + ex.getMessage());
            }
        }
    }
}