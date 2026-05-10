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

import forge.ai.*;
import forge.game.*;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static forge.ai.AiPlayDecision.WillPlay;

public class MattAiController extends forge.ai.AiController {
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

        if (landList.size() == 1) {
            return landList.get(0);
        }

        CardCollection nonLandsInHand = CardLists.filter(player.getCardsIn(ZoneType.Hand), CardPredicates.NON_LANDS);

        String response = null;
        try {
            response = api.chooseBestLandToPlay(game, player);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (response != null) {
            final String finalResponse = response;
            var possibleChoice = landList.filter(card -> card.getName().equals(finalResponse)).stream().findAny();
            if (possibleChoice.isPresent()) {
                System.out.println("PLAYING LAND!!! " + possibleChoice.get().getName());
                return possibleChoice.get();
            } else {
                var partialMatch = landList.filter(card -> card.getName().contains(finalResponse)).stream().findAny();
                if (partialMatch.isPresent()) {
                    System.out.println("PLAYING LAND FROM PARTIAL MATCH!!! " + partialMatch.get().getName());
                    return partialMatch.get();
                }
            }
        }

        var result = super.chooseBestLandToPlay(landList);
        return result;
    }

    @Override
    public SpellAbility chooseSpellAbilityToPlayFromList(List<SpellAbility> all, boolean skipCounter) {
        if (all == null || all.isEmpty()) {
            return null;
        }

        var playableSpellAbilityList = new ArrayList<SpellAbility>();
        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player)) {
            if (skipCounter && sa.getApi() == ApiType.Counter) {
                continue;
            }

            if (canPlayAndPayFor(sa) == WillPlay) {
                playableSpellAbilityList.add(sa);
            }

            sa.clearLastState();
        }

        if (playableSpellAbilityList.isEmpty()) {
            return null;
        }

        String response = null;
        try {
            response = api.chooseBestSpellAbilityToPlayFromList(playableSpellAbilityList, game, player);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (response != null) {
            final String finalResponse = response;
            var possibleChoice = all.stream().filter(sa -> sa.getDescription().contains(finalResponse)).findAny();
            if (possibleChoice.isPresent()) {
                System.out.println("PLAYING SPELL OR ABILITY!!! " + possibleChoice.get().getDescription());
                var sa = possibleChoice.get();

                sa.setActivatingPlayer(player);
                SpellAbility root = sa.getRootAbility();
                if (root != sa) {
                    root.setActivatingPlayer(player);
                }

                if (playableSpellAbilityList.stream().noneMatch(s -> s.getDescription().equals(sa.getDescription()))) {
                    System.out.println("Wait, it chose a spell that isn't playable?");
                    for (var s : playableSpellAbilityList) {
                        System.out.println("PLAYABLE:\n" + s.getDescription());
                    }
                    throw new RuntimeException("ERROR!!");
                }
            }
        }

        return null;
    }
}
