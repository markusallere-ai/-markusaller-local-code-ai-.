package ai;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Tiny UCI subprocess fixture for verifying process startup, commands, parsing, and shutdown. */
public final class FakeStockfishEngine {
    private FakeStockfishEngine() {}

    public static void main(String[] args) throws Exception {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
            String command;
            while ((command = input.readLine()) != null) {
                if (command.equals("uci")) {
                    System.out.println("id name FakeStockfish");
                    System.out.println("uciok");
                } else if (command.equals("isready")) {
                    System.out.println("readyok");
                } else if (command.startsWith("go ")) {
                    String forcedMove = command.contains("searchmoves ")
                        ? command.substring(command.indexOf("searchmoves ") + "searchmoves ".length())
                            .split(" ")[0]
                        : "";
                    String move = forcedMove.isEmpty() ? "e2e4" : forcedMove;
                    System.out.println("info depth 14 score cp 25 nodes 12 pv " + move);
                    System.out.println("bestmove " + move);
                } else if (command.equals("quit")) {
                    return;
                }
                System.out.flush();
            }
        }
    }
}
