package forge.ai;

import forge.proto.GameState;

public interface AiDecisionListener {
    void onDecision(GameState state, String chosenActionId);
}
