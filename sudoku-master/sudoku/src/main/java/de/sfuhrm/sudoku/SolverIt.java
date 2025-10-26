/* ---------------------------------------------------------------
Práctica 1.
Código fuente: SolverIt.java
Grau GEIADE
41533494W - Antonio Cayuela Lopez
48054965F - Alejandro Fernandez Mimbrera
--------------------------------------------------------------- */

package de.sfuhrm.sudoku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Solves a partially filled Sudoku. Can find multiple solutions if they are
 * there.
 *
 * MODIFICADO PARA PRÁCTICA 1 SCP (Versión 2: Concurrente Iterativa)
 *
 * @author Stephan Fuhrmann (Base)
 * @author (Tu NIF y Nombre)
 */
public final class SolverIt {

    /**
     * Riddle original (solo lectura).
     */
    private final CachedGameMatrixImpl riddle;

    /**
     * The possible solutions for this riddle.
     */
    private List<GameMatrix> possibleSolutions;

    public static final int DEFAULT_LIMIT = 1;
    private int limit;

    // Contadores globales (atómicos para concurrencia)
    private static AtomicLong recursive_calls = new AtomicLong(0);

    // Contador global de tareas creadas (para trazas periódicas)
    private static final AtomicLong tasks_created = new AtomicLong(0);

    public static long getTasksCreated() { return tasks_created.get(); }
    public static long incTasksCreated() { return tasks_created.incrementAndGet(); }
    public static void resetTasksCreated() { tasks_created.set(0); }

    public static long start;
    private static final long DEFAULT_STATS_STEP = 1000000;

    /** Número de hilos a utilizar (pasado desde -p). */
    private int numThreads = 1;

    // --- Campos para la Versión 2 (Iterativa) ---
    private ExecutorService threadPool;
    private PriorityBlockingQueue<SudokuTask> taskQueue;
    private AtomicInteger activeTasks;
    private AtomicInteger solutionsFound;
    // --- Fin Campos V2 ---

    // New concurrent collection for solutions
    private ConcurrentLinkedQueue<GameMatrix> concurrentSolutions;

    public SolverIt(final GameMatrix solveMe) {
        Objects.requireNonNull(solveMe, "solveMe is null");
        limit = DEFAULT_LIMIT;
        riddle = new CachedGameMatrixImpl(solveMe.getSchema());
        riddle.setAll(solveMe.getArray());
    }

    public static long getRecursive_calls() {
        return recursive_calls.get();
    }

    public static void setRecursive_calls(long v) {
        SolverIt.recursive_calls.set(v);
    }

    public static long incRecursive_calls() {
        return SolverIt.recursive_calls.incrementAndGet();
    }

    public void setNumThreads(final int threads) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int req = threads;
        // Política: si el usuario pasa ≤ 1 (o negativo), usamos el máximo disponible
        if (req <= 1) req = cores;
        // Si pide más que cores, limitamos a cores
        this.numThreads = Math.min(req, cores);
    }

    public void setLimit(final int set) {
        this.limit = set;
    }

    /**
     * Resuelve el Sudoku.
     */
    public List<GameMatrix> solve() {
        start = System.currentTimeMillis();
        setRecursive_calls(0);
        resetTasksCreated();

        concurrentSolutions = new ConcurrentLinkedQueue<>();
        possibleSolutions = new ArrayList<>();

        solutionsFound = new AtomicInteger(0);

        int freeCells = riddle.getSchema().getTotalFields()
                - riddle.getSetCount();

        // Seguridad: aplicar misma política que setNumThreads
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (numThreads <= 1) {
            numThreads = cores; // forzar concurrencia cuando sea posible
        } else {
            numThreads = Math.min(numThreads, cores);
        }

        System.out.printf("Iniciando resolución concurrente iterativa con %d hilos.%n", numThreads);
        if (numThreads > 1) {
            solveIterative(freeCells);
        } else {
            CachedGameMatrixImpl riddleCopy = new CachedGameMatrixImpl(riddle.getSchema());
            riddleCopy.setAll(riddle.getArray());
            backtrack(freeCells, new CellIndex(), riddleCopy);
        }

        // Add all solutions from concurrentSolutions to possibleSolutions before returning
        possibleSolutions.addAll(concurrentSolutions);

        long end = System.currentTimeMillis();
        System.out.printf("[%3.3f] SUDOKU DONE. Recursive Calls: %d. Tareas creadas: %d. Free Cells: %d. Solutions Found: %d. Threads: %d\n\n",
                (end - start) / 1000.0,
                getRecursive_calls(),
                getTasksCreated(),
                freeCells,
                possibleSolutions.size(),
                numThreads);

        return Collections.unmodifiableList(possibleSolutions);
    }

    /**
     * Implementación Concurrente (V2): Robusta al Livelock.
     */
    private void solveIterative(int freeCells) {
        threadPool = Executors.newFixedThreadPool(numThreads);
        taskQueue = new PriorityBlockingQueue<>();
        activeTasks = new AtomicInteger(0);

        CachedGameMatrixImpl initialRiddle = new CachedGameMatrixImpl(riddle.getSchema());
        initialRiddle.setAll(riddle.getArray());

        SudokuTask initialTask = new SudokuTask(initialRiddle, freeCells);
        taskQueue.add(initialTask);
        activeTasks.incrementAndGet();
        long tc0 = incTasksCreated();
        if ((tc0 % DEFAULT_STATS_STEP) == 0) {
            long now = System.currentTimeMillis();
            Thread cur = Thread.currentThread();
            System.out.printf("[%3.3f] [TID=%d|%s] Tareas creadas: %d. En cola: %d. Activas: %d. Soluciones: %d.%n",
                    (now - start) / 1000.0,
                    cur.getId(), cur.getName(),
                    getTasksCreated(), taskQueue.size(), activeTasks.get(), solutionsFound.get());
        }

        // 3. Crear y lanzar los N workers (Usan take() bloqueante)
        for (int i = 0; i < numThreads; i++) {
            Runnable worker = () -> {
                try {
                    while (solutionsFound.get() < limit) {
                        SudokuTask currentTask = taskQueue.take();
                        try {
                            if (solutionsFound.get() < limit) {
                                processTask(currentTask);
                            }
                        } catch (Throwable t) {
                            // Registrar y continuar; evitamos matar el worker
                            // System.err.println("Worker error: " + t.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            threadPool.submit(worker);
        }

        // 4. Esperar a que el trabajo termine (Main Thread)
        while (solutionsFound.get() < limit) {

            // Condición de finalización 1: Cola vacía Y contador de tareas activas a 0.
            if (taskQueue.isEmpty() && activeTasks.get() == 0) {

                // DOBLE CHEQUEO DE INACTIVIDAD: Esperamos para detectar el race condition
                try {
                    // ¡CORRECCIÓN CRÍTICA! Aumentamos el tiempo de espera.
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                // Condición de finalización 2: Si después de 500ms sigue vacío, terminamos.
                if (taskQueue.isEmpty() && activeTasks.get() == 0) {
                    break;
                }
            }

            // Si no se cumplen las condiciones de rotura, esperamos un tiempo breve.
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 5. Forzar la terminación
        threadPool.shutdownNow();

        // 6. Esperar el shutdown final
        try {
            threadPool.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            System.err.println("Resolución concurrente interrumpida.");
            Thread.currentThread().interrupt();
        }
    }

    private void processTask(SudokuTask task) {
        incRecursive_calls();

        // Asegurar decremento de tareas activas en cualquier salida
        boolean decDone = false;
        try {
            if (solutionsFound.get() >= limit) {
                return;
            }

            // Trabajamos sobre copias locales
            CachedGameMatrixImpl B = task.board;
            int free = task.freeCells;

            // 1) Forzados inline: aplicar todas las celdas con 1 candidato
            CellIndex ci = new CellIndex();
            while (true) {
                if (free == 0) {
                    GameMatrix gmi = new GameMatrixImpl(B.getSchema());
                    gmi.setAll(B.getArray());
                    if (solutionsFound.incrementAndGet() <= limit) {
                        concurrentSolutions.add(gmi);
                        Thread cur = Thread.currentThread();
                        System.out.printf("[%.3f] [TID=%d|%s] -> SOLUCIÓN ENCONTRADA (iterativo)\n",
                                (System.currentTimeMillis() - start) / 1000.0,
                                cur.getId(), cur.getName());
                    }
                    return;
                }
                GameMatrixImpl.FreeCellResult r = B.findLeastFreeCell(ci);
                if (r != GameMatrixImpl.FreeCellResult.FOUND) {
                    // Callejón sin salida
                    return;
                }
                int rr = ci.row, cc = ci.column;
                int mask = B.getFreeMask(rr, cc);
                int cnt = Integer.bitCount(mask);
                if (cnt == 0) {
                    // Sin candidatos
                    return;
                }
                if (cnt == 1) {
                    int v = Creator.getSetBitOffset(mask, 0);
                    B.set(rr, cc, (byte) v);
                    free -= 1;
                    // seguir comprimiendo
                } else {
                    // 2) Ramificar: una tarea hija por candidato
                    for (int b = 0; b < cnt; b++) {
                        if (solutionsFound.get() >= limit) break;
                        int v = Creator.getSetBitOffset(mask, b);
                        CachedGameMatrixImpl child = new CachedGameMatrixImpl(B.getSchema());
                        child.setAll(B.getArray());
                        child.set(rr, cc, (byte) v);
                        SudokuTask childTask = new SudokuTask(child, free - 1);
                        activeTasks.incrementAndGet();
                        taskQueue.add(childTask);
                        long tcc = incTasksCreated();
                        if ((tcc % DEFAULT_STATS_STEP) == 0) {
                            long now = System.currentTimeMillis();
                            Thread cur = Thread.currentThread();
                            System.out.printf("[%3.3f] [TID=%d|%s] Tareas creadas: %d. En cola: %d. Activas: %d. Soluciones: %d.%n",
                                    (now - start) / 1000.0,
                                    cur.getId(), cur.getName(),
                                    getTasksCreated(), taskQueue.size(), activeTasks.get(), solutionsFound.get());
                        }
                    }
                    return;
                }
            }
        } finally {
            if (!decDone) {
                activeTasks.decrementAndGet();
            }
        }
    }


    private static class SudokuTask implements Comparable<SudokuTask> {
        final CachedGameMatrixImpl board;
        final int freeCells;
        final int mrvValue;

        SudokuTask(CachedGameMatrixImpl board, int freeCells) {
            this.board = board;
            this.freeCells = freeCells;
            this.mrvValue = calculateMRV(board);
        }

        private int calculateMRV(CachedGameMatrixImpl b) {
            if (freeCells == 0) return Integer.MAX_VALUE;

            CellIndex minimumCell = new CellIndex();
            b.findLeastFreeCell(minimumCell);
            int freeMask = b.getFreeMask(minimumCell.row, minimumCell.column);
            return Integer.bitCount(freeMask);
        }

        @Override
        public int compareTo(SudokuTask other) {
            return Integer.compare(this.mrvValue, other.mrvValue);
        }
    }


    // --- MÉTODO SECUENCIAL (Para -p 1) ---
    private int backtrack(final int freeCells,
                          final CellIndex minimumCell,
                          final CachedGameMatrixImpl currentRiddle) {

        if ((incRecursive_calls() % DEFAULT_STATS_STEP) == 0) {
            long end = System.currentTimeMillis();
            System.out.printf("[%3.3f] Recursive Calls: %d. Free Cells: %d. Solutions Found: %d.\n", (end - start) / 1000.0, getRecursive_calls(), freeCells, possibleSolutions.size());
        }

        if (solutionsFound.get() >= limit) {
            return 0;
        }

        if (freeCells == 0) {
            if (solutionsFound.incrementAndGet() <= limit) {
                GameMatrix gmi = new GameMatrixImpl(currentRiddle.getSchema());
                gmi.setAll(currentRiddle.getArray());
                concurrentSolutions.add(gmi);
            }
            return 1;
        }

        GameMatrixImpl.FreeCellResult freeCellResult =
                currentRiddle.findLeastFreeCell(minimumCell);
        if (freeCellResult != GameMatrixImpl.FreeCellResult.FOUND) {
            return 0;
        }

        int result = 0;
        int minimumRow = minimumCell.row;
        int minimumColumn = minimumCell.column;
        int minimumFree = currentRiddle.getFreeMask(minimumRow, minimumColumn);
        int minimumBits = Integer.bitCount(minimumFree);

        for (int bit = 0; bit < minimumBits; bit++) {
            if (solutionsFound.get() >= limit) break;

            int index = Creator.getSetBitOffset(minimumFree, bit);
            currentRiddle.set(minimumRow, minimumColumn, (byte) index);
            result += backtrack(freeCells - 1, minimumCell, currentRiddle);
        }

        if (solutionsFound.get() < limit) {
            currentRiddle.set(minimumRow,
                    minimumColumn,
                    currentRiddle.getSchema().getUnsetValue());
        }

        return result;
    }
}