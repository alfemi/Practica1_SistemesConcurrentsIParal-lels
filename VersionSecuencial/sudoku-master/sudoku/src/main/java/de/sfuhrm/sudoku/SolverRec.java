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

    // --- Minimal concurrency state (no high-level APIs) ---
    private final Object monitor = new Object(); // monitor for wait/notify
    private int activeTasks = 0;                 // number of worker threads currently running
    private volatile boolean cancel = false;     // set to true when solution limit is reached

    /**
     * Counter for recursive calls on bactrack algorithm
     */
    private static long recursive_calls=0;
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

        if (requested <= 0) {
            requested = cores;
        }

        this.maxThreads = Math.min(requested, cores);
    }

    public static synchronized long getRecursive_calls() {
        return recursive_calls;
    }

    public static synchronized void setRecursive_calls(long recursive_calls) {
        SolverRec.recursive_calls = recursive_calls;
    }

    public static synchronized long incRecursive_calls() {
        return ++SolverRec.recursive_calls;
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

    private boolean canSpawnThread() {
        synchronized (monitor) {
            return activeTasks < Math.max(1, maxThreads);
        }
    }

    private void beginTask() {
        synchronized (monitor) { activeTasks++; }
    }

    private void endTask() {
        synchronized (monitor) {
            activeTasks--;
            if (activeTasks <= 0) {
                monitor.notifyAll();
            }
        }
    }

    /**
     * Solves the Sudoku problem.
     *
     * @return the found solutions. Should be only one.
     */
    public List<GameMatrix> solve() {
        start = System.currentTimeMillis();
        cancel = false;
        possibleSolutions.clear();
        int freeCells = riddle.getSchema().getTotalFields() - riddle.getSetCount();

        // Start search on the current thread
        backtrack(freeCells, new CellIndex());

        // Wait for background workers (spawned tasks) to finish without using join
        synchronized (monitor) {
            while (activeTasks > 0 && !cancel) {
                try {
                    monitor.wait();
                } catch (InterruptedException ignored) {
                    // If interrupted, break early but keep state consistent
                    break;
                }
            }
        }

        long end = System.currentTimeMillis();
        System.out.printf("[%3.3f] SUDOKU DONE. Recursive Calls: %d. Free Cells: %d. Solutions Found: %d.%n%n",
                (end - start) / 1000.0, this.getRecursive_calls(), freeCells, possibleSolutions.size());

        return Collections.unmodifiableList(possibleSolutions);
    }

    private int backtrack(final int freeCells, final CellIndex minimumCell) {
        return backtrack(freeCells, minimumCell, this.riddle);
    }

    // Core backtracking that works on the provided board instance (can be a copy for worker threads)
    private int backtrack(final int freeCells, final CellIndex minimumCell, final CachedGameMatrixImpl board) {
        assert freeCells >= 0 : "freeCells is negative";

        if ((this.incRecursive_calls() % DEFAULT_STATS_STEP) == 0) {
            long now = System.currentTimeMillis();
            System.out.printf("[%3.3f] Recursive Calls: %d. Free Cells: %d. Solutions Found: %d.%n",
                    (now - start) / 1000.0, this.getRecursive_calls(), freeCells, possibleSolutions.size());
        }

        // Early exit if we reached the solution limit or a global cancel was requested
        if (cancel) {
            return 0;
        }
        if (possibleSolutions.size() >= limit) {
            cancel = true;
            synchronized (monitor) { monitor.notifyAll(); }
            return 0;
        }

        // Base case: solution found
        if (freeCells == 0) {
            GameMatrix gmi = new GameMatrixImpl(board.getSchema());
            gmi.setAll(board.getArray());
            synchronized (possibleSolutions) {
                if (!cancel && possibleSolutions.size() < limit) {
                    possibleSolutions.add(gmi);
                    if (possibleSolutions.size() >= limit) {
                        cancel = true;
                        synchronized (monitor) { monitor.notifyAll(); }
                    }
                }
            }
            return 1;
        }

        GameMatrixImpl.FreeCellResult freeCellResult = board.findLeastFreeCell(minimumCell);
        if (freeCellResult != GameMatrixImpl.FreeCellResult.FOUND) {
            // dead end
            return 0;
        }

        int result = 0;
        int minimumRow = minimumCell.row;
        int minimumColumn = minimumCell.column;
        int minimumFree = board.getFreeMask(minimumRow, minimumColumn);
        int minimumBits = Integer.bitCount(minimumFree);

        // Explore candidates
        for (int bit = 0; bit < minimumBits; bit++) {
            if (cancel) break; // cooperative cancellation

            int index = Creator.getSetBitOffset(minimumFree, bit);
            assert index > 0;

            // Decide: spawn a worker thread or continue inline (sequentially)
            if (canSpawnThread()) {
                // Prepare an isolated copy for the worker
                final CachedGameMatrixImpl child = new CachedGameMatrixImpl(board.getSchema());
                child.setAll(board.getArray());
                child.set(minimumRow, minimumColumn, (byte) index);
                final int childFree = freeCells - 1;

                beginTask();
                Thread t = new Thread(() -> {
                    try {
                        backtrack(childFree, new CellIndex(), child);
                    } catch (Throwable ex) {
                        // Robust error handling to avoid silent thread death
                        System.err.println("Worker error: " + ex.getMessage());
                    } finally {
                        endTask();
                    }
                });
                t.start();
            } else {
                // Continue in current thread to respect maxThreads
                board.set(minimumRow, minimumColumn, (byte) index);
                int rc = backtrack(freeCells - 1, minimumCell, board);
                result += rc;
            }
        }

        // Undo assignment if we modified current board in this frame
        board.set(minimumRow, minimumColumn, board.getSchema().getUnsetValue());

        return result;
    }
}