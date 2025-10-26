/* ---------------------------------------------------------------
Práctica 1.
Código fuente: SolverRec.java
Grau GEIADE
41533494W - Antonio Cayuela Lopez
48054965F - Alejandro Fernandez Mimbrera
--------------------------------------------------------------- */

package de.sfuhrm.sudoku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SolverRec {

    private final CachedGameMatrixImpl riddle;
    private final List<GameMatrix> possibleSolutions;

    public static final int DEFAULT_LIMIT = 1;
    private int limit;

    // número máximo de hilos usados
    private final int maxThreads;

    // control para parar cuando haya solución
    private volatile boolean cancel = false;
    private volatile GameMatrix found = null;

    // contador atómico de llamadas recursivas
    private static final java.util.concurrent.atomic.AtomicLong recursive_calls =
            new java.util.concurrent.atomic.AtomicLong(0);
    public static long start;
    private static final long DEFAULT_STATS_STEP = 1000000;

    public SolverRec(final GameMatrix solveMe) {
        Objects.requireNonNull(solveMe, "solveMe is null");
        limit = DEFAULT_LIMIT;
        riddle = new CachedGameMatrixImpl(solveMe.getSchema());
        riddle.setAll(solveMe.getArray());
        possibleSolutions = new ArrayList<>();

        // coge el número de hilos del sistema o por defecto los cores disponibles
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int requested = cores;
        String threadsProp = System.getProperty("sudoku.threads");
        if (threadsProp != null) {
            try { requested = Integer.parseInt(threadsProp.trim()); }
            catch (NumberFormatException ignored) { requested = cores; }
        }
        if (requested <= 1) requested = cores;
        this.maxThreads = Math.min(requested, cores);
    }

    public static long incRecursive_calls() { return recursive_calls.incrementAndGet(); }

    public List<GameMatrix> solve() {
        start = System.currentTimeMillis();
        cancel = false;
        found = null;
        possibleSolutions.clear();
        recursive_calls.set(0);

        System.out.printf("Resolviendo con versión recursiva concurrente (%d hilos)%n", maxThreads);

        int freeCells = riddle.getSchema().getTotalFields() - riddle.getSetCount();

        // generamos unas cuantas "semillas" para repartir trabajo entre hilos
        List<CachedGameMatrixImpl> seedBoards = new ArrayList<>(maxThreads);
        List<Integer> seedFree = new ArrayList<>(maxThreads);
        java.util.ArrayDeque<CachedGameMatrixImpl> qBoards = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> qFree = new java.util.ArrayDeque<>();

        CachedGameMatrixImpl startBoard = new CachedGameMatrixImpl(riddle.getSchema());
        startBoard.setAll(riddle.getArray());
        qBoards.add(startBoard);
        qFree.add(freeCells);
        CellIndex tmp = new CellIndex();

        // expansión simple hasta tener una semilla por hilo
        while (seedBoards.size() < maxThreads && !qBoards.isEmpty()) {
            CachedGameMatrixImpl cur = qBoards.removeFirst();
            int curFree = qFree.removeFirst();

            while (true) {
                if (curFree == 0) {
                    seedBoards.add(cur);
                    seedFree.add(curFree);
                    break;
                }
                GameMatrixImpl.FreeCellResult r = cur.findLeastFreeCell(tmp);
                if (r != GameMatrixImpl.FreeCellResult.FOUND) break;

                int rr = tmp.row, cc = tmp.column;
                int m = cur.getFreeMask(rr, cc);
                int cnt = Integer.bitCount(m);
                if (cnt == 0) break;

                if (cnt == 1) {
                    // aplicamos movimientos forzados
                    int v = Creator.getSetBitOffset(m, 0);
                    cur.set(rr, cc, (byte) v);
                    curFree--;
                } else {
                    // en celdas con varias opciones creamos nuevas ramas
                    for (int b = 0; b < cnt && seedBoards.size() < maxThreads; b++) {
                        int v = Creator.getSetBitOffset(m, b);
                        CachedGameMatrixImpl child = new CachedGameMatrixImpl(cur.getSchema());
                        child.setAll(cur.getArray());
                        child.set(rr, cc, (byte) v);
                        seedBoards.add(child);
                        seedFree.add(curFree - 1);
                    }
                    break;
                }
            }
        }

        if (seedBoards.isEmpty()) return Collections.unmodifiableList(possibleSolutions);

        // lanzamos un hilo por semilla
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < Math.min(maxThreads, seedBoards.size()); i++) {
            CachedGameMatrixImpl sb = seedBoards.get(i);
            int sf = seedFree.get(i);
            Thread t = new Thread(new Worker(i, sb, sf), "SolverRec-" + i);
            workers.add(t);
            t.start();
        }

        // esperamos a que acaben (o alguien encuentre solución)
        try {
            while (found == null) {
                boolean anyAlive = false;
                for (Thread t : workers) if (t.isAlive()) anyAlive = true;
                if (!anyAlive) break;
                Thread.sleep(3);
            }
        } catch (InterruptedException ignored) {}

        cancel = true;
        if (found != null) possibleSolutions.add(found);

        long end = System.currentTimeMillis();
        System.out.printf("[%3.3f] Terminado. Soluciones: %d%n",
                (end - start) / 1000.0, possibleSolutions.size());
        return Collections.unmodifiableList(possibleSolutions);
    }

    // versión recursiva clásica adaptada a hilos
    private int backtrack(final int freeCells, final CellIndex cell,
                          final CachedGameMatrixImpl board, final Worker self) {

        if (cancel) return 0;
        if (freeCells == 0) {
            GameMatrix gmi = new GameMatrixImpl(board.getSchema());
            gmi.setAll(board.getArray());
            if (found == null) {
                found = gmi;
                cancel = true;
            }
            return 1;
        }

        GameMatrixImpl.FreeCellResult res = board.findLeastFreeCell(cell);
        if (res != GameMatrixImpl.FreeCellResult.FOUND) return 0;

        int mask = board.getFreeMask(cell.row, cell.column);
        int bits = Integer.bitCount(mask);

        for (int b = 0; b < bits && !cancel; b++) {
            int val = Creator.getSetBitOffset(mask, b);
            board.set(cell.row, cell.column, (byte) val);
            backtrack(freeCells - 1, cell, board, self);
        }

        board.set(cell.row, cell.column, board.getSchema().getUnsetValue());
        return 0;
    }

    private final class Worker implements Runnable {
        private final int id;
        private final CachedGameMatrixImpl board;
        private final int initialFreeCells;

        Worker(int id, CachedGameMatrixImpl board, int freeCells) {
            this.id = id;
            this.board = board;
            this.initialFreeCells = freeCells;
        }

        @Override
        public void run() {
            backtrack(initialFreeCells, new CellIndex(), board, this);
        }
    }
}