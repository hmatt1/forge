/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.ai2;

import forge.ai.AiControllerAbstract;
import forge.ai.ComputerUtilCard;
import forge.game.*;
import forge.game.card.*;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class MattAiController extends AiControllerAbstract {
    LLMApi api = new LLMApi();

    public MattAiController(final Player computerPlayer, final Game game0) {
        super(computerPlayer, game0);
    }

    @Override
    public Card chooseBestLandToPlay(CardCollection landList) {
        if (landList.isEmpty()) {
            return null;
        }

        landList = ComputerUtilCard.dedupeCards(landList);

        CardCollection nonLandsInHand = CardLists.filter(player.getCardsIn(ZoneType.Hand), CardPredicates.NON_LANDS);

        try {
            var response = api.chooseBestLandToPlay(game, player);
            if (response != null) {
                var possibleChoice = landList.filter(card -> card.getName().equals(response)).stream().findAny();
                if (possibleChoice.isPresent()) {
                    System.out.println("PLAYING LAND!!! " + possibleChoice.get().getName());
                    return possibleChoice.get();
                } else {
                    System.out.println("PLAYING LAND FROM PARTIAL MATCH!!! " + possibleChoice.get().getName());
                    var partialMatch = landList.filter(card -> card.getName().contains(response)).stream().findAny();
                    if (partialMatch.isPresent()) {
                        return partialMatch.get();
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


        var result = super.chooseBestLandToPlay(landList);
        return result;
    }
}
