/* ---------------------------------------------------------------
Práctica 1.
Código fuente: SolverIt.java
Grau GEIADE
41533494W - Antonio Cayuela Lopez
48054965F - Alejandro Fernandez Mimbrera
--------------------------------------------------------------- */

/*
Sudoku - a fast Java Sudoku game creation library.
Copyright (C) 2017  Stephan Fuhrmann

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

package de.sfuhrm.sudoku.client;

import de.sfuhrm.sudoku.Creator;
import de.sfuhrm.sudoku.GameMatrix;
import de.sfuhrm.sudoku.GameMatrixFactory;
import de.sfuhrm.sudoku.GameSchema;
import de.sfuhrm.sudoku.GameSchemas;
import de.sfuhrm.sudoku.QuadraticArrays;
import de.sfuhrm.sudoku.Riddle;
import de.sfuhrm.sudoku.Solver;
import de.sfuhrm.sudoku.SolverIt;
import de.sfuhrm.sudoku.SolverRec;
import de.sfuhrm.sudoku.output.GameMatrixFormatter;
import de.sfuhrm.sudoku.output.JsonArrayFormatter;
import de.sfuhrm.sudoku.output.LatexTableFormatter;
import de.sfuhrm.sudoku.output.MarkdownTableFormatter;
import de.sfuhrm.sudoku.output.PlainTextFormatter;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/**
 * Sudoku command-line client.
 */
public class Client {

    @Option(name = "-n", aliases = {"-count"}, usage = "Number of outputs to create")
    private int count = 1;

    enum Op {
        Full, Riddle, Both, Solve
    }

    enum Formatter {
        PlainText(PlainTextFormatter.class),
        MarkDownTable(MarkdownTableFormatter.class),
        LatexTable(LatexTableFormatter.class),
        JsonArray(JsonArrayFormatter.class);

        private final Class<? extends GameMatrixFormatter> clazz;

        Formatter(final Class<? extends GameMatrixFormatter> inClazz) {
            this.clazz = inClazz;
        }

        public GameMatrixFormatter newInstance() {
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException | InvocationTargetException |
                     IllegalAccessException | InstantiationException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    @Option(name = "-f", aliases = {"-format"}, usage = "Output format")
    private Formatter format = Formatter.PlainText;

    @Option(name = "-w", aliases = {"-writefile"}, usage = "Write riddle/solution to file")
    private boolean writefile;

    @Option(name = "-e", aliases = {"-exec"}, usage = "Operation to perform")
    private Op op = Op.Full;

    @Option(name = "-t", aliases = {"-time"}, usage = "Show timing information")
    private boolean timing;

    @Option(name = "-q", aliases = {"-quiet"}, usage = "No output")
    private boolean quiet;

    @Option(name = "-c", aliases = {"-numberstoclear"}, usage = "Numbers to clear")
    private int maxNumbersToClear = -1;

    @Option(name = "-s", aliases = {"-schema"}, usage = "Sudoku size (4x4, 9x9, 16x16, 25x25)")
    private SchemaEnum schema = SchemaEnum.S9X9;

    @Option(name = "-p", aliases = {"-threads"}, usage = "Number of threads")
    private int ThreadsNumber = 1;

    @Option(name = "-i", aliases = {"-input"}, usage = "Input sudoku file to solve")
    private Path input;

    @Option(name = "-o", aliases = {"-output"}, usage = "Output base name")
    private Path output;

    @Option(name = "-h", aliases = {"-help"}, usage = "Show help")
    private boolean help;

    // modos de ejecución añadidos para probar secuencial, iterativo y recursivo
    enum Mode { Auto, Seq, Iter, Rec }

    // opción para elegir el modo desde consola
    @Option(name = "-m", aliases = {"-mode"}, usage = "Solver mode: auto|seq|iter|rec")
    private Mode mode = Mode.Auto;

    private enum SchemaEnum {
        S4X4(GameSchemas.SCHEMA_4X4),
        S9X9(GameSchemas.SCHEMA_9X9),
        S16X16(GameSchemas.SCHEMA_16X16),
        S25X25(GameSchemas.SCHEMA_25X25);

        private final GameSchema schema;

        SchemaEnum(final GameSchema inSchema) {
            this.schema = inSchema;
        }
    }

    private GameSchema getSchema() {
        return schema.schema;
    }

    /** Solve sudoku */
    private void solve(final GameMatrixFormatter formatter)
            throws FileNotFoundException, IOException {
        if (op == Op.Solve && input == null) {
            throw new IllegalArgumentException("Expecting input file for Solve");
        }

        List<String> lines = Files.readAllLines(input);
        lines = lines.stream()
                .filter(l -> !l.isEmpty())
                .map(l -> l.replaceAll("__", "0"))
                .collect(Collectors.toList());

        byte[][] data = QuadraticArrays.parse(lines.toArray(new String[0]));

        GameMatrix gameMatrix = new GameMatrixFactory().newGameMatrix(getSchema());
        gameMatrix.setAll(data);

        List<GameMatrix> solutions;

        if (mode == Mode.Seq || (mode == Mode.Auto && ThreadsNumber <= 1)) {
            // modo secuencial, usado como referencia
            Solver solver = new Solver(gameMatrix);
            solutions = solver.solve();

        } else if (mode == Mode.Iter || (mode == Mode.Auto && ThreadsNumber > 1)) {
            // modo iterativo concurrente con varios hilos
            System.setProperty("sudoku.threads", Integer.toString(ThreadsNumber));
            SolverIt solver = new SolverIt(gameMatrix);
            solver.setNumThreads(ThreadsNumber);
            solutions = solver.solve();
        } else {
            // modo recursivo concurrente con varios hilos
            System.setProperty("sudoku.threads", Integer.toString(ThreadsNumber));
            SolverRec solver = new SolverRec(gameMatrix);
            solutions = solver.solve();
        }

        if (solutions.isEmpty()) {
            System.out.println("No Sudoku solution found.");
        } else {
            if (!quiet) {
                for (GameMatrix r : solutions) {
                    System.out.println(formatter.format(r));
                }
            }
            if (writefile) {
                writeSolution(formatter, solutions.get(0));
            }
        }
    }

    /** Run the selected operation */
    private void run() throws IOException {
        GameMatrixFormatter formatter = format.newInstance();
        long start = System.currentTimeMillis();

        if (!quiet) {
            System.out.print(formatter.documentStart());
        }

        if (op == Op.Solve) {
            solve(formatter); // llamada al método con los modos añadidos
        } else {
            for (int i = 0; i < count; i++) {
                GameMatrix matrix;
                Riddle riddle;
                switch (op) {
                    case Full:
                        matrix = Creator.createFull(getSchema());
                        if (!quiet) System.out.println(formatter.format(matrix));
                        if (writefile) writeSolution(formatter, matrix);
                        break;

                    case Riddle:
                        matrix = Creator.createFull(getSchema());
                        riddle = maxNumbersToClear > 0 ?
                                Creator.createRiddle(matrix, maxNumbersToClear) :
                                Creator.createRiddle(matrix);
                        if (!quiet) System.out.println(formatter.format(riddle));
                        if (writefile) writeRiddle(formatter, riddle);
                        break;

                    case Both:
                        matrix = Creator.createFull(getSchema());
                        riddle = maxNumbersToClear > 0 ?
                                Creator.createRiddle(matrix, maxNumbersToClear) :
                                Creator.createRiddle(matrix);
                        if (!quiet) {
                            System.out.println(formatter.format(matrix));
                            System.out.println(formatter.format(riddle));
                        }
                        if (writefile) {
                            writeSolution(formatter, matrix);
                            writeRiddle(formatter, riddle);
                        }
                        break;
                    default:
                        throw new IllegalStateException("Unhandled case " + op);
                }
            }
        }

        long end = System.currentTimeMillis();
        if (!quiet) {
            System.out.print(formatter.documentEnd());
        }

        if (timing) {
            System.err.printf("Total time: %.3f s%n", (end - start) / 1000.0);
        }
    }

    private void writeSolution(final GameMatrixFormatter formatter, GameMatrix solution) {
        try (FileWriter myWriter = new FileWriter(output.toString() + ".sol.txt")) {
            myWriter.write(formatter.format(solution).toString());
        } catch (IOException e) {
            System.err.println("Error writing solution: " + e.getMessage());
        }
    }

    private void writeRiddle(final GameMatrixFormatter formatter, GameMatrix riddle) {
        try (FileWriter myWriter = new FileWriter(output.toString() + ".sdku.txt")) {
            myWriter.write(formatter.format(riddle).toString());
        } catch (IOException e) {
            System.err.println("Error writing riddle: " + e.getMessage());
        }
    }

    public static void main(final String[] args)
            throws CmdLineException, IOException {
        Client client = new Client();
        CmdLineParser parser = new CmdLineParser(client);
        parser.parseArgument(args);
        if (client.help) {
            parser.printUsage(System.out);
            return;
        }
        client.run();
    }
}