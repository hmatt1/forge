package forge.ai2;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

public class MattPlayerControllerAi extends PlayerControllerAi {

    public MattPlayerControllerAi(Game game, Player player, LobbyPlayer lobbyPlayer) {
        super(game, player, lobbyPlayer, new MattAiController(player, game));
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        var result = super.chooseSpellAbilityToPlay();
         return result;
    }
}

