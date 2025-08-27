package forge.ai2;

import forge.ai.AIOption;
import forge.ai.AiProfileUtil;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.LobbyPlayer;
import forge.game.player.PlayerController;

import java.util.Set;

public class MattLobbyPlayerAi extends LobbyPlayerAi {

    public MattLobbyPlayerAi(String name, Set<AIOption> options) {
        super(name, options);
    }

    private PlayerController createControllerForPlayer(Player ai) {
        PlayerControllerAi result = new MattPlayerControllerAi(ai.getGame(), ai, this);
        result.setUseSimulation(useSimulation);
        result.allowCheatShuffle(allowCheatShuffle);
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
