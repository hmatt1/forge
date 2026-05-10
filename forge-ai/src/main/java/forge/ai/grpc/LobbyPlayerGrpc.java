package forge.ai.grpc;

import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

import java.util.Set;

public class LobbyPlayerGrpc extends LobbyPlayerAi {

    private final String grpcEndpoint;
    private forge.ai.AiDecisionListener decisionListener;

    public LobbyPlayerGrpc(String name, Set<AIOption> options, String grpcEndpoint) {
        super(name, options);
        this.grpcEndpoint = grpcEndpoint;
    }

    public void setDecisionListener(forge.ai.AiDecisionListener listener) {
        this.decisionListener = listener;
    }

    private PlayerController createControllerFor(Player ai) {
        GrpcAiController grpcAi = new GrpcAiController(ai, ai.getGame(), grpcEndpoint);
        if (decisionListener != null) {
            grpcAi.addDecisionListener(decisionListener);
        }
        PlayerControllerAi result = new PlayerControllerAi(ai.getGame(), ai, this);
        result.setAiController(grpcAi);
        result.setUseSimulation(useSimulation);
        return result;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return createControllerFor(slave);
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player ai = new Player(getName(), game, id);
        ai.setFirstController(createControllerFor(ai));
        return ai;
    }
}
