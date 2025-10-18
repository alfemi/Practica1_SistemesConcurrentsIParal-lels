/* ---------------------------------------------------------------
Práctica 1.
Código fuente: SolverIt.java
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SolverIt {

    private final CachedGameMatrixImpl riddle;
    private final List<GameMatrix> possibleSolutions;
    public static final int DEFAULT_LIMIT = 1;
    private int limit;

    public SolverIt(final GameMatrix solveMe) {
        Objects.requireNonNull(solveMe, "solveMe is null");
        limit = DEFAULT_LIMIT;
        riddle = new CachedGameMatrixImpl(solveMe.getSchema());
        riddle.setAll(solveMe.getArray());
        possibleSolutions = new ArrayList<>();
    }

    public void setLimit(final int set) {
        this.limit = set;
    }

    static final class State {
        final CachedGameMatrixImpl board;
        final int freeCells;
        State(CachedGameMatrixImpl board, int freeCells) {
            this.board = board;
            this.freeCells = freeCells;
        }
    }

    private static CachedGameMatrixImpl copyOf(CachedGameMatrixImpl src) {
        CachedGameMatrixImpl c = new CachedGameMatrixImpl(src.getSchema());
        c.setAll(src.getArray());
        return c;
    }

    public List<GameMatrix> solve() {
        long start = System.currentTimeMillis();
        possibleSolutions.clear();

        int freeCells = riddle.getSchema().getTotalFields() - riddle.getSetCount();

        BlockingQueue<State> queue = new LinkedBlockingQueue<>();
        queue.offer(new State(copyOf(riddle), freeCells));

        List<GameMatrix> out = Collections.synchronizedList(possibleSolutions);
        AtomicBoolean stop = new AtomicBoolean(false);

        int nThreads = Integer.getInteger("sudoku.threads",
                Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);

        Runnable worker = () -> {
            while (!stop.get()) {
                State s;
                try {
                    s = queue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (s == null) {
                    if (stop.get() || queue.isEmpty()) return;
                    continue;
                }
                if (out.size() >= limit) { stop.set(true); return; }

                if (s.freeCells == 0) {
                    GameMatrix g = new GameMatrixImpl(s.board.getSchema());
                    g.setAll(s.board.getArray());
                    synchronized (out) {
                        if (out.size() < limit) out.add(g);
                        if (out.size() >= limit) stop.set(true);
                    }
                    continue;
                }

                CellIndex cell = new CellIndex();
                GameMatrixImpl.FreeCellResult fr = s.board.findLeastFreeCell(cell);
                if (fr != GameMatrixImpl.FreeCellResult.FOUND) {
                    continue;
                }

                int mask = s.board.getFreeMask(cell.row, cell.column);
                int bits = Integer.bitCount(mask);
                for (int b = 0; b < bits && !stop.get(); b++) {
                    int idx = Creator.getSetBitOffset(mask, b);
                    CachedGameMatrixImpl c = new CachedGameMatrixImpl(s.board.getSchema());
                    c.setAll(s.board.getArray());
                    c.set(cell.row, cell.column, (byte) idx);
                    queue.offer(new State(c, s.freeCells - 1));
                }
            }
        };

        for (int i = 0; i < nThreads; i++) pool.submit(worker);
        pool.shutdown();
        try { pool.awaitTermination(10, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}

        long end = System.currentTimeMillis();
        System.out.printf("[%3.3f] SUDOKU DONE (Iter). Free Cells: %d. Solutions Found: %d.%n%n",
                (end - start) / 1000.0, freeCells, out.size());

        return Collections.unmodifiableList(out);
    }
}