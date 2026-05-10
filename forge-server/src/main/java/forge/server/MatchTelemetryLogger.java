package forge.server;

import forge.game.Game;
import forge.proto.MatchResult;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

public class MatchTelemetryLogger {

    private final String csvPath;

    public MatchTelemetryLogger(String csvPath) {
        this.csvPath = csvPath;
        initHeader();
    }

    private synchronized void initHeader() {
        if (!Files.exists(Paths.get(csvPath))) {
            try (PrintWriter out = new PrintWriter(new FileWriter(csvPath))) {
                out.println("MatchId,Player1,Player2,Deck1,Deck2,Winner,Turns,WinReason,DNF");
            } catch (IOException e) {
                System.err.println("Failed to initialize telemetry CSV: " + e.getMessage());
            }
        }
    }

    public synchronized void logMatch(Game game, String deck1, String deck2, MatchResult result) {
        try (PrintWriter out = new PrintWriter(new FileWriter(csvPath, true))) {
            StringBuilder sb = new StringBuilder();
            sb.append(result.getMatchId()).append(",");
            sb.append(game.getPlayers().get(0).getName()).append(",");
            sb.append(game.getPlayers().get(1).getName()).append(",");
            sb.append(deck1).append(",");
            sb.append(deck2).append(",");
            sb.append(result.getWinnerName()).append(",");
            sb.append(result.getNumTurns()).append(",");
            sb.append(result.getWinReason()).append(",");
            sb.append(result.getDnf());
            out.println(sb.toString());
        } catch (IOException e) {
            System.err.println("Failed to log match telemetry: " + e.getMessage());
        }
    }
}
