package forge.server;

import com.google.protobuf.util.JsonFormat;
import forge.ai.AiDecisionListener;
import forge.proto.GameState;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class DecisionTelemetryLogger implements AiDecisionListener {

    private final String logPath;
    private final JsonFormat.Printer printer;

    public DecisionTelemetryLogger(String logPath) {
        this.logPath = logPath;
        this.printer = JsonFormat.printer().omittingInsignificantWhitespace();
    }

    @Override
    public void onDecision(GameState state, String chosenActionId) {
        try (PrintWriter out = new PrintWriter(new FileWriter(logPath, true))) {
            String jsonState = printer.print(state);
            // We log a single line with state and action
            // In a real Parquet implementation, this would be a row in a table
            out.println("{\"action_id\":\"" + chosenActionId + "\", \"state\":" + jsonState + "}");
        } catch (IOException e) {
            System.err.println("Failed to log decision telemetry: " + e.getMessage());
        }
    }
}
