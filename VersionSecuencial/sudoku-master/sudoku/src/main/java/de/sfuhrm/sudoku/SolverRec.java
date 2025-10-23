package de.sfuhrm.sudoku;

import java.util.List;

/* Versión mínima: delega en Solver (ya funciona con -m rec) */
public final class SolverRec {
    private final Solver base;

    public SolverRec(GameMatrix solveMe) {
        this.base = new Solver(solveMe);
    }

    public List<GameMatrix> solve() {
        return base.solve();
    }
}