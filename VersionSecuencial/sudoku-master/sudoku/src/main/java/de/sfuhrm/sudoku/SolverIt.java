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

/**
 * Versión 2: Concurrente ITERATIVA con hilos de alto nivel.
 * - Misma estrategia que SolverRec: semillas (primeros p tableros distintos)
 * - Lectura de -p por propiedad "sudoku.threads", cap a nº de cores
 * - Sin recursión: backtracking iterativo con pila explícita
 * - Determinista en orden de candidatos y semillas
 */
public final class SolverIt {

    /** Copia de trabajo (solo lectura aquí). */
    private final CachedGameMatrixImpl riddle;

    /** Soluciones encontradas. */
    private final List<GameMatrix> possibleSolutions = new ArrayList<>();

    public static final int DEFAULT_LIMIT = 1;
    private int limit = DEFAULT_LIMIT;

    /** Medición simple. */
    private static long start;

    /** Nº de hilos efectivos (capado a cores). */
    private final int numThreads;

    /** Construye a partir de una matriz de juego. */
    public SolverIt(final GameMatrix solveMe) {
        Objects.requireNonNull(solveMe, "solveMe is null");
        this.riddle = new CachedGameMatrixImpl(solveMe.getSchema());
        this.riddle.setAll(solveMe.getArray());
        this.numThreads = resolveThreads();
    }

    /** Límite de soluciones a buscar. */
    public void setLimit(final int set) {
        this.limit = set;
    }

    /** Lee "-p" como en SolverRec y lo capa a nº de cores. */
    private int resolveThreads() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int requested = cores;
        String prop = System.getProperty("sudoku.threads");
        if (prop != null) {
            try {
                requested = Integer.parseInt(prop.trim());
            } catch (NumberFormatException ignored) {
                requested = cores;
            }
        }
        if (requested <= 0) requested = cores;
        return Math.min(requested, cores);
    }

    /** Punto de entrada: versión iterativa de alto nivel. */
    public List<GameMatrix> solve() {
        start = System.currentTimeMillis();
        possibleSolutions.clear();

        final int total = riddle.getSchema().getTotalFields();
        final int freeCells = total - riddle.getSetCount();

        if (freeCells == 0) {
            GameMatrix g = new GameMatrixImpl(riddle.getSchema());
            g.setAll(riddle.getArray());
            possibleSolutions.add(g);
            printSummary(freeCells);
            return Collections.unmodifiableList(possibleSolutions);
        }

        // 1) Semillas: p primeros tableros distintos (determinista)
        List<Seed> seeds = buildSeeds(numThreads);
        if (seeds.isEmpty()) {
            printSummary(freeCells);
            return Collections.unmodifiableList(possibleSolutions);
        }

        // 2) Pool fijo y tareas (una por semilla)
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        try {
            if (limit <= 1) {
                // Primera solución: recoger el PRIMER resultado NO NULO
                ExecutorCompletionService<GameMatrix> ecs = new ExecutorCompletionService<>(pool);
                List<Future<GameMatrix>> submitted = new ArrayList<>(seeds.size());
                for (Seed s : seeds) {
                    submitted.add(ecs.submit(() -> solveOneIterative(s.board, s.freeCells)));
                }
                int remaining = seeds.size();
                while (remaining-- > 0) {
                    try {
                        Future<GameMatrix> f = ecs.take(); // bloquea hasta que una termina
                        GameMatrix sol = f.get();
                        if (sol != null) {
                            possibleSolutions.add(sol);
                            // Cancelar el resto de tareas en curso
                            for (Future<GameMatrix> other : submitted) {
                                if (!other.isDone()) other.cancel(true);
                            }
                            break;
                        }
                    } catch (ExecutionException e) {
                        // tarea fallida: seguimos con las demás
                    }
                }
            } else {
                // Varias soluciones (hasta limit): lanzamos todas y recogemos
                List<Future<GameMatrix>> futures = new ArrayList<>(seeds.size());
                for (Seed s : seeds) {
                    futures.add(pool.submit(() -> solveOneIterative(s.board, s.freeCells)));
                }
                for (Future<GameMatrix> f : futures) {
                    if (possibleSolutions.size() >= limit) break;
                    try {
                        GameMatrix g = f.get();
                        if (g != null && possibleSolutions.size() < limit) {
                            possibleSolutions.add(g);
                        }
                    } catch (ExecutionException | InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
            try { pool.awaitTermination(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }

        printSummary(freeCells);
        return Collections.unmodifiableList(possibleSolutions);
    }

    /** Traza simple de fin. */
    private void printSummary(int freeCells) {
        long end = System.currentTimeMillis();
        System.out.printf("[%3.3f] SUDOKU DONE. Free Cells: %d. Solutions Found: %d. Threads: %d%n%n",
                (end - start) / 1000.0,
                freeCells,
                possibleSolutions.size(),
                numThreads);
    }

    // ----------------------------------------------------------------------
    // Semillas: mismo enfoque que en SolverRec (BFS determinista + forzados)
    // ----------------------------------------------------------------------

    /** Par simple para (tablero, celdas libres). */
    private static final class Seed {
        final CachedGameMatrixImpl board;
        final int freeCells;
        Seed(CachedGameMatrixImpl b, int f) { board = b; freeCells = f; }
    }

    /** Construye hasta 'wanted' semillas: forzados inline y ramifica en ≥2. */
    private List<Seed> buildSeeds(final int wanted) {
        // Permitir hasta 16 semillas (máximo lógico para Sudoku 16x16)
        int maxSeeds = Math.min(16, wanted);
        List<Seed> out = new ArrayList<>(maxSeeds);
        if (maxSeeds <= 0) return out;

        CachedGameMatrixImpl startBoard = new CachedGameMatrixImpl(riddle.getSchema());
        startBoard.setAll(riddle.getArray());
        int startFree = riddle.getSchema().getTotalFields() - riddle.getSetCount();

        ArrayDeque<Seed> q = new ArrayDeque<>();
        q.add(new Seed(startBoard, startFree));
        CellIndex tmp = new CellIndex();

        while (out.size() < maxSeeds && !q.isEmpty()) {
            Seed cur = q.removeFirst();
            CachedGameMatrixImpl B = cur.board;
            int free = cur.freeCells;

            // comprimir forzados en línea
            while (true) {
                if (free == 0) { out.add(new Seed(B, free)); break; }
                GameMatrixImpl.FreeCellResult r = B.findLeastFreeCell(tmp);
                if (r != GameMatrixImpl.FreeCellResult.FOUND) { break; } // callejón sin salida
                int rr = tmp.row, cc = tmp.column;
                int mask = B.getFreeMask(rr, cc);
                int cnt = Integer.bitCount(mask);
                if (cnt == 0) { break; } // sin solución

                if (cnt == 1) {
                    int v = Creator.getSetBitOffset(mask, 0);
                    B.set(rr, cc, (byte) v);
                    free -= 1;
                    continue; // seguir comprimiendo
                } else {
                    // ramificar: crear hijos en orden determinista
                    for (int k = 0; k < cnt && out.size() < maxSeeds; k++) {
                        int v = Creator.getSetBitOffset(mask, k);
                        CachedGameMatrixImpl child = new CachedGameMatrixImpl(B.getSchema());
                        child.setAll(B.getArray());
                        child.set(rr, cc, (byte) v);
                        out.add(new Seed(child, free - 1));
                    }
                    // permitir seguir bajando por la primera rama si aún faltan semillas
                    if (out.size() < maxSeeds) {
                        int v0 = Creator.getSetBitOffset(mask, 0);
                        CachedGameMatrixImpl deeper = new CachedGameMatrixImpl(B.getSchema());
                        deeper.setAll(B.getArray());
                        deeper.set(rr, cc, (byte) v0);
                        q.add(new Seed(deeper, free - 1));
                    }
                    break;
                }
            }
        }
        return out;
    }

    // ----------------------------------------------------------------------
    // Backtracking iterativo (pila explícita). Sin recursión.
    // Devuelve UNA solución (o null) a partir de un tablero semilla.
    // ----------------------------------------------------------------------

    /** Frame de la pila: celda fija y candidatos pendientes. */
    private static final class Frame {
        final int row, col;
        final int[] vals;   // candidatos ordenados
        int idx;            // siguiente candidato a probar
        Frame(int r, int c, int[] v) { row = r; col = c; vals = v; idx = 0; }
        boolean hasNext() { return idx < vals.length; }
        int next() { return vals[idx++]; }
    }

    private GameMatrix solveOneIterative(CachedGameMatrixImpl board, int freeCells) {
        final byte UNSET = board.getSchema().getUnsetValue();
        final Deque<Frame> stack = new ArrayDeque<>();
        final CellIndex min = new CellIndex();

        // función local: prepara siguiente MRV y apila su frame
        final Runnable pushNextMRV = () -> {
            GameMatrixImpl.FreeCellResult res = board.findLeastFreeCell(min);
            if (res != GameMatrixImpl.FreeCellResult.FOUND) { // sin salida
                stack.push(new Frame(-1, -1, new int[0])); // marca imposible
                return;
            }
            int r = min.row, c = min.column;
            int mask = board.getFreeMask(r, c);
            int bits = Integer.bitCount(mask);
            if (bits == 0) {
                stack.push(new Frame(-1, -1, new int[0]));
                return;
            }
            int[] vals = new int[bits];
            for (int i = 0; i < bits; i++) vals[i] = Creator.getSetBitOffset(mask, i);
            stack.push(new Frame(r, c, vals));
        };

        // Si la semilla ya está completa, devolver solución inmediata
        if (freeCells == 0) {
            GameMatrix g = new GameMatrixImpl(board.getSchema());
            g.setAll(board.getArray());
            return g;
        }

        // Inicial: apilar primer MRV
        pushNextMRV.run();

        while (!stack.isEmpty() && !Thread.currentThread().isInterrupted()) {
            Frame top = stack.peek();

            // Marca "imposible": retroceder
            if (top.row == -1) {
                stack.pop();
                // deshacer en marco anterior si lo hubiera
                if (!stack.isEmpty()) {
                    Frame prev = stack.peek();
                    board.set(prev.row, prev.col, UNSET);
                    freeCells += 1;
                }
                continue;
            }

            if (top.hasNext()) {
                // probar siguiente candidato en (row,col)
                int v = top.next();
                board.set(top.row, top.col, (byte) v);
                freeCells -= 1;

                if (freeCells == 0) {
                    GameMatrix g = new GameMatrixImpl(board.getSchema());
                    g.setAll(board.getArray());
                    return g;
                }

                // profundizar: apilar siguiente MRV
                pushNextMRV.run();

                // si el siguiente fue imposible, el bucle hará backtrack y deshará esta asignación
            } else {
                // exhausto: deshacer y retroceder
                stack.pop();
                board.set(top.row, top.col, UNSET);
                freeCells += 1;
            }
        }

        return null; // sin solución desde esta semilla
    }
}