/* ---------------------------------------------------------------
Práctica 1.
Código fuente: SolverIt.java
Grau GEIADE
41533494W - Antonio Cayuela Lopez
48054965F - Alejandro Fernandez Mimbrera
--------------------------------------------------------------- */

package de.sfuhrm.sudoku;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class SolverIt {

    private final CachedGameMatrixImpl riddle;
    private List<GameMatrix> possibleSolutions;

    public static final int DEFAULT_LIMIT = 1;
    private int limit;

    private static AtomicLong recursive_calls = new AtomicLong(0);
    private static final AtomicLong tasks_created = new AtomicLong(0);

    public static long start;
    private static final long DEFAULT_STATS_STEP = 1000000;

    private int numThreads = 1;
    private ExecutorService threadPool;
    private PriorityBlockingQueue<SudokuTask> taskQueue;
    private AtomicInteger activeTasks;
    private AtomicInteger solutionsFound;
    private ConcurrentLinkedQueue<GameMatrix> concurrentSolutions;

    public SolverIt(final GameMatrix solveMe) {
        Objects.requireNonNull(solveMe, "solveMe is null");
        limit = DEFAULT_LIMIT;
        riddle = new CachedGameMatrixImpl(solveMe.getSchema());
        riddle.setAll(solveMe.getArray());
    }

    // fija el número de hilos respetando el límite del sistema
    public void setNumThreads(final int threads) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        numThreads = (threads <= 1) ? cores : Math.min(threads, cores);
    }

    public List<GameMatrix> solve() {
        start = System.currentTimeMillis();
        recursive_calls.set(0);
        tasks_created.set(0);

        concurrentSolutions = new ConcurrentLinkedQueue<>();
        possibleSolutions = new ArrayList<>();
        solutionsFound = new AtomicInteger(0);

        int freeCells = riddle.getSchema().getTotalFields() - riddle.getSetCount();
        System.out.printf("Resolviendo con versión iterativa concurrente (%d hilos)%n", numThreads);

        if (numThreads > 1) solveIterative(freeCells);
        else {
            CachedGameMatrixImpl copy = new CachedGameMatrixImpl(riddle.getSchema());
            copy.setAll(riddle.getArray());
            backtrack(freeCells, new CellIndex(), copy);
        }

        possibleSolutions.addAll(concurrentSolutions);
        long end = System.currentTimeMillis();
        System.out.printf("[%3.3f] Terminado. Soluciones: %d%n",
                (end - start) / 1000.0, possibleSolutions.size());
        return Collections.unmodifiableList(possibleSolutions);
    }

    // versión iterativa: usa cola de tareas y un pool fijo
    private void solveIterative(int freeCells) {
        threadPool = Executors.newFixedThreadPool(numThreads);
        taskQueue = new PriorityBlockingQueue<>();
        activeTasks = new AtomicInteger(0);

        taskQueue.add(new SudokuTask(riddle, freeCells));
        activeTasks.incrementAndGet();

        for (int i = 0; i < numThreads; i++) {
            threadPool.submit(() -> {
                try {
                    while (solutionsFound.get() == 0) {
                        SudokuTask t = taskQueue.poll(200, TimeUnit.MILLISECONDS);
                        if (t == null) continue;
                        processTask(t);
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        while (taskQueue.size() > 0 || activeTasks.get() > 0) {
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }

        threadPool.shutdownNow();
    }

    // procesa cada tarea: si puede, genera hijos nuevos
    private void processTask(SudokuTask task) {
        recursive_calls.incrementAndGet();
        CachedGameMatrixImpl b = task.board;
        int free = task.freeCells;
        CellIndex ci = new CellIndex();

        while (true) {
            if (free == 0) {
                GameMatrix gmi = new GameMatrixImpl(b.getSchema());
                gmi.setAll(b.getArray());
                concurrentSolutions.add(gmi);
                solutionsFound.incrementAndGet();
                return;
            }

            GameMatrixImpl.FreeCellResult r = b.findLeastFreeCell(ci);
            if (r != GameMatrixImpl.FreeCellResult.FOUND) return;

            int rr = ci.row, cc = ci.column;
            int mask = b.getFreeMask(rr, cc);
            int cnt = Integer.bitCount(mask);
            if (cnt == 0) return;

            if (cnt == 1) {
                int v = Creator.getSetBitOffset(mask, 0);
                b.set(rr, cc, (byte) v);
                free--;
            } else {
                // generamos una tarea por cada candidato
                for (int i = 0; i < cnt; i++) {
                    int v = Creator.getSetBitOffset(mask, i);
                    CachedGameMatrixImpl child = new CachedGameMatrixImpl(b.getSchema());
                    child.setAll(b.getArray());
                    child.set(rr, cc, (byte) v);
                    taskQueue.add(new SudokuTask(child, free - 1));
                    activeTasks.incrementAndGet();
                }
                return;
            }
        }
    }

    private static class SudokuTask implements Comparable<SudokuTask> {
        final CachedGameMatrixImpl board;
        final int freeCells;
        final int mrv;

        SudokuTask(CachedGameMatrixImpl b, int f) {
            board = new CachedGameMatrixImpl(b.getSchema());
            board.setAll(b.getArray());
            freeCells = f;
            mrv = calcMRV(board);
        }

        // MRV simple: menos candidatos = mayor prioridad
        private int calcMRV(CachedGameMatrixImpl b) {
            if (freeCells == 0) return Integer.MAX_VALUE;
            CellIndex c = new CellIndex();
            b.findLeastFreeCell(c);
            return Integer.bitCount(b.getFreeMask(c.row, c.column));
        }

        public int compareTo(SudokuTask o) {
            return Integer.compare(this.mrv, o.mrv);
        }
    }

    // versión secuencial para comparar
    private int backtrack(final int freeCells,
                          final CellIndex cell,
                          final CachedGameMatrixImpl board) {

        if (freeCells == 0) {
            GameMatrix gmi = new GameMatrixImpl(board.getSchema());
            gmi.setAll(board.getArray());
            concurrentSolutions.add(gmi);
            return 1;
        }

        GameMatrixImpl.FreeCellResult res = board.findLeastFreeCell(cell);
        if (res != GameMatrixImpl.FreeCellResult.FOUND) return 0;

        int mask = board.getFreeMask(cell.row, cell.column);
        int bits = Integer.bitCount(mask);

        for (int b = 0; b < bits; b++) {
            int val = Creator.getSetBitOffset(mask, b);
            board.set(cell.row, cell.column, (byte) val);
            backtrack(freeCells - 1, cell, board);
        }

        board.set(cell.row, cell.column, board.getSchema().getUnsetValue());
        return 0;
    }
}