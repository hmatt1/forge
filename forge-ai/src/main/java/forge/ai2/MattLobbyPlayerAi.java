package forge.ai2;

import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

import java.util.Set;

public class MattLobbyPlayerAi extends LobbyPlayerAi {

    public MattLobbyPlayerAi(String name, Set<AIOption> options) {
        super(name, options);
    }

    private PlayerController createControllerForPlayer(Player ai) {
        MattAiController controller = new MattAiController(ai, ai.getGame());
        PlayerControllerAi result = new PlayerControllerAi(ai.getGame(), ai, this);
        result.setAiController(controller);
        result.setUseSimulation(useSimulation);
        return result;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return this.createControllerForPlayer(slave);
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player ai = new Player(getName(), game, id);
        ai.setFirstController(this.createControllerForPlayer(ai));

        return ai;
    }
}
