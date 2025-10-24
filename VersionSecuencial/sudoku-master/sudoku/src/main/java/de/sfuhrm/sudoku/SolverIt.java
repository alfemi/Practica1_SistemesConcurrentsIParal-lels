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
/*
Sudoku - a fast Java Sudoku game creation library.
Copyright (C) 2017-2018  Stephan Fuhrmann
LGPL v2.1 or later.
*/
package de.sfuhrm.sudoku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    public void setLimit(final int set) { this.limit = set; }

    static final class State {
        final CachedGameMatrixImpl board;
        final int free;
        State(CachedGameMatrixImpl b, int f){ board = b; free = f; }
    }

    private static CachedGameMatrixImpl copyOf(CachedGameMatrixImpl src) {
        CachedGameMatrixImpl c = new CachedGameMatrixImpl(src.getSchema());
        c.setAll(src.getArray());
        return c;
    }

    public List<GameMatrix> solve() {
        possibleSolutions.clear();

        int freeCells = riddle.getSchema().getTotalFields() - riddle.getSetCount();

        // Cola acotada para evitar OOM
        BlockingQueue<State> q = new ArrayBlockingQueue<>(20_000);
        q.offer(new State(copyOf(riddle), freeCells));

        List<GameMatrix> out = Collections.synchronizedList(possibleSolutions);
        AtomicBoolean stop = new AtomicBoolean(false);

        int nThreads = Integer.getInteger(
                "sudoku.threads",
                Math.max(1, Runtime.getRuntime().availableProcessors())
        );
        System.err.println("[ITER] SolverIt activo con " + nThreads + " hilos.");

        ExecutorService pool = Executors.newFixedThreadPool(nThreads);

        Runnable worker = () -> {
            while (!stop.get()) {
                State s;
                try {
                    s = q.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (s == null) {
                    if (stop.get() || q.isEmpty()) return;
                    continue;
                }
                if (out.size() >= limit) { stop.set(true); return; }

                if (s.free == 0) {
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
                if (mask == 0) {
                    // sin candidatos, poda
                    continue;
                }

                int bits = Integer.bitCount(mask);
                // Limitar fan-out cuando el tablero está muy vacío
                int fanout = (s.free > 120) ? Math.min(bits, 2) : bits;

                for (int b = 0; b < fanout && !stop.get(); b++) {
                    int idx = Creator.getSetBitOffset(mask, b);
                    CachedGameMatrixImpl c = new CachedGameMatrixImpl(s.board.getSchema());
                    c.setAll(s.board.getArray());
                    c.set(cell.row, cell.column, (byte) idx);
                    try {
                        // backpressure para no desbordar la cola
                        q.offer(new State(c, s.free - 1), 50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        };

        for (int i = 0; i < nThreads; i++) pool.submit(worker);
        pool.shutdown();
        try { pool.awaitTermination(10, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}

        return Collections.unmodifiableList(out);
    }
}